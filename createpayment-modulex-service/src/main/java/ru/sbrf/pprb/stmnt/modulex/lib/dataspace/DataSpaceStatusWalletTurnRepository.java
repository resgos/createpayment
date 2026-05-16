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
import ru.sbrf.pprb.stmnt.modulex.packet.ExistStatusWalletTurnParam;
import ru.sbrf.pprb.stmnt.modulex.packet.KeyStatusWalletTurn;
import ru.sbrf.pprb.stmnt.modulex.packet.packet.Packet;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;
import sbp.sbt.sdk.exception.SdkJsonRpcClientException;

import java.util.Optional;

/**
 * Реальная DataSpace-имплементация {@link StatusWalletTurnRepository}.
 *
 * <p>Upsert через нативный SDK-примитив {@code packet.statusWalletTurn.updateOrCreate(...)}
 * по уник-индексу {@code (ccWalletTurnObjectId, ccStatus)} — атомарно, без race
 * между search и create.</p>
 *
 * <p>Важно: {@code ccWalletTurnId} в локальной модели помечен {@code isDeprecated},
 * но в корп-рантайме DataSpace всё ещё mandatory → дублируем его значением
 * {@code ccWalletTurnObjectId} и при create, и при update.</p>
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
            Packet packet = Packet.createPacket();
            packet.statusWalletTurn.updateOrCreate(
                    CreateStatusWalletTurnParam.create()
                            .setCcWalletTurnObjectId(u.getCcWalletTurnObjectId())
                            // Дубль: в корп-схеме ccWalletTurnId mandatory (deprecated в нашей XML).
                            .setCcWalletTurnId(u.getCcWalletTurnObjectId())
                            .setCcOperationId(u.getCcOperationId())
                            .setCcTransactionId(u.getCcTransactionId())
                            .setCcStatus(u.getCcStatus())
                            .setCcStatusCode(u.getCcStatusCode())
                            .setCcStatusDesc(u.getCcStatusDesc())
                            .setSysLastChangeDate(u.getSysLastChangeDate()),
                    ExistStatusWalletTurnParam.create()
                            .setByKey(KeyStatusWalletTurn.CCWALLETTURNOBJECTID_CCSTATUS)
                            .setUpdate(upd -> {
                                upd.setCcOperationId(u.getCcOperationId());
                                upd.setCcTransactionId(u.getCcTransactionId());
                                upd.setCcStatusCode(u.getCcStatusCode());
                                upd.setCcStatusDesc(u.getCcStatusDesc());
                                upd.setSysLastChangeDate(u.getSysLastChangeDate());
                            })
            );
            dsApi.execute(packet);
            log.debug("status_WalletTurn upsert ok: walletTurnObjectId={}, status={}, code={}",
                    u.getCcWalletTurnObjectId(), u.getCcStatus(), u.getCcStatusCode());
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
}
