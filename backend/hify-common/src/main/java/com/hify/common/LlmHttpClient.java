package com.hify.common;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class LlmHttpClient {
    private static final Logger log = LoggerFactory.getLogger(LlmHttpClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(65);

    private final RestClient restClient;
    private final OkHttpClient streamClient;
    private final Executor llmExecutor;

    public LlmHttpClient(@Qualifier("llmExecutor") Executor llmExecutor) {
        this.llmExecutor = llmExecutor;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.streamClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(120))
                .callTimeout(Duration.ofSeconds(125))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
    }

    public String post(String url, Map<String, String> headers, String body) {
        return post(url, headers, body, ExecutionControl.none());
    }

    public String post(String url, Map<String, String> headers, String body, ExecutionControl control) {
        long localDeadline = System.nanoTime() + OVERALL_TIMEOUT.toNanos();
        checkActive(control, localDeadline);
        FutureTask<String> future = new FutureTask<>(() -> {
            // Eligibility may have changed while waiting in the executor queue.
            checkActive(control, localDeadline);
            return executePost(url, headers, body);
        });
        try {
            llmExecutor.execute(future);
            while (true) {
                checkActive(control, localDeadline);
                long waitNanos = Math.max(1, control.remaining(Duration.ofMillis(100)).toNanos());
                try {
                    String result = future.get(waitNanos, TimeUnit.NANOSECONDS);
                    checkActive(control, localDeadline);
                    return result;
                } catch (TimeoutException ignored) {
                    // Poll the shared cancellation/deadline token instead of blocking for the full read timeout.
                }
            }
        } catch (RejectedExecutionException exception) {
            throw new ExecutionRejectedException();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ExecutionCancelledException("LLM request interrupted");
        } catch (ExecutionException exception) {
            checkActive(control, localDeadline);
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new LlmApiException(LlmApiException.Type.REQUEST_FAILED, "LLM request failed", exception.getCause());
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    public EventSource stream(String url, Map<String, String> headers, String body, StreamCallback callback) {
        Request.Builder request = new Request.Builder().url(url).post(RequestBody.create(body, JSON));
        headers.forEach(request::addHeader);
        long started = System.nanoTime();
        RequestLogContext.Snapshot context = RequestLogContext.capture();
        return EventSources.createFactory(streamClient).newEventSource(request.build(), new EventSourceListener() {
            @Override
            public void onOpen(EventSource source, Response response) {
                context.run(() -> log.info("llm.stream.open target={} status={} latencyMs={}",
                        safeTarget(url), response.code(), elapsedMs(started)));
            }

            @Override
            public void onEvent(EventSource source, String id, String type, String data) {
                context.run(() -> {
                    try {
                        callback.onEvent(id, type, data);
                    } catch (RuntimeException exception) {
                        source.cancel();
                        context.run(() -> callback.onFailure(new LlmApiException(LlmApiException.Type.REQUEST_FAILED,
                                "Invalid streaming response", exception)));
                    }
                });
            }

            @Override
            public void onClosed(EventSource source) {
                context.run(() -> {
                    log.info("llm.stream.closed target={} latencyMs={}", safeTarget(url), elapsedMs(started));
                    callback.onClosed();
                });
            }

            @Override
            public void onFailure(EventSource source, Throwable throwable, Response response) {
                context.run(() -> {
                    LlmApiException failure = classify(response == null ? null : response.code(), throwable);
                    log.warn("llm.stream.failed target={} type={} latencyMs={}",
                            safeTarget(url), failure.type(), elapsedMs(started));
                    callback.onFailure(failure);
                });
            }
        });
    }

    public void streamAndAwait(String url, Map<String, String> headers, String body,
                               ExecutionControl control, StreamCallback callback) {
        long localDeadline = System.nanoTime() + Duration.ofSeconds(125).toNanos();
        checkActive(control, localDeadline);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<LlmApiException> failure = new AtomicReference<>();
        EventSource source = stream(url, headers, body, new StreamCallback() {
            @Override public void onEvent(String id, String type, String data) {
                checkActive(control, localDeadline);
                callback.onEvent(id, type, data);
            }
            @Override public void onClosed() {
                try { callback.onClosed(); }
                finally { completed.countDown(); }
            }
            @Override public void onFailure(LlmApiException exception) {
                failure.compareAndSet(null, exception);
                try { callback.onFailure(exception); }
                finally { completed.countDown(); }
            }
        });
        try {
            while (true) {
                checkActive(control, localDeadline);
                if (completed.await(100, TimeUnit.MILLISECONDS)) break;
            }
            checkActive(control, localDeadline);
            if (failure.get() != null) throw failure.get();
        } catch (InterruptedException exception) {
            source.cancel();
            Thread.currentThread().interrupt();
            throw new ExecutionCancelledException("Streaming request interrupted");
        } finally {
            source.cancel();
        }
    }

    private static void checkActive(ExecutionControl control, long localDeadline) {
        control.throwIfCancelled();
        if (control.isExpired() || System.nanoTime() >= localDeadline) {
            throw new LlmApiException(LlmApiException.Type.TIMEOUT, "LLM request exceeded remaining run deadline");
        }
    }

    private String executePost(String url, Map<String, String> headers, String body) {
        long started = System.nanoTime();
        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(URI.create(url))
                    .headers(target -> headers.forEach(target::add))
                    .header(HttpHeaders.CONTENT_TYPE, org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw classify(response.getStatusCode().value(), null);
            }
            log.info("llm.http target={} status={} latencyMs={}", safeTarget(url),
                    response.getStatusCode().value(), elapsedMs(started));
            return response.getBody() == null ? "" : response.getBody();
        } catch (HttpStatusCodeException exception) {
            LlmApiException failure = classify(exception.getStatusCode().value(), exception);
            log.warn("llm.http.failed target={} status={} type={} latencyMs={}", safeTarget(url),
                    exception.getStatusCode().value(), failure.type(), elapsedMs(started));
            throw failure;
        } catch (ResourceAccessException exception) {
            LlmApiException failure = classify(null, exception);
            log.warn("llm.http.failed target={} type={} latencyMs={}", safeTarget(url),
                    failure.type(), elapsedMs(started));
            throw failure;
        }
    }

    private static LlmApiException classify(Integer status, Throwable cause) {
        if (status != null && (status == 401 || status == 403)) {
            return new LlmApiException(LlmApiException.Type.AUTH_FAILED, "LLM provider rejected credentials", cause);
        }
        if (status != null && status == 429) {
            return new LlmApiException(LlmApiException.Type.RATE_LIMITED, "LLM provider rate limited the request", cause);
        }
        if (status != null && status >= 500) {
            return new LlmApiException(LlmApiException.Type.PROVIDER_UNAVAILABLE, "LLM provider is unavailable", cause);
        }
        if (hasCause(cause, java.net.http.HttpTimeoutException.class)
                || hasCause(cause, java.net.SocketTimeoutException.class)) {
            return new LlmApiException(LlmApiException.Type.TIMEOUT, "LLM provider timed out", cause);
        }
        return new LlmApiException(LlmApiException.Type.REQUEST_FAILED, "LLM provider request failed", cause);
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (type.isInstance(cursor)) return true;
        }
        return false;
    }

    private static String safeTarget(String value) {
        URI uri = URI.create(value);
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + port + uri.getPath();
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    public interface StreamCallback {
        void onEvent(String id, String type, String data);
        void onClosed();
        void onFailure(LlmApiException exception);
    }
}
