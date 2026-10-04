package com.hify.workflow.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.*;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Operator-granted exact GET endpoints, without redirects or authority interpolation. */
@Component
public class WorkflowHttpClient {
    private final Set<String> endpoints;
    private final CredentialReferencePolicy credentials;
    private final OkHttpClient client;
    @Autowired
    public WorkflowHttpClient(@Value("${hify.workflow.http-allowed-endpoints:[]}") String config,
                              ObjectMapper json, CredentialReferencePolicy credentials) {
        this(config, json, credentials, new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(5))
                .callTimeout(Duration.ofSeconds(10)).connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS)).build());
    }
    WorkflowHttpClient(String config, ObjectMapper json, CredentialReferencePolicy credentials, OkHttpClient client) {
        this.credentials = credentials;
        try {
            List<String> input = json.readValue(config, new TypeReference<>() {});
            Set<String> parsed = new HashSet<>();
            for (String endpoint : input) parsed.add(canonical(endpoint));
            this.endpoints = Set.copyOf(parsed);
        } catch (Exception failure) { throw new IllegalStateException("Workflow HTTP endpoint grants are invalid"); }
        this.client = client;
    }
    static String canonical(String value) {
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.equals(uri.normalize()) || value.contains("{") || value.contains("}")
                    || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) throw new IllegalArgumentException();
            // Validate original spelling before OkHttp can normalize away ambiguous provenance.
            if (WorkflowAddressPolicy.literal(uri.getHost())) WorkflowAddressPolicy.numericAddress(uri.getHost());
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(value));
            if (WorkflowAddressPolicy.literal(url.host())) WorkflowAddressPolicy.numericAddress(url.host());
            // Reject encodings that would change endpoint identity during normalization.
            if (!uri.getRawPath().isEmpty() && !url.encodedPath().equals(uri.getRawPath())) throw new IllegalArgumentException();
            return url.toString();
        } catch (Exception failure) { throw invalid(); }
    }
    public void requireAllowed(String endpoint, String credentialRef) {
        if (!endpoints.contains(canonical(endpoint))) throw new BizException(ErrorCode.PARAM_ERROR,
                "HTTP 节点端点未获管理员精确授权");
        try { WorkflowAddressPolicy.checkLiteral(Objects.requireNonNull(HttpUrl.parse(endpoint)).host()); }
        catch (UnknownHostException denied) { throw new BizException(ErrorCode.PARAM_ERROR, "HTTP 节点目标地址被安全策略拒绝"); }
        if (credentialRef != null && !credentialRef.isBlank()) credentials.requireAllowed(credentialRef, endpoint);
    }
    public String get(String endpoint, String credentialRef, Map<String, String> query, ExecutionControl control) {
        WorkflowControl.check(control);
        requireAllowed(endpoint, credentialRef);
        if (query.size() > 16) throw invalid();
        HttpUrl base = Objects.requireNonNull(HttpUrl.parse(canonical(endpoint)));
        HttpUrl.Builder url = base.newBuilder();
        for (var entry : query.entrySet()) {
            TextInput.requireNoNul(entry.getKey(), entry.getValue());
            if (entry.getKey().isBlank() || entry.getKey().length() > 128 || entry.getValue() == null
                    || entry.getValue().length() > 2048) throw invalid();
            url.addQueryParameter(entry.getKey(), entry.getValue());
        }
        String secret = credentialRef == null || credentialRef.isBlank() ? null : credentials.resolve(credentialRef, endpoint);
        if (secret != null && (secret.length() > 8192 || secret.chars().anyMatch(c -> c < 0x20 || c > 0x7e)))
            throw new BizException(ErrorCode.PARAM_ERROR, "HTTP 节点凭据格式无效");
        Request.Builder request = new Request.Builder().url(url.build()).get().header("Accept", "application/json, text/plain");
        if (secret != null) request.header("Authorization", "Bearer " + secret);
        // Lookup stays inside the cancellable call. Validated addresses are cached for this call only;
        // the URL hostname remains unchanged for Host, TLS SNI and certificate hostname verification.
        OkHttpClient scoped = client.newBuilder().dns(WorkflowAddressPolicy.pinnedDns(base.host(), client.dns()))
                .callTimeout(Math.max(1, control.remaining(Duration.ofSeconds(10)).toMillis()), TimeUnit.MILLISECONDS).build();
        WorkflowControl.check(control);
        Call call = scoped.newCall(request.build());
        CompletableFuture<String> result = new CompletableFuture<>();
        call.enqueue(new Callback() {
            @Override public void onFailure(Call ignored, IOException failure) { result.completeExceptionally(failure); }
            @Override public void onResponse(Call ignored, Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP status rejected");
                    if (response.headers("Content-Type").size() != 1) throw new IOException("HTTP content type rejected");
                    MediaType media = response.body().contentType();
                    if (media == null || !(media.type().equals("text") && media.subtype().equals("plain")
                            || media.type().equals("application") && (media.subtype().equals("json") || media.subtype().endsWith("+json"))))
                        throw new IOException("HTTP content type rejected");
                    long charsets = java.util.regex.Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=")
                            .matcher(response.header("Content-Type")).results().count();
                    if (charsets > 1 || (media.parameter("charset") != null
                            && !StandardCharsets.UTF_8.equals(Charset.forName(media.parameter("charset")))))
                        throw new IOException("HTTP charset rejected");
                    byte[] bytes = response.body().byteStream().readNBytes(32769);
                    if (bytes.length > 32768) throw new IOException("HTTP response exceeds limit");
                    String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                    TextInput.requireNoNul(value);
                    if (secret != null && value.contains(secret)) throw new IOException("Credential echo rejected");
                    result.complete(value);
                } catch (Exception failure) { result.completeExceptionally(failure); }
            }
        });
        try {
            while (true) {
                WorkflowControl.check(control);
                try { return result.get(50, TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) { /* bounded call timeout plus shared cancellation */ }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            control.throwIfCancelled();
            throw new ExecutionCancelledException("HTTP node interrupted");
        } catch (ExecutionException failure) {
            WorkflowControl.check(control);
            // A socket/node timeout is a failed node, not expiry of the parent Run.
            throw new BizException(ErrorCode.CONFLICT, "HTTP 节点调用失败或响应超限");
        } finally { call.cancel(); }
    }
    private static BizException invalid() { return new BizException(ErrorCode.PARAM_ERROR, "HTTP 节点配置或查询预算无效"); }
}
