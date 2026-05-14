package ru.sbrf.pprb.stmnt.modulex.integration.callback;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionResult;
import ru.sbrf.pprb.stmnt.modulex.config.ResultCallbackProperties;

import java.time.Duration;

@Slf4j
@Component
public class ResultCallbackClientImpl implements ResultCallbackClient {

    private final ResultCallbackProperties properties;
    private final RestTemplate restTemplate;

    /**
     * Используется self-contained {@link RestTemplate}: собирается inline из
     * {@link RestTemplateBuilder} (доступен из {@code spring-boot-starter-web}),
     * никаких именованных бинов снаружи не требуется — устраняет ошибку wiring
     * при stale-сборке или альтернативной конфигурации.
     */
    public ResultCallbackClientImpl(ResultCallbackProperties properties,
                                    RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = buildRestTemplate(builder, properties);
    }

    private static RestTemplate buildRestTemplate(RestTemplateBuilder builder,
                                                  ResultCallbackProperties props) {
        ObjectMapper om = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        RestTemplate rt = builder
                .setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .build();

        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter(om);
        boolean replaced = false;
        for (int i = 0; i < rt.getMessageConverters().size(); i++) {
            HttpMessageConverter<?> conv = rt.getMessageConverters().get(i);
            if (conv instanceof MappingJackson2HttpMessageConverter) {
                rt.getMessageConverters().set(i, jsonConverter);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            rt.getMessageConverters().add(0, jsonConverter);
        }
        return rt;
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
