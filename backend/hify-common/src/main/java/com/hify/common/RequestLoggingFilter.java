package com.hify.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = requestId(request.getHeader(HEADER));
        long started = System.nanoTime();
        String previousRequestId = MDC.get(RequestLogContext.REQUEST_ID);
        MDC.put("requestId", requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("http.request method={} path={} status={} latencyMs={}", request.getMethod(),
                    request.getRequestURI(), response.getStatus(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (previousRequestId == null) MDC.remove(RequestLogContext.REQUEST_ID);
            else MDC.put(RequestLogContext.REQUEST_ID, previousRequestId);
        }
    }

    private String requestId(String candidate) {
        if (RequestLogContext.valid(candidate)) return candidate;
        return UUID.randomUUID().toString();
    }
}
