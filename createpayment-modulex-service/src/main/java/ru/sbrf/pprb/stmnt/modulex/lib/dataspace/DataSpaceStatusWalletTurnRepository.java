package ru.sbrf.pprb.stmnt.modulex.lib.dataspace;

import com.sbt.pprb.ac.graph.collection.GraphCollection;
import lombok.extern.slf4j.Slf4j;
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
 * <p>Текущий {@code modulex-model-sdk} ещё генерирует поле как {@code ccWalletTurnId}
 * (старое имя). В новом {@code modulex.xml} оно переименовано в
 * {@code ccWalletTurnObjectId} — после регенерации SDK поменяй
 * {@code setCcWalletTurnId} → {@code setCcWalletTurnObjectId} и
 * {@code ccWalletTurnIdEq} → {@code ccWalletTurnObjectIdEq}.</p>
 *
 * <p>Upsert по уник-ключу:</p>
 * <ol>
 *   <li>search по паре (id + status);</li>
 *   <li>если запись есть — {@code packet.statusWalletTurn.update(Ref.of(objectId), updateParam)};</li>
 *   <li>если нет — {@code packet.statusWalletTurn.create(createParam)};</li>
 *   <li>{@code dsApi.execute(packet)}.</li>
 * </ol>
 *
 * <p>{@code objectId} проектируется в Get автоматически — явный
 * {@code .withObjectId()} в SDK отсутствует.</p>
 */
@Slf4j
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
                        // SDK ещё использует старое имя ccWalletTurnId — value кладём из
                        // нашего ccWalletTurnObjectId (это один и тот же external bch payment id).
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
            log.error("status_WalletTurn upsert failed for walletTurnObjectId={}, status={}: {}",
                    u.getCcWalletTurnObjectId(), u.getCcStatus(), e.getMessage(), e);
            throw new IllegalStateException("status_WalletTurn upsert failed", e);
        }
    }

    private String findObjectId(String walletTurnObjectId, String status) throws SdkJsonRpcClientException {
        // SDK имя поля — ccWalletTurnId; objectId проектируется автоматически.
        GraphCollection<StatusWalletTurnGet> coll = dsApi.searchStatusWalletTurn(g -> g
                .setWhere(w -> w.ccWalletTurnIdEq(walletTurnObjectId).and(w.ccStatusEq(status))));
        return coll.stream().findFirst().map(StatusWalletTurnGet::getObjectId).orElse(null);
    }
}
