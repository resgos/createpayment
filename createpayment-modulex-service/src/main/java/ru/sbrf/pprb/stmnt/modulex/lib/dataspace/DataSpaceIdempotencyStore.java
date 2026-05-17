package ru.sbrf.pprb.stmnt.modulex.lib.dataspace;

import com.sbt.pprb.ac.graph.collection.GraphCollection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.config.AppConfig;
import ru.sbrf.pprb.stmnt.modulex.graph.get.ResponseTicketIdempotencyGet;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.lib.IdempotencyStore;
import ru.sbrf.pprb.stmnt.modulex.packet.CreateResponseTicketIdempotencyParam;
import ru.sbrf.pprb.stmnt.modulex.packet.packet.Packet;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;
import sbp.sbt.sdk.exception.SdkJsonRpcClientException;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * DataSpace-имплементация {@link IdempotencyStore}.
 *
 * <p>Уник-индекс по {@code ccIdempotencyKey} — повторная попытка вставить
 * тот же ключ ловится в {@code SdkJsonRpcClientException} и трактуется как
 * "уже сохранён" (idempotent), не ошибка.</p>
 *
 * <p>В {@code find} проекцируем минимум полей для восстановления ApiResult:
 * {@code ccApiResultStatus}, {@code ccApiResultMessage}, {@code ccCorrelationId}.</p>
 */
@Slf4j
@Primary
@Component
public class DataSpaceIdempotencyStore implements IdempotencyStore {

    private final DataSpaceApi dsApi;

    public DataSpaceIdempotencyStore(DataSpaceApi dsApi) {
        this.dsApi = dsApi;
    }

    @Override
    public Optional<ApiResult> find(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        try {
            GraphCollection<ResponseTicketIdempotencyGet> coll = dsApi.searchResponseTicketIdempotency(g -> g
                    .setWhere(w -> w.ccIdempotencyKeyEq(idempotencyKey))
                    .withCcIdempotencyKey()
                    .withCcCorrelationId()
                    .withCcUpdUID()
                    .withCcApiResultStatus()
                    .withCcApiResultMessage());
            return coll.stream().findFirst().map(g -> ApiResult.builder()
                    .correlationId(g.getCcCorrelationId())
                    .idempotencyKey(g.getCcIdempotencyKey())
                    .status(g.getCcApiResultStatus())
                    .message(g.getCcApiResultMessage())
                    .build());
        } catch (SdkJsonRpcClientException e) {
            log.error("responseTicket_idempotency lookup FAILED for idempotencyKey={}: {}",
                    idempotencyKey, e.getMessage(), e);
            // Не бросаем — если кеш недоступен, идём по обычному пайплайну (deduplication best-effort).
            return Optional.empty();
        }
    }

    @Override
    public void save(String idempotencyKey, String correlationId, String updUID, ApiResult result) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || result == null) return;
        if (!"SUCCESS".equals(result.getStatus())) {
            log.debug("Skip persisting non-success ApiResult: idempotencyKey={}, status={}",
                    idempotencyKey, result.getStatus());
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now(AppConfig.ZONE_ID);
            Packet packet = new Packet();
            packet.responseTicketIdempotency.create(CreateResponseTicketIdempotencyParam.create()
                    .setCcIdempotencyKey(idempotencyKey)
                    .setCcCorrelationId(correlationId)
                    .setCcUpdUID(updUID)
                    .setCcApiResultStatus(result.getStatus())
                    .setCcApiResultMessage(result.getMessage())
                    .setCcProcessedAt(now)
                    .setSysLastChangeDate(now));
            dsApi.execute(packet);
            log.debug("responseTicket_idempotency saved: idempotencyKey={}, updUID={}", idempotencyKey, updUID);
        } catch (SdkJsonRpcClientException e) {
            if (isDuplicateKey(e)) {
                log.debug("responseTicket_idempotency already exists for idempotencyKey={} — idempotent skip",
                        idempotencyKey);
                return;
            }
            log.error("responseTicket_idempotency save FAILED for idempotencyKey={}: {}",
                    idempotencyKey, e.getMessage(), e);
            // Не бросаем — фейл сохранения кеша не должен ломать основной flow обработки квитанции.
        }
    }

    private static boolean isDuplicateKey(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            String m = cur.getMessage();
            if (m != null) {
                String lower = m.toLowerCase();
                if (lower.contains("duplicate")
                        || lower.contains("unique")
                        || lower.contains("уник")
                        || lower.contains("дубл")
                        || lower.contains("already exist")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}
