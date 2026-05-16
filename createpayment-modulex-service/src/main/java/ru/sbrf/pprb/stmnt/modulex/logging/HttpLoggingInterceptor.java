package ru.sbrf.pprb.stmnt.modulex.logging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Логирует исходящий HTTP вокруг {@code RestTemplate}: метод, URL,
 * заголовки, тело запроса, статус, заголовки и тело ответа, время.
 * Уровень — {@code DEBUG}; чтобы отключить — выкрутить логгер в INFO.
 *
 * <p>RestTemplate, к которому привязан этот интерцептор, должен быть обёрнут
 * в {@code BufferingClientHttpRequestFactory}, иначе response.getBody()
 * можно прочитать только один раз.</p>
 */
@Slf4j
public class HttpLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final int MAX_BODY_LOG = 16_384;
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "x-auth-token");

    private final String name;

    public HttpLoggingInterceptor(String name) {
        this.name = name;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        long start = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("HTTP → [{}] {} {} headers={} body={}",
                    name, request.getMethod(), request.getURI(),
                    headers(request.getHeaders()), body(body));
        }
        try {
            ClientHttpResponse response = execution.execute(request, body);
            long ms = System.currentTimeMillis() - start;
            if (log.isDebugEnabled()) {
                byte[] respBody = StreamUtils.copyToByteArray(response.getBody());
                log.debug("HTTP ← [{}] {} ({} ms) headers={} body={}",
                        name, response.getStatusCode(), ms,
                        headers(response.getHeaders()), body(respBody));
            }
            return response;
        } catch (IOException | RuntimeException e) {
            long ms = System.currentTimeMillis() - start;
            log.warn("HTTP ✗ [{}] {} {} ({} ms) failed: {}",
                    name, request.getMethod(), request.getURI(), ms, e.getMessage());
            throw e;
        }
    }

    private static String headers(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) return "{}";
        return headers.entrySet().stream()
                .map(e -> e.getKey() + "=" + maskValue(e.getKey(), e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private static String maskValue(String key, List<String> values) {
        if (SENSITIVE_HEADERS.contains(key.toLowerCase())) return "[masked]";
        return String.join(",", values);
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
