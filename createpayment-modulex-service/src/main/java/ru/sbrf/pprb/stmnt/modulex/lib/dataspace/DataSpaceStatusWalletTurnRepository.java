package ru.sbrf.pprb.stmnt.modulex.lib.dataspace;

import com.sbt.pprb.ac.graph.collection.GraphCollection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.graph.get.StatusWalletTurnGet;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnRepository;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnUpdate;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnView;
import ru.sbrf.pprb.stmnt.modulex.packet.CreateStatusWalletTurnParam;
import ru.sbrf.pprb.stmnt.modulex.packet.packet.Packet;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;
import sbp.sbt.sdk.exception.SdkJsonRpcClientException;

import java.util.Optional;

/**
 * Реальная DataSpace-имплементация {@link StatusWalletTurnRepository}.
 *
 * <p><b>Маппинг полей</b>:</p>
 * <ul>
 *   <li>DTO {@code ccWalletTurnObjectId} (внешний blockchain id) ↔
 *       SDK {@code ccWalletTurnObjectId} (mandatory, новое имя поля).</li>
 *   <li>SDK поле {@code ccWalletTurnId} помечено deprecated и больше не
 *       пишется — заменено новым {@code ccWalletTurnObjectId}.</li>
 * </ul>
 *
 * <p><b>Идемпотентность</b>: в корп-модели у {@code status_WalletTurn} нет
 * уник-индекса по {@code (ccWalletTurnObjectId, ccStatus)} — все индексы
 * {@code unique="false"}. Чтобы не плодить дубли на ретраях, делаем
 * <b>search-then-create</b>: перед вставкой проверяем существование пары
 * {@code (walletTurnObjectId, status)}. Если есть — skip.</p>
 */
@Slf4j
@Primary
@Component
public class DataSpaceStatusWalletTurnRepository implements StatusWalletTurnRepository {

    private final DataSpaceApi dsApi;

    public DataSpaceStatusWalletTurnRepository(DataSpaceApi dsApi) {
        this.dsApi = dsApi;
    }

