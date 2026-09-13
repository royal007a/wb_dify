package com.hify.provider.runtime;

import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.provider.api.ProviderType;
import com.hify.runtime.ModelClient;
import com.hify.runtime.ModelRequest;
import com.hify.runtime.RuntimeMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ProviderAdapterRegistry {
    private final Map<ProviderType, ProviderProtocolAdapter> adapters;

    public ProviderAdapterRegistry(List<ProviderProtocolAdapter> adapters) {
        EnumMap<ProviderType, ProviderProtocolAdapter> indexed = new EnumMap<>(ProviderType.class);
        adapters.forEach(adapter -> {
            if (indexed.put(adapter.type(), adapter) != null) {
                throw new IllegalStateException("Duplicate Provider adapter: " + adapter.type());
            }
        });
        this.adapters = Map.copyOf(indexed);
    }

    public ModelClient create(ProviderRuntimeConfig provider) {
        ProviderProtocolAdapter adapter = adapters.get(provider.type());
        if (adapter == null) throw new IllegalStateException("Provider adapter is unavailable: " + provider.type());
        return adapter.create(provider);
    }

    public AdapterHealth test(ProviderRuntimeConfig provider) {
        long started = System.nanoTime();
        try {
            create(provider).generate(new ModelRequest(provider.defaultModelId(), 0,
                    List.of(RuntimeMessage.user("Reply with OK.")), List.of()));
            return new AdapterHealth(true, elapsedMs(started), "OK", "连接成功", LocalDateTime.now());
        } catch (com.hify.common.LlmApiException exception) {
            return new AdapterHealth(false, elapsedMs(started), exception.type().name(),
                    safeMessage(exception.type().name()), LocalDateTime.now());
        } catch (RuntimeException exception) {
            return new AdapterHealth(false, elapsedMs(started), "INVALID_RESPONSE",
                    "Provider 响应无效或配置不可用", LocalDateTime.now());
        }
    }

    private long elapsedMs(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String safeMessage(String code) {
        return switch (code) {
            case "AUTH_FAILED" -> "鉴权失败";
            case "RATE_LIMITED" -> "请求被限流";
            case "TIMEOUT" -> "连接超时";
            case "PROVIDER_UNAVAILABLE" -> "供应商暂不可用";
            default -> "连接失败";
        };
    }

    public record AdapterHealth(boolean success, long latencyMs, String code,
                                String message, LocalDateTime checkedAt) {}
}
