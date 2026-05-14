package ru.sbrf.pprb.stmnt.modulex.lib.dataspace;

import com.sbt.pprb.ac.graph.collection.GraphCollection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.graph.get.StatusWalletTurnGet;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnRepository;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnUpdate;
import ru.sbrf.pprb.stmnt.modulex.packet.CreateStatusWalletTurnParam;
import ru.sbrf.pprb.stmnt.modulex.packet.UpdateStatusWalletTurnParam;
import ru.sbrf.pprb.stmnt.modulex.packet.packet.Packet;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;
import sbp.sbt.sdk.exception.SdkJsonRpcClientException;

/**
 * Реальная DataSpace-имплементация {@link StatusWalletTurnRepository}.
 *
 * <p>Уник-индекс таблицы — {@code (ccWalletTurnObjectId, ccStatus)}. Логика upsert:</p>
 * <ol>
 *   <li>search по этой паре</li>
 *   <li>если запись есть — update (новые code/desc/timestamp)</li>
 *   <li>если нет — create</li>
 * </ol>
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
        try {
            String existingId = findObjectId(u.getCcWalletTurnObjectId(), u.getCcStatus());
            Packet packet = new Packet();
            if (existingId != null) {
                packet.statusWalletTurn.update(UpdateStatusWalletTurnParam.create()
                        .setObjectId(existingId)
                        .setCcOperationId(u.getCcOperationId())
                        .setCcTransactionId(u.getCcTransactionId())
                        .setCcStatusCode(u.getCcStatusCode())
                        .setCcStatusDesc(u.getCcStatusDesc())
                        .setSysLastChangeDate(u.getSysLastChangeDate()));
                log.debug("status_WalletTurn updated: walletTurnObjectId={}, status={}, code={}",
                        u.getCcWalletTurnObjectId(), u.getCcStatus(), u.getCcStatusCode());
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

    /** Найти objectId записи по уник. паре (ccWalletTurnObjectId, ccStatus). */
    private String findObjectId(String walletTurnObjectId, String status) throws SdkJsonRpcClientException {
        if (walletTurnObjectId == null || status == null) return null;
        GraphCollection<StatusWalletTurnGet> coll = dsApi.searchStatusWalletTurn(g -> g
                .ccWalletTurnObjectId().equal(walletTurnObjectId)
                .ccStatus().equal(status)
                .with().objectId());
        return coll.stream().findFirst().map(StatusWalletTurnGet::getObjectId).orElse(null);
    }
}
