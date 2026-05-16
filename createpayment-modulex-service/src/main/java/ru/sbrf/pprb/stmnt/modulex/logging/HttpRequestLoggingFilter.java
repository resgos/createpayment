package ru.sbrf.pprb.stmnt.modulex.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Логирует входящий HTTP — {@code /api/createPayment} (JSON-RPC) и
 * {@code /upd/response/execute} (REST PGW callback). Тело + заголовки +
 * статус + время.
 *
 * <p>Использует {@link ContentCachingRequestWrapper} /
 * {@link ContentCachingResponseWrapper}, чтобы тело можно было читать после
 * обработки. Уровень — {@code DEBUG}.</p>
 *
 * <p>Уровень {@code INFO} → отключение этого логирования.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpRequestLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_LOG = 16_384;
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "x-auth-token");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!log.isDebugEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = System.currentTimeMillis() - start;
            String fullUrl = req.getRequestURI()
                    + (req.getQueryString() != null ? "?" + req.getQueryString() : "");
            log.debug("HTTP IN  ← {} {} headers={} body={}",
                    req.getMethod(), fullUrl,
                    headers(req), body(req.getContentAsByteArray()));
            log.debug("HTTP IN  → {} ({} ms) body={}",
                    res.getStatus(), ms, body(res.getContentAsByteArray()));
            res.copyBodyToResponse();
        }
    }

    private static String headers(HttpServletRequest req) {
        Map<String, String> map = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        if (names != null) {
            Collections.list(names).forEach(n -> {
                String v = SENSITIVE_HEADERS.contains(n.toLowerCase())
                        ? "[masked]" : String.join(",", Collections.list(req.getHeaders(n)));
                map.put(n, v);
            });
        }
        return map.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private static String body(byte[] body) {
        if (body == null || body.length == 0) return "<empty>";
        if (body.length > MAX_BODY_LOG) {
            return new String(body, 0, MAX_BODY_LOG, StandardCharsets.UTF_8)
                    + "...<truncated " + (body.length - MAX_BODY_LOG) + " bytes>";
        }
        return new String(body, StandardCharsets.UTF_8);
    }
}
