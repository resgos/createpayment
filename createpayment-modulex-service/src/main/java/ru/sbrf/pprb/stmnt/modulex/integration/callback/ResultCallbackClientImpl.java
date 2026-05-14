package ru.sbrf.pprb.stmnt.modulex.integration.callback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionResult;
import ru.sbrf.pprb.stmnt.modulex.config.ResultCallbackProperties;

@Slf4j
@Component
public class ResultCallbackClientImpl implements ResultCallbackClient {

    private final ResultCallbackProperties properties;
    private final RestTemplate restTemplate;

    public ResultCallbackClientImpl(ResultCallbackProperties properties,
                                    @Qualifier("resultCallbackRestTemplate") RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Override
    public void send(ExecutionResult result) {
        if (!properties.isEnabled() || properties.getUrl() == null || properties.getUrl().isBlank()) {
            log.info("Result callback disabled — skipping. payload: bchOperationId={}, resultStatus={}",
                    result.getBchOperationId(), result.getResultStatus());
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<ExecutionResult> entity = new HttpEntity<>(result, headers);

        int attempts = Math.max(1, properties.getMaxAttempts());
        long delay = Math.max(0, properties.getRetryDelayMs());
        Exception lastError = null;

        for (int i = 1; i <= attempts; i++) {
            try {
                log.debug("Result callback attempt {}/{} url={} bchOpId={} status={}",
                        i, attempts, properties.getUrl(),
                        result.getBchOperationId(), result.getResultStatus());
                restTemplate.postForEntity(properties.getUrl(), entity, Void.class);
                return;
            } catch (RestClientException e) {
                lastError = e;
                log.warn("Result callback attempt {}/{} failed: {}", i, attempts, e.getMessage());
                if (i < attempts) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        log.error("Result callback failed after {} attempt(s) for bchOpId={}: {}",
                attempts, result.getBchOperationId(),
                lastError != null ? lastError.getMessage() : "unknown");
    }
}
