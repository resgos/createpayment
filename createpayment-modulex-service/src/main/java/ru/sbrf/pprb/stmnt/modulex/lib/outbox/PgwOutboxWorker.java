package ru.sbrf.pprb.stmnt.modulex.lib.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.config.AppConfig;
import ru.sbrf.pprb.stmnt.modulex.config.PgwProperties;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.PgwClient;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.UPDDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Background-воркер гарантированной доставки УРД в PGW.
 *
 * <p>Каждые {@code outbox.poll-interval-ms} миллисекунд:</p>
 * <ol>
 *   <li>Берёт первые N {@code PENDING}-записей из {@link UpdOutboxRepository} с
 *       {@code ccNextRetryAt &le; now}.</li>
 *   <li>Для каждой:
 *     <ul>
 *       <li>десериализует UPDDTO из {@code ccPayload};</li>
 *       <li>вызывает {@link PgwClient#transferUpd} с тем же {@code requestId};</li>
 *       <li>при успехе → status=SENT, attempts инкрементится финально;</li>
 *       <li>при неудаче → attempts++. Если attempts &lt; maxAttempts → ccNextRetryAt
 *           сдвигается на {@code retryDelayMs}, status остаётся PENDING. Иначе → GIVEUP.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Метрики:</p>
 * <ul>
 *   <li>{@code outbox_processed} — успешно отправлено воркером</li>
 *   <li>{@code outbox_retry} — попыток воркера</li>
 *   <li>{@code outbox_giveup} — переходов в GIVEUP</li>
 * </ul>
 */
@Slf4j
@Component
public class PgwOutboxWorker {

    /** Сколько записей за один тик. */
    private static final int BATCH_SIZE = 50;

    private final UpdOutboxRepository repo;
    private final PgwClient pgwClient;
    private final ObjectMapper objectMapper;
    private final PgwProperties properties;

    private final Counter processed;
    private final Counter retried;
    private final Counter gaveUp;

    public PgwOutboxWorker(UpdOutboxRepository repo,
                           PgwClient pgwClient,
                           @Qualifier("sberObjectMapper") ObjectMapper objectMapper,
                           PgwProperties properties,
                           MeterRegistry registry) {
        this.repo = repo;
        this.pgwClient = pgwClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.processed = Counter.builder("outbox_processed").register(registry);
        this.retried = Counter.builder("outbox_retry").register(registry);
        this.gaveUp = Counter.builder("outbox_giveup").register(registry);
    }

    @Scheduled(fixedDelayString = "${pgw.outbox.poll-interval-ms:60000}",
            initialDelayString = "${pgw.outbox.initial-delay-ms:30000}")
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(AppConfig.ZONE_ID);
        List<UpdOutboxEntry> batch = repo.findPending(now, BATCH_SIZE);
        if (batch.isEmpty()) return;
        log.debug("OutboxWorker tick: picked {} PENDING entries", batch.size());

        for (UpdOutboxEntry entry : batch) {
            processOne(entry, now);
        }
    }

    private void processOne(UpdOutboxEntry entry, LocalDateTime now) {
        retried.increment();
        try {
            UPDDTO upd = objectMapper.readValue(entry.getCcPayload(), UPDDTO.class);
            pgwClient.transferUpd(entry.getCcRequestId(), upd);

            entry.setCcStatus(UpdOutboxEntry.STATUS_SENT);
            entry.setCcAttempts(entry.getCcAttempts() + 1);
            entry.setCcNextRetryAt(null);
            entry.setCcLastError(null);
            entry.setSysLastChangeDate(now);
            repo.save(entry);
            processed.increment();
            log.info("OutboxWorker delivered UPD to PGW: requestId={}, updUID={}, attempts={}",
                    entry.getCcRequestId(), entry.getCcUpdUID(), entry.getCcAttempts());
        } catch (Exception e) {
            int next = entry.getCcAttempts() + 1;
            entry.setCcAttempts(next);
            entry.setCcLastError(truncate(e.getMessage(), 4000));
            entry.setSysLastChangeDate(now);
            if (next >= entry.getCcMaxAttempts()) {
                entry.setCcStatus(UpdOutboxEntry.STATUS_GIVEUP);
                entry.setCcNextRetryAt(null);
                gaveUp.increment();
                log.error("OutboxWorker GAVE UP after {} attempts: requestId={}, updUID={}, lastError={}",
                        next, entry.getCcRequestId(), entry.getCcUpdUID(), e.getMessage());
            } else {
                entry.setCcNextRetryAt(now.plusNanos(properties.getRetryDelayMs() * 1_000_000L));
                log.warn("OutboxWorker attempt {}/{} failed for requestId={}: {} — next retry at {}",
                        next, entry.getCcMaxAttempts(), entry.getCcRequestId(),
                        e.getMessage(), entry.getCcNextRetryAt());
            }
            try {
                repo.save(entry);
            } catch (Exception inner) {
                log.error("OutboxWorker failed to persist retry state for requestId={}: {}",
                        entry.getCcRequestId(), inner.getMessage());
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
