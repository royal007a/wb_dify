package com.hify.provider.application;

import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class ProviderUrlPolicy {
    public String validate(String value) {
        try {
            URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new BizException(ErrorCode.PARAM_ERROR, "Provider Base URL 只允许 http/https");
            }
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "Provider Base URL 格式无效");
            }
            return stripTrailingSlash(uri.toString());
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BizException(ErrorCode.PARAM_ERROR, "Provider Base URL 格式无效");
        }
    }

    private String stripTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
