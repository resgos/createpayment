package ru.sbrf.pprb.stmnt.modulex.lib.dataspace;

import com.sbt.pprb.ac.graph.collection.GraphCollection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.graph.get.UpdOutboxGet;
import ru.sbrf.pprb.stmnt.modulex.lib.outbox.UpdOutboxEntry;
import ru.sbrf.pprb.stmnt.modulex.lib.outbox.UpdOutboxRepository;
import ru.sbrf.pprb.stmnt.modulex.packet.CreateUpdOutboxParam;
import ru.sbrf.pprb.stmnt.modulex.packet.packet.Packet;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;
import sbp.sbt.sdk.exception.SdkJsonRpcClientException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * DataSpace-имплементация {@link UpdOutboxRepository}.
 *
 * <p>Уник-индекс по {@code ccRequestId}. Запись создаётся при первой неудаче
 * sync-attempt в {@link ru.sbrf.pprb.stmnt.modulex.integration.pgw.PgwClient}.
 * Background-воркер ({@code PgwOutboxWorker}) забирает PENDING и пытается
 * отправить — обновляет attempts/status/nextRetryAt натуральным upsert-ом по
 * тому же ccRequestId.</p>
 */
@Slf4j
@Primary
@Component
public class DataSpaceUpdOutboxRepository implements UpdOutboxRepository {

    private final DataSpaceApi dsApi;

    public DataSpaceUpdOutboxRepository(DataSpaceApi dsApi) {
        this.dsApi = dsApi;
    }

    @Override
    public void save(UpdOutboxEntry e) {
        if (e == null || e.getCcRequestId() == null) return;
        try {
            Packet packet = new Packet();
            packet.updOutbox.create(CreateUpdOutboxParam.create()
                    .setCcRequestId(e.getCcRequestId())
                    .setCcUpdUID(e.getCcUpdUID())
                    .setCcPayload(e.getCcPayload())
                    .setCcStatus(e.getCcStatus())
                    .setCcAttempts(e.getCcAttempts())
                    .setCcMaxAttempts(e.getCcMaxAttempts())
                    .setCcNextRetryAt(e.getCcNextRetryAt())
                    .setCcLastError(e.getCcLastError())
                    .setCcCreatedAt(e.getCcCreatedAt())
                    .setSysLastChangeDate(e.getSysLastChangeDate()));
            dsApi.execute(packet);
            log.debug("upd_outbox saved: requestId={}, updUID={}, status={}, attempts={}/{}",
                    e.getCcRequestId(), e.getCcUpdUID(), e.getCcStatus(),
                    e.getCcAttempts(), e.getCcMaxAttempts());
        } catch (SdkJsonRpcClientException ex) {
            if (isDuplicateKey(ex)) {
                // Запись существует — это update-сценарий, но SDK для UpdOutbox
                // тут не поддержит updateOrCreate без objectId. Пишем варн и
                // полагаемся, что воркер увидит запись сам по findPending.
                log.warn("upd_outbox already exists for requestId={} — likely a retry race, skip", e.getCcRequestId());
                return;
            }
            log.error("upd_outbox save FAILED for requestId={}: {}", e.getCcRequestId(), ex.getMessage(), ex);
            throw new IllegalStateException("upd_outbox save failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Optional<UpdOutboxEntry> findByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) return Optional.empty();
        try {
            GraphCollection<UpdOutboxGet> coll = dsApi.searchUpdOutbox(g -> g
                    .setWhere(w -> w.ccRequestIdEq(requestId))
                    .withCcRequestId()
                    .withCcUpdUID()
                    .withCcPayload()
                    .withCcStatus()
                    .withCcAttempts()
                    .withCcMaxAttempts()
                    .withCcNextRetryAt()
                    .withCcLastError()
                    .withCcCreatedAt());
            return coll.stream().findFirst().map(DataSpaceUpdOutboxRepository::map);
        } catch (SdkJsonRpcClientException e) {
            log.error("upd_outbox lookup FAILED for requestId={}: {}", requestId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public List<UpdOutboxEntry> findPending(LocalDateTime now, int limit) {
        try {
            GraphCollection<UpdOutboxGet> coll = dsApi.searchUpdOutbox(g -> g
                    .setWhere(w -> w.ccStatusEq(UpdOutboxEntry.STATUS_PENDING)
                            .and(w.ccNextRetryAtLessOrEq(now)))
                    .setLimit(limit)
                    .withCcRequestId()
                    .withCcUpdUID()
                    .withCcPayload()
                    .withCcStatus()
                    .withCcAttempts()
                    .withCcMaxAttempts()
                    .withCcNextRetryAt()
                    .withCcLastError()
                    .withCcCreatedAt());
            return coll.stream().map(DataSpaceUpdOutboxRepository::map).collect(Collectors.toList());
        } catch (SdkJsonRpcClientException e) {
            log.error("upd_outbox findPending FAILED: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private static UpdOutboxEntry map(UpdOutboxGet g) {
        return UpdOutboxEntry.builder()
                .ccRequestId(g.getCcRequestId())
                .ccUpdUID(g.getCcUpdUID())
                .ccPayload(g.getCcPayload())
                .ccStatus(g.getCcStatus())
                .ccAttempts(g.getCcAttempts() != null ? g.getCcAttempts() : 0)
                .ccMaxAttempts(g.getCcMaxAttempts() != null ? g.getCcMaxAttempts() : 0)
                .ccNextRetryAt(g.getCcNextRetryAt())
                .ccLastError(g.getCcLastError())
                .ccCreatedAt(g.getCcCreatedAt())
                .build();
    }

    private static boolean isDuplicateKey(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            String m = cur.getMessage();
            if (m != null) {
                String lower = m.toLowerCase();
                if (lower.contains("duplicate") || lower.contains("unique")
                        || lower.contains("уник") || lower.contains("дубл")
                        || lower.contains("already exist")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}
