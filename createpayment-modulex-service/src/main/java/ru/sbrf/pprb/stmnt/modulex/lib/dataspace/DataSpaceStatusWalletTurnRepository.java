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
 * <p>Работаем строго через натуральные ключи из запроса
 * ({@code ccWalletTurnId}, {@code ccOperationId}, {@code ccTransactionId},
 * {@code ccStatus}). Никаких {@code objectId} не используем — он сгенерён
 * DataSpace-ом и не имеет бизнес-смысла для нашего кейса.</p>
 *
 * <p>Запись статусов идемпотентна: каждый переход
 * (PPRB_GET / PPRB_STARTED / PPRB_PROCESSING / PPRB_EXECUTED / PPRB_FAILED)
 * пишется один раз благодаря уник-индексу {@code (ccWalletTurnId, ccStatus)}.
 * Повторная попытка вставки той же пары → ловим duplicate-key и молча skip-аем.</p>
 *
 * <p>В корп-SDK поле {@code ccWalletTurnObjectId} ещё НЕ сгенерировано —
 * поэтому во все вызовы идёт {@code ccWalletTurnId} (deprecated в нашей XML,
 * но реально присутствует в сгенерированных классах и в БД). В DTO имя
 * остаётся {@code ccWalletTurnObjectId} для семантической ясности; маппинг 1:1.</p>
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
            Packet packet = new Packet();
            packet.statusWalletTurn.create(CreateStatusWalletTurnParam.create()
                    .setCcWalletTurnId(u.getCcWalletTurnObjectId())
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
            if (isDuplicateKey(e)) {
                log.debug("status_WalletTurn already exists: walletTurnObjectId={}, status={} — idempotent skip",
                        u.getCcWalletTurnObjectId(), u.getCcStatus());
                return;
            }
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
                    .withCcWalletTurnId()
                    .withCcOperationId()
                    .withCcTransactionId()
                    .withCcStatus());
            return coll.stream().findFirst().map(g -> StatusWalletTurnView.builder()
                    // В DTO имя ccWalletTurnObjectId, в SDK — ccWalletTurnId. Маппим 1:1.
                    .ccWalletTurnObjectId(g.getCcWalletTurnId())
                    .ccOperationId(g.getCcOperationId())
                    .ccTransactionId(g.getCcTransactionId())
                    .ccStatus(g.getCcStatus())
                    .build());
        } catch (SdkJsonRpcClientException e) {
            log.error("status_WalletTurn lookup by ccOperationId={} FAILED: {}", ccOperationId, e.getMessage(), e);
            throw new IllegalStateException("status_WalletTurn lookup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Грубое распознавание duplicate-key ошибки от DataSpace SDK.
     * Без жёсткой привязки к конкретному классу — проверяем по подстрокам в message.
     */
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
            String cn = cur.getClass().getSimpleName().toLowerCase();
            if (cn.contains("duplicate") || cn.contains("unique")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
