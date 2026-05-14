package ru.sbrf.pprb.stmnt.modulex.lib.dataspace;

import com.sbt.pprb.ac.graph.collection.GraphCollection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurn;
import ru.sbrf.pprb.stmnt.modulex.graph.get.WalletTurnGet;
import ru.sbrf.pprb.stmnt.modulex.lib.WalletTurnRepository;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;
import sbp.sbt.sdk.exception.SdkJsonRpcClientException;

import java.util.Optional;

/**
 * Реальная DataSpace-имплементация {@link WalletTurnRepository}.
 * {@code @Primary} — вытесняет {@link ru.sbrf.pprb.stmnt.modulex.lib.InMemoryWalletTurnRepository}.
 */
@Slf4j
@Primary
@Component
public class DataSpaceWalletTurnRepository implements WalletTurnRepository {

    private final DataSpaceApi dsApi;

    public DataSpaceWalletTurnRepository(DataSpaceApi dsApi) {
        this.dsApi = dsApi;
    }

    @Override
    public Optional<WalletTurn> findByBchOperationId(String ccBchOperationId) {
        if (ccBchOperationId == null || ccBchOperationId.isBlank()) {
            return Optional.empty();
        }
        try {
            GraphCollection<WalletTurnGet> coll = dsApi.searchWalletTurn(g -> g
                    .ccBchOperationId().equal(ccBchOperationId)
                    .with()
                        .ccDate()
                        .ccBchOperationId()
                        .ccTxId()
                        .ccBlockNumber()
                        .ccContractId()
                        .ccOwnerDt()
                        .ccRegisterDt()
                        .ccOwnerKt()
                        .ccRegisterKt()
                        .ccSum()
                        .ccDateDoc()
                        .ccPurpose()
                        .ccOperationId()
                        .ccTransactionId()
                        .ccRqTm()
                        .ccRqUId()
                        .ccSignature()
                        .sysLastChangeDate());
            return coll.stream().findFirst().map(this::map);
        } catch (SdkJsonRpcClientException e) {
            log.error("WalletTurn lookup failed for ccBchOperationId={}: {}",
                    ccBchOperationId, e.getMessage(), e);
            throw new IllegalStateException("WalletTurn lookup failed", e);
        }
    }

    private WalletTurn map(WalletTurnGet g) {
        return WalletTurn.builder()
                .ccDate(g.getCcDate())
                .ccBchOperationId(g.getCcBchOperationId())
                .ccTxId(g.getCcTxId())
                .ccBlockNumber(g.getCcBlockNumber())
                .ccContractId(g.getCcContractId())
                .ccOwnerDt(g.getCcOwnerDt())
                .ccRegisterDt(g.getCcRegisterDt())
                .ccOwnerKt(g.getCcOwnerKt())
                .ccRegisterKt(g.getCcRegisterKt())
                .ccSum(g.getCcSum())
                .ccDateDoc(g.getCcDateDoc())
                .ccPurpose(g.getCcPurpose())
                .ccOperationId(g.getCcOperationId())
                .ccTransactionId(g.getCcTransactionId())
                .ccRqTm(g.getCcRqTm())
                .ccRqUId(g.getCcRqUId())
                .ccSignature(g.getCcSignature())
                .sysLastChangeDate(g.getSysLastChangeDate())
                .build();
    }
}
