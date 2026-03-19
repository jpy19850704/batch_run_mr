package com.zcyh.mr.springboot.filter;

import com.zcyh.mr.springboot.context.RequestContext;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.model.ApiResponse;
import com.zcyh.mr.springboot.service.AlertService;
import com.zcyh.mr.springboot.service.AuditLogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一接口鉴权过滤器。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {
    private final boolean securityEnabled;
    private final boolean requireUserHeader;
    private final boolean trustSourceHeaders;
    private final Map<String, String> tokenClientMapping;
    private final String[] excludePaths;
    private final AlertService alertService;
    private final AuditLogService auditLogService;

    public AuthFilter(
            @Value("${mr.security.enabled:false}") boolean securityEnabled,
            @Value("${mr.security.exclude-paths:/healthz,/readyz,/error}") String excludePaths,
            @Value("${mr.security.tokens:}") String tokenConfig,
            @Value("${mr.security.require-user-header:false}") boolean requireUserHeader,
            @Value("${mr.security.trust-source-headers:true}") boolean trustSourceHeaders,
            AlertService alertService,
            AuditLogService auditLogService
    ) {
        this.securityEnabled = securityEnabled;
        this.requireUserHeader = requireUserHeader;
        this.trustSourceHeaders = trustSourceHeaders;
        this.tokenClientMapping = parseTokenConfig(tokenConfig);
        this.excludePaths = splitExcludePaths(excludePaths);
        this.alertService = alertService;
        this.auditLogService = auditLogService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isExcluded(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        RequestContext context = RequestContextHolder.getOrCreate();
        fillTrustedHeaders(request, context);

        if (!securityEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = resolveToken(request);
        String clientId = token == null ? null : tokenClientMapping.get(token);
        if (clientId == null) {
            reject(request, response, "AUTH_FAILED", "鉴权失败，缺少有效访问令牌");
            return;
        }
        context.setClientId(clientId);
        if (requireUserHeader && trimToNull(context.getUserId()) == null) {
            reject(request, response, "AUTH_FAILED", "鉴权失败，缺少用户标识头");
            return;
        }
        RequestContextHolder.bind(context);
        filterChain.doFilter(request, response);
    }

    private void fillTrustedHeaders(HttpServletRequest request, RequestContext context) {
        if (!trustSourceHeaders) {
            return;
        }
        context.setUserId(firstNonBlank(context.getUserId(), request.getHeader("X-User-Id")));
        context.setUserName(firstNonBlank(context.getUserName(), request.getHeader("X-User-Name")));
        context.setSourceSystem(firstNonBlank(context.getSourceSystem(), request.getHeader("X-Source-System")));
        context.setClientId(firstNonBlank(context.getClientId(), request.getHeader("X-Client-Id")));
        RequestContextHolder.bind(context);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String code, String message) throws IOException {
        String uri = request.getRequestURI();
        alertService.warn(code, "uri=" + uri + ", message=" + message);
        auditLogService.recordFailure("AUTHENTICATION", "REQUEST", uri, null, code, message, 0L);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(com.alibaba.fastjson2.JSON.toJSONString(ApiResponse.fail(code, message)));
    }

    private boolean isExcluded(String uri) {
        String safeUri = trimToNull(uri);
        if (safeUri == null) {
            return false;
        }
        for (String exclude : excludePaths) {
            if (safeUri.startsWith(exclude)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveToken(HttpServletRequest request) {
        String authHeader = trimToNull(request.getHeader("Authorization"));
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimToNull(authHeader.substring(7));
        }
        return trimToNull(request.getHeader("X-MR-Token"));
    }

    private static Map<String, String> parseTokenConfig(String tokenConfig) {
        if (trimToNull(tokenConfig) == null) {
            return Collections.emptyMap();
        }
        Map<String, String> mapping = new LinkedHashMap<String, String>();
        String[] pairs = tokenConfig.split(",");
        for (String pair : pairs) {
            String safePair = trimToNull(pair);
            if (safePair == null) {
                continue;
            }
            int idx = safePair.indexOf(':');
            if (idx <= 0 || idx >= safePair.length() - 1) {
                continue;
            }
            String token = trimToNull(safePair.substring(0, idx));
            String clientId = trimToNull(safePair.substring(idx + 1));
            if (token != null && clientId != null) {
                mapping.put(token, clientId);
            }
        }
        return mapping;
    }

    private static String[] splitExcludePaths(String text) {
        if (trimToNull(text) == null) {
            return new String[0];
        }
        String[] parts = text.split(",");
        int count = 0;
        for (String part : parts) {
            if (trimToNull(part) != null) {
                count++;
            }
        }
        String[] result = new String[count];
        int idx = 0;
        for (String part : parts) {
            String safe = trimToNull(part);
            if (safe != null) {
                result[idx++] = safe;
            }
        }
        return result;
    }

    private static String firstNonBlank(String first, String second) {
        String safeFirst = trimToNull(first);
        return safeFirst != null ? safeFirst : trimToNull(second);
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
