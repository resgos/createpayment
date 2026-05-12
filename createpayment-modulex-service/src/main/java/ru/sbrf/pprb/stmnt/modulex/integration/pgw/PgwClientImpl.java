package ru.sbrf.pprb.stmnt.modulex.integration.pgw;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import ru.sbrf.pprb.stmnt.modulex.config.PgwProperties;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.UPDDTO;

@Slf4j
@Component
public class PgwClientImpl implements PgwClient {

    private final PgwProperties properties;
    private final RestTemplate restTemplate;

    public PgwClientImpl(PgwProperties properties,
                         @Qualifier("pgwRestTemplate") RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Override
    public ApiResult transferUpd(String requestId, UPDDTO updDTO) {
        if (!properties.isEnabled()) {
            log.info("PGW disabled — skipping transferUpd for requestId={}, updUID={}",
                    requestId, updDTO.getUpdUID());
            return ApiResult.builder()
                    .correlationId(requestId)
                    .status("SKIPPED")
                    .message("PGW disabled")
                    .build();
        }

        String url = properties.getUrl() + properties.getTransferPath() + "?requestId=" + requestId;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<UPDDTO> entity = new HttpEntity<>(updDTO, headers);

        int attempts = Math.max(1, properties.getMaxAttempts());
        long delay = Math.max(0, properties.getRetryDelayMs());
        Exception lastError = null;

        for (int i = 1; i <= attempts; i++) {
            try {
                log.debug("PGW transferUpd attempt {}/{} requestId={} updUID={}",
                        i, attempts, requestId, updDTO.getUpdUID());
                ResponseEntity<ApiResult> resp = restTemplate.postForEntity(url, entity, ApiResult.class);
                ApiResult body = resp.getBody();
                if (body == null) {
                    body = ApiResult.builder().correlationId(requestId).build();
                }
                log.debug("PGW transferUpd ok: correlationId={}, idempotencyKey={}, status={}",
                        body.getCorrelationId(), body.getIdempotencyKey(), body.getStatus());
                return body;
            } catch (RestClientException e) {
                lastError = e;
                log.warn("PGW transferUpd attempt {}/{} failed: {}", i, attempts, e.getMessage());
                if (i < attempts) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        throw new IllegalStateException(
                "PGW transferUpd failed after " + attempts + " attempt(s): "
                        + (lastError != null ? lastError.getMessage() : "unknown error"),
                lastError);
    }
}
