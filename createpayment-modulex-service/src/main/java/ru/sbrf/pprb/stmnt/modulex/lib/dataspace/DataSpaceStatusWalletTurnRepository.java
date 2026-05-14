package ru.sbrf.pprb.stmnt.modulex.lib.dataspace;

import com.sbt.pprb.ac.graph.collection.GraphCollection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.graph.get.StatusWalletTurnGet;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnRepository;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnUpdate;
import ru.sbrf.pprb.stmnt.modulex.packet.CreateStatusWalletTurnParam;
import ru.sbrf.pprb.stmnt.modulex.packet.StatusWalletTurnRef;
import ru.sbrf.pprb.stmnt.modulex.packet.UpdateStatusWalletTurnParam;
import ru.sbrf.pprb.stmnt.modulex.packet.packet.Packet;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;
import sbp.sbt.sdk.exception.SdkJsonRpcClientException;

/**
 * Реальная DataSpace-имплементация {@link StatusWalletTurnRepository}.
 *
 * <p>Upsert по уник-ключу {@code (ccWalletTurnObjectId, ccStatus)}:</p>
 * <ol>
 *   <li>{@code dsApi.searchStatusWalletTurn(g -> g.setWhere(w -> w.ccWalletTurnObjectIdEq(...).and(w.ccStatusEq(...))).withObjectId())};</li>
 *   <li>если запись есть — {@code packet.statusWalletTurn.update(StatusWalletTurnRef.of(objectId), updateParam)};</li>
 *   <li>если нет — {@code packet.statusWalletTurn.create(createParam)};</li>
 *   <li>{@code dsApi.execute(packet)}.</li>
 * </ol>
 *
 * <p>Альтернатива (если SDK подсунет ключ-enum) — одной операцией
 * {@code packet.statusWalletTurn.updateOrCreate(param, KeyStatusWalletTurn.CC_WALLET_TURN_OBJECT_ID_AND_CC_STATUS)}.</p>
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
            log.warn("Skip upsert: ccWalletTurnObjectId or ccStatus is null");
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
                log.debug("status_WalletTurn updated: objectId={}, walletTurnObjectId={}, status={}",
                        existingId, u.getCcWalletTurnObjectId(), u.getCcStatus());
            } else {
                packet.statusWalletTurn.create(CreateStatusWalletTurnParam.create()
                        .setCcWalletTurnObjectId(u.getCcWalletTurnObjectId())
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
            log.error("status_WalletTurn upsert failed for walletTurnObjectId={}, status={}: {}",
                    u.getCcWalletTurnObjectId(), u.getCcStatus(), e.getMessage(), e);
            throw new IllegalStateException("status_WalletTurn upsert failed", e);
        }
    }

    private String findObjectId(String walletTurnObjectId, String status) throws SdkJsonRpcClientException {
        GraphCollection<StatusWalletTurnGet> coll = dsApi.searchStatusWalletTurn(g -> g
                .setWhere(w -> w.ccWalletTurnObjectIdEq(walletTurnObjectId).and(w.ccStatusEq(status)))
                .withObjectId());
        return coll.stream().findFirst().map(StatusWalletTurnGet::getObjectId).orElse(null);
    }
}
