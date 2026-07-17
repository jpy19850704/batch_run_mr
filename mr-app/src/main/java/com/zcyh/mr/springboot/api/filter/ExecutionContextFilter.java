package com.zcyh.mr.springboot.api.filter;

import com.zcyh.mr.springboot.runtime.ExecutionContext;
import com.zcyh.mr.springboot.runtime.ExecutionContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * 初始化执行链路上下文并写入 MDC。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExecutionContextFilter extends OncePerRequestFilter {
    private static final String HEADER_TRACE_ID = "X-Trace-Id";
    private static final String HEADER_REQUEST_ID = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ExecutionContext context = new ExecutionContext();
        context.setTraceId(firstNonBlank(request.getHeader(HEADER_TRACE_ID), newId("T")));
        context.setRequestId(firstNonBlank(request.getHeader(HEADER_REQUEST_ID), newId("R")));
        context.setRemoteIp(resolveRemoteIp(request));
        context.setRequestUri(request.getRequestURI());
        context.setMethod(request.getMethod());
        ExecutionContextHolder.bind(context);
        response.setHeader(HEADER_TRACE_ID, context.getTraceId());
        response.setHeader(HEADER_REQUEST_ID, context.getRequestId());
        try {
            filterChain.doFilter(request, response);
        } finally {
            ExecutionContextHolder.clear();
        }
    }

    private static String resolveRemoteIp(HttpServletRequest request) {
        String forwarded = firstNonBlank(request.getHeader("X-Forwarded-For"), request.getHeader("X-Real-IP"));
        if (forwarded != null) {
            int idx = forwarded.indexOf(',');
            return idx >= 0 ? forwarded.substring(0, idx).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String firstNonBlank(String first, String second) {
        String safe = trimToNull(first);
        return safe != null ? safe : trimToNull(second);
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
