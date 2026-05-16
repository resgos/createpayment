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
import ru.sbrf.pprb.stmnt.modulex.packet.StatusWalletTurnRef;
import ru.sbrf.pprb.stmnt.modulex.packet.UpdateStatusWalletTurnParam;
import ru.sbrf.pprb.stmnt.modulex.packet.packet.Packet;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;
import sbp.sbt.sdk.exception.SdkJsonRpcClientException;

import java.util.Optional;

/**
 * Реальная DataSpace-имплементация {@link StatusWalletTurnRepository}.
 *
 * <p>В корп-SDK поле {@code ccWalletTurnObjectId} ещё НЕ сгенерировано
 * (наше переименование в XML не дошло до regen). Поэтому здесь во все
 * SDK-вызовы идёт {@code ccWalletTurnId} — это deprecated в нашей XML
 * имя, которое реально присутствует в сгенерированных классах и в
 * деплое корп-БД. В нашем DTO поле остаётся {@code ccWalletTurnObjectId}
 * для семантической ясности; маппинг 1:1.</p>
 *
 * <p>{@code updateOrCreate} с {@code KeyStatusWalletTurn} тоже недоступен
 * без regen — используем legacy search→if/else create/update.</p>
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
            String existingId = findObjectId(u.getCcWalletTurnObjectId(), u.getCcStatus());
            Packet packet = new Packet();
            if (existingId != null) {
                packet.statusWalletTurn.update(StatusWalletTurnRef.of(existingId),
                        UpdateStatusWalletTurnParam.create()
                                .setCcOperationId(u.getCcOperationId())
                                .setCcTransactionId(u.getCcTransactionId())
                                .setCcStatusCode(u.getCcStatusCode())
                                .setCcStatusDesc(u.getCcStatusDesc())
                                .setSysLastChangeDate(u.getSysLastChangeDate()));
                log.debug("status_WalletTurn updated: objectId={}, walletTurnObjectId={}, status={}, code={}",
                        existingId, u.getCcWalletTurnObjectId(), u.getCcStatus(), u.getCcStatusCode());
            } else {
                packet.statusWalletTurn.create(CreateStatusWalletTurnParam.create()
                        // ccWalletTurnId — единственное поле в корп-SDK, кладём blockchain id сюда.
                        .setCcWalletTurnId(u.getCcWalletTurnObjectId())
                        .setCcOperationId(u.getCcOperationId())
                        .setCcTransactionId(u.getCcTransactionId())
                        .setCcStatus(u.getCcStatus())
                        .setCcStatusCode(u.getCcStatusCode())
                        .setCcStatusDesc(u.getCcStatusDesc())
                        .setSysLastChangeDate(u.getSysLastChangeDate()));
                log.debug("status_WalletTurn created: walletTurnObjectId={}, status={}",
                        u.getCcWalletTurnObjectId(), u.getCcStatus());
            }
            dsApi.execute(packet);
        } catch (SdkJsonRpcClientException e) {
            log.error("status_WalletTurn upsert FAILED via DataSpace SDK: walletTurnObjectId={}, status={}, code={}, desc={}, exception={}: {}",
                    u.getCcWalletTurnObjectId(), u.getCcStatus(),
                    u.getCcStatusCode(), u.getCcStatusDesc(),
                    e.getClass().getName(), e.getMessage(), e);
            throw new IllegalStateException("status_WalletTurn upsert failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("status_WalletTurn upsert FAILED (unexpected): walletTurnObjectId={}, status={}, exception={}: {}",
                    u.getCcWalletTurnObjectId(), u.getCcStatus(),
                    e.getClass().getName(), e.getMessage(), e);
            throw new IllegalStateException("status_WalletTurn upsert failed: "
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

    /** Найти objectId записи по уник. паре {@code (ccWalletTurnId, ccStatus)}. */
    private String findObjectId(String walletTurnObjectId, String status) throws SdkJsonRpcClientException {
        GraphCollection<StatusWalletTurnGet> coll = dsApi.searchStatusWalletTurn(g -> g
                .setWhere(w -> w.ccWalletTurnIdEq(walletTurnObjectId).and(w.ccStatusEq(status)))
                .withObjectId());
        return coll.stream().findFirst().map(StatusWalletTurnGet::getObjectId).orElse(null);
    }
}
