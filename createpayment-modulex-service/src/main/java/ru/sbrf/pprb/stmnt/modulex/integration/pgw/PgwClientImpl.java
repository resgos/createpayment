package ru.sbrf.pprb.stmnt.modulex.integration.pgw;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import ru.sbrf.pprb.stmnt.modulex.config.AppConfig;
import ru.sbrf.pprb.stmnt.modulex.config.PgwProperties;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.UPDDTO;
import ru.sbrf.pprb.stmnt.modulex.lib.outbox.UpdOutboxEntry;
import ru.sbrf.pprb.stmnt.modulex.lib.outbox.UpdOutboxRepository;

import java.time.LocalDateTime;

/**
 * HTTP-клиент PGW.
 *
 * <p>В {@link #transferUpd(String, UPDDTO)} делается ОДНА синхронная попытка
 * отправки. При успехе — возвращаем ApiResult; при сбое (RestClientException
 * или non-2xx) — кладём УРД в {@link UpdOutboxRepository} со статусом
 * {@code PENDING} и бросаем исключение. Background-воркер
 * ({@code PgwOutboxWorker}) дальше переотправляет с тем же {@code requestId}
 * до {@code maxAttempts} раз. Это гарантирует доставку без блокировки
 * клиентского Tomcat-треда.</p>
 *
 * <p>Метрики:</p>
 * <ul>
 *   <li>{@code pgw_transfer_upd_success}</li>
 *   <li>{@code pgw_transfer_upd_failure}</li>
 *   <li>{@code pgw_transfer_upd_duration} — timer</li>
 *   <li>{@code pgw_transfer_upd_outbox} — попадание в outbox после первого провала</li>
 * </ul>
 */
@Slf4j
@Component
public class PgwClientImpl implements PgwClient {

    private final PgwProperties properties;
    private final RestTemplate restTemplate;
    private final UpdOutboxRepository outbox;
    private final ObjectMapper objectMapper;

    private final Counter success;
    private final Counter failure;
    private final Counter outboxed;
    private final Timer duration;

    public PgwClientImpl(PgwProperties properties,
                         @Qualifier("pgwRestTemplate") RestTemplate restTemplate,
                         UpdOutboxRepository outbox,
                         @Qualifier("sberObjectMapper") ObjectMapper objectMapper,
                         MeterRegistry registry) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.success = Counter.builder("pgw_transfer_upd_success").register(registry);
        this.failure = Counter.builder("pgw_transfer_upd_failure").register(registry);
        this.outboxed = Counter.builder("pgw_transfer_upd_outbox").register(registry);
        this.duration = Timer.builder("pgw_transfer_upd_duration").register(registry);
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

        long started = System.nanoTime();
        try {
            log.debug("PGW transferUpd sync requestId={} updUID={}", requestId, updDTO.getUpdUID());
            ResponseEntity<ApiResult> resp = restTemplate.postForEntity(url, entity, ApiResult.class);
            ApiResult body = resp.getBody();
            if (body == null) {
                body = ApiResult.builder().correlationId(requestId).build();
            }
            success.increment();
            duration.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            // Нормализуем status — PGW иногда возвращает null. Считаем доставленным.
            if (body.getStatus() == null || body.getStatus().isBlank()) {
                body = body.toBuilder().status("SUCCESS").build();
            }
            log.debug("PGW transferUpd ok: correlationId={}, idempotencyKey={}, status={}",
                    body.getCorrelationId(), body.getIdempotencyKey(), body.getStatus());
            return body;
        } catch (RestClientException e) {
            failure.increment();
            duration.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            log.warn("PGW transferUpd sync FAILED requestId={}: {} — scheduling outbox retry",
                    requestId, e.getMessage());
            boolean enqueued = scheduleOutboxRetry(requestId, updDTO, e.getMessage());
            if (enqueued) {
                // УРД в очереди — это НЕ терминальная ошибка, гарант-доставка работает.
                return ApiResult.builder()
                        .correlationId(requestId)
                        .status("QUEUED")
                        .message("Queued for background retry: " + e.getMessage())
                        .build();
            }
            // Outbox тоже не дал положить — терминальная ошибка.
            return ApiResult.builder()
                    .correlationId(requestId)
                    .status("ERROR")
                    .message("Sync failed and outbox enqueue failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Кладём УРД в outbox. Возвращает {@code true} если запись успешно создана
     * (или уже была — дубль), {@code false} если outbox сам упал (тогда это
     * терминальная ошибка для УРД, retry не будет).
     */
    private boolean scheduleOutboxRetry(String requestId, UPDDTO updDTO, String errorMsg) {
        try {
            // Если запись уже есть (дубль/повторная отправка тем же клиентом) — считаем enqueued.
            if (outbox.findByRequestId(requestId).isPresent()) {
                log.debug("Outbox entry already exists for requestId={} — skip enqueue", requestId);
                return true;
            }
            String payload = objectMapper.writeValueAsString(updDTO);
            LocalDateTime now = LocalDateTime.now(AppConfig.ZONE_ID);
            UpdOutboxEntry entry = UpdOutboxEntry.builder()
                    .ccRequestId(requestId)
                    .ccUpdUID(updDTO.getUpdUID())
                    .ccPayload(payload)
                    .ccStatus(UpdOutboxEntry.STATUS_PENDING)
                    .ccAttempts(1)  // первый sync-attempt уже был и провалился
                    .ccMaxAttempts(Math.max(2, properties.getMaxAttempts()))
                    .ccNextRetryAt(now.plusNanos(properties.getRetryDelayMs() * 1_000_000L))
                    .ccLastError(truncate(errorMsg, 4000))
                    .ccCreatedAt(now)
                    .sysLastChangeDate(now)
                    .build();
            outbox.save(entry);
            outboxed.increment();
            log.info("Enqueued UPD in outbox: requestId={}, updUID={}, nextRetryAt={}",
                    requestId, updDTO.getUpdUID(), entry.getCcNextRetryAt());
            return true;
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize UPDDTO for outbox: requestId={}, error={}",
                    requestId, ex.getMessage(), ex);
            return false;
        } catch (Exception ex) {
            log.error("Failed to enqueue outbox entry for requestId={}: {}",
                    requestId, ex.getMessage(), ex);
            return false;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