    @Override
    public void upsertStatus(StatusWalletTurnUpdate u) {
        if (u == null || u.getCcWalletTurnObjectId() == null || u.getCcStatus() == null) {
            log.warn("Skip status upsert: ccWalletTurnObjectId or ccStatus is null");
            return;
        }
        try {
            // Dedup per attempt (ccOperationId + ccStatus). Каждый updUID получает
            // свою независимую серию строк — это позволяет forceResend создавать
            // новые попытки без конфликтов со старыми.
            if (u.getCcOperationId() != null
                    && existsStatusForOperation(u.getCcOperationId(), u.getCcStatus())) {
                log.debug("status_WalletTurn already exists: ccOperationId={}, status={} — idempotent skip",
                        u.getCcOperationId(), u.getCcStatus());
                return;
            }
            Packet packet = new Packet();
            packet.statusWalletTurn.create(CreateStatusWalletTurnParam.create()
                    .setCcWalletTurnObjectId(u.getCcWalletTurnObjectId())
                    .setCcOperationId(u.getCcOperationId())
                    .setCcTransactionId(u.getCcTransactionId())
                    .setCcStatus(u.getCcStatus())
                    .setCcStatusCode(u.getCcStatusCode())
                    .setCcStatusDesc(u.getCcStatusDesc())
                    .setSysLastChangeDate(u.getSysLastChangeDate()));
            dsApi.execute(packet);
            log.debug("status_WalletTurn created: walletTurnObjectId={}, status={}, code={}",
                    u.getCcWalletTurnObjectId(), u.getCcStatus(), u.getCcStatusCode());
        } catch (SdkJsonRpcClientException e) {
            log.error("status_WalletTurn create FAILED via DataSpace SDK: walletTurnObjectId={}, status={}, code={}, desc={}, exception={}: {}",
                    u.getCcWalletTurnObjectId(), u.getCcStatus(),
                    u.getCcStatusCode(), u.getCcStatusDesc(),
                    e.getClass().getName(), e.getMessage(), e);
            throw new IllegalStateException("status_WalletTurn create failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("status_WalletTurn create FAILED (unexpected): walletTurnObjectId={}, status={}, exception={}: {}",
                    u.getCcWalletTurnObjectId(), u.getCcStatus(),
                    e.getClass().getName(), e.getMessage(), e);
            throw new IllegalStateException("status_WalletTurn create failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<StatusWalletTurnView> findFirstByOperationId(String ccOperationId) {
        if (ccOperationId == null || ccOperationId.isBlank()) {
            return Optional.empty();
        }
        try {
            log.debug("DataSpace searchStatusWalletTurn by ccOperationId={}", ccOperationId);
            GraphCollection<StatusWalletTurnGet> coll = dsApi.searchStatusWalletTurn(g -> g
                    .setWhere(w -> w.ccOperationIdEq(ccOperationId))
                    .withCcWalletTurnObjectId()
                    .withCcOperationId()
                    .withCcTransactionId()
                    .withCcStatus());
            return coll.stream().findFirst().map(g -> StatusWalletTurnView.builder()
                    .ccWalletTurnObjectId(g.getCcWalletTurnObjectId())
                    .ccOperationId(g.getCcOperationId())
                    .ccTransactionId(g.getCcTransactionId())
                    .ccStatus(g.getCcStatus())
                    .build());
        } catch (SdkJsonRpcClientException e) {
            log.error("status_WalletTurn lookup by ccOperationId={} FAILED: {}", ccOperationId, e.getMessage(), e);
            throw new IllegalStateException("status_WalletTurn lookup failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> findLastFinalStatus(String ccWalletTurnObjectId) {
        if (ccWalletTurnObjectId == null || ccWalletTurnObjectId.isBlank()) {
            return Optional.empty();
        }
        try {
            // EXECUTED имеет приоритет: если есть и EXECUTED и FAILED для этого
            // walletTurn — считаем что в итоге исполнено.
            if (existsByWalletTurnAndStatus(ccWalletTurnObjectId, "PPRB_EXECUTED")) {
                return Optional.of("PPRB_EXECUTED");
            }
            if (existsByWalletTurnAndStatus(ccWalletTurnObjectId, "PPRB_FAILED")) {
                return Optional.of("PPRB_FAILED");
            }
            return Optional.empty();
        } catch (SdkJsonRpcClientException e) {
            log.error("status_WalletTurn final-status lookup for ccWalletTurnObjectId={} FAILED: {}",
                    ccWalletTurnObjectId, e.getMessage(), e);
            // На ошибке lookup — лучше не блокировать обработку. Возвращаем empty,
            // даём пайплайну продолжить (новая попытка).
            return Optional.empty();
        }
    }

    /**
     * Проверка существования строки по {@code (ccOperationId, ccStatus)} — для
     * app-level дедупа внутри одной попытки. Каждый updUID = независимая серия.
     */
    private boolean existsStatusForOperation(String ccOperationId, String status)
            throws SdkJsonRpcClientException {
        GraphCollection<StatusWalletTurnGet> coll = dsApi.searchStatusWalletTurn(g -> g
                .setWhere(w -> w.ccOperationIdEq(ccOperationId).and(w.ccStatusEq(status)))
                .setLimit(1)
                .withCcStatus());
        return coll.stream().findFirst().isPresent();
    }

    /**
     * Проверка существования строки по {@code (ccWalletTurnObjectId, ccStatus)} —
     * для pre-check «уже отработан этот платёж?».
     */
    private boolean existsByWalletTurnAndStatus(String walletTurnObjectId, String status)
            throws SdkJsonRpcClientException {
        GraphCollection<StatusWalletTurnGet> coll = dsApi.searchStatusWalletTurn(g -> g
                .setWhere(w -> w.ccWalletTurnObjectIdEq(walletTurnObjectId).and(w.ccStatusEq(status)))
                .setLimit(1)
                .withCcStatus());
        return coll.stream().findFirst().isPresent();
    }
}
