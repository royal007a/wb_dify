package com.hify.provider.application;

import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ProviderUrlPolicy {
    private final boolean allowPrivate;
    private final Set<String> hostAllowlist;
    private final HostResolver resolver;

    @Autowired
    public ProviderUrlPolicy(@Value("${hify.provider.allow-private:false}") boolean allowPrivate,
                             @Value("${hify.provider.host-allowlist:}") String hostAllowlist) {
        this(allowPrivate, hostAllowlist, InetAddress::getAllByName);
    }

    ProviderUrlPolicy(boolean allowPrivate, String hostAllowlist, HostResolver resolver) {
        this.allowPrivate = allowPrivate;
        this.hostAllowlist = Arrays.stream(hostAllowlist.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.resolver = resolver;
    }

    public String validate(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new BizException(ErrorCode.PARAM_ERROR, "Provider Base URL 只允许 http/https");
            }
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "Provider Base URL 格式无效");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!allowPrivate && !allowlisted(host)) {
                InetAddress[] addresses = resolver.resolve(host);
                if (addresses.length == 0) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "Provider Base URL 无法解析");
                }
                for (InetAddress address : addresses) {
                    if (blocked(address)) {
                        throw new BizException(ErrorCode.FORBIDDEN,
                                "Provider Base URL 解析到内网、本机或保留地址");
                    }
                }
            }
            return stripTrailingSlash(uri.toString());
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(ErrorCode.PARAM_ERROR, "Provider Base URL 格式无效或无法解析");
        }
    }

    private boolean allowlisted(String host) {
        if (hostAllowlist.contains(host)) return true;
        return hostAllowlist.stream()
                .filter(pattern -> pattern.startsWith("*."))
                .map(pattern -> pattern.substring(1))
                .anyMatch(host::endsWith);
    }

    private boolean blocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) return (bytes[0] & 0xfe) == 0xfc;
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        return first == 0 || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 198 && (second == 18 || second == 19))
                || first >= 224;
    }

    private String stripTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws Exception;
    }
}
