package ru.sbrf.pprb.stmnt.modulex.lib.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionStatus;
import ru.sbrf.pprb.stmnt.modulex.config.AppConfig;
import ru.sbrf.pprb.stmnt.modulex.config.PgwProperties;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.PgwClient;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.UPDDTO;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnRepository;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnUpdate;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnView;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
 *       <li>при {@code SUCCESS} → {@code SENT} в outbox + {@code PPRB_STARTED}
 *           в status_WalletTurn (если ещё не было);</li>
 *       <li>при сбое → attempts++. Если attempts &lt; maxAttempts →
 *           {@code ccNextRetryAt} сдвигается на {@code retryDelayMs}, status
 *           остаётся PENDING. Иначе → {@code GIVEUP} + {@code PPRB_FAILED}.</li>
 *     </ul>
 *   </li>
 * </ol>
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
    private final StatusWalletTurnRepository statusRepo;

    private final Counter processed;
    private final Counter retried;
    private final Counter gaveUp;

    public PgwOutboxWorker(UpdOutboxRepository repo,
                           PgwClient pgwClient,
                           @Qualifier("sberObjectMapper") ObjectMapper objectMapper,
                           PgwProperties properties,
                           StatusWalletTurnRepository statusRepo,
                           MeterRegistry registry) {
        this.repo = repo;
        this.pgwClient = pgwClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.statusRepo = statusRepo;
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
            ApiResult result = pgwClient.transferUpd(entry.getCcRequestId(), upd);

            // PgwClient теперь не throws — нужно проверить status явно.
            String pgwStatus = result.getStatus();
            if ("QUEUED".equals(pgwStatus) || "ERROR".equals(pgwStatus)) {
                // Доставка снова не прошла — обработка retry/giveup.
                handleRetryOrGiveup(entry, now, result.getMessage());
                return;
            }

            // SUCCESS (или SKIPPED для отключённого PGW) — УРД доставлен.
            entry.setCcStatus(UpdOutboxEntry.STATUS_SENT);
            entry.setCcAttempts(entry.getCcAttempts() + 1);
            entry.setCcNextRetryAt(null);
            entry.setCcLastError(null);
            entry.setSysLastChangeDate(now);
            repo.save(entry);
            processed.increment();
            log.info("OutboxWorker delivered UPD to PGW: requestId={}, updUID={}, attempts={}",
                    entry.getCcRequestId(), entry.getCcUpdUID(), entry.getCcAttempts());

            // Обновляем status_WalletTurn: QUEUED → STARTED.
            writeStatus(entry, ExecutionStatus.PPRB_STARTED, null, null);
        } catch (Exception e) {
            handleRetryOrGiveup(entry, now, e.getMessage());
        }
    }

    private void handleRetryOrGiveup(UpdOutboxEntry entry, LocalDateTime now, String errorMsg) {
        int next = entry.getCcAttempts() + 1;
        entry.setCcAttempts(next);
        entry.setCcLastError(truncate(errorMsg, 4000));
        entry.setSysLastChangeDate(now);
        boolean giveup = next >= entry.getCcMaxAttempts();
        if (giveup) {
            entry.setCcStatus(UpdOutboxEntry.STATUS_GIVEUP);
            entry.setCcNextRetryAt(null);
            gaveUp.increment();
            log.error("OutboxWorker GAVE UP after {} attempts: requestId={}, updUID={}, lastError={}",
                    next, entry.getCcRequestId(), entry.getCcUpdUID(), errorMsg);
        } else {
            entry.setCcNextRetryAt(now.plusNanos(properties.getRetryDelayMs() * 1_000_000L));
            log.warn("OutboxWorker attempt {}/{} failed for requestId={}: {} — next retry at {}",
                    next, entry.getCcMaxAttempts(), entry.getCcRequestId(),
                    errorMsg, entry.getCcNextRetryAt());
        }
        try {
            repo.save(entry);
        } catch (Exception inner) {
            log.error("OutboxWorker failed to persist retry state for requestId={}: {}",
                    entry.getCcRequestId(), inner.getMessage());
        }
        // На GIVEUP — пишем PPRB_FAILED в status_WalletTurn.
        if (giveup) {
            writeStatus(entry, ExecutionStatus.PPRB_FAILED, "OUTBOX_GIVEUP",
                    "Outbox gave up after " + next + " attempts: " + truncate(errorMsg, 200));
        }
    }

    /**
     * Дописывает строку в status_WalletTurn. Резолвит ccWalletTurnObjectId
     * через lookup по ccOperationId (= updUID) — берём из PPRB_GET-строки,
     * созданной в синке.
     */
    private void writeStatus(UpdOutboxEntry entry, ExecutionStatus status, String code, String desc) {
        try {
            Optional<StatusWalletTurnView> ctx = statusRepo.findFirstByOperationId(entry.getCcUpdUID());
            if (ctx.isEmpty()) {
                log.warn("OutboxWorker: no status_WalletTurn row found for updUID={} — skipping {} write",
                        entry.getCcUpdUID(), status);
                return;
            }
            statusRepo.upsertStatus(StatusWalletTurnUpdate.builder()
                    .ccWalletTurnObjectId(ctx.get().getCcWalletTurnObjectId())
                    .ccOperationId(entry.getCcUpdUID())
                    .ccTransactionId(ctx.get().getCcTransactionId())
                    .ccStatus(status.name())
                    .ccStatusCode(code)
                    .ccStatusDesc(desc)
                    .sysLastChangeDate(LocalDateTime.now(AppConfig.ZONE_ID))
                    .build());
        } catch (Exception e) {
            log.error("OutboxWorker: failed to write {} for updUID={}: {}",
                    status, entry.getCcUpdUID(), e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
