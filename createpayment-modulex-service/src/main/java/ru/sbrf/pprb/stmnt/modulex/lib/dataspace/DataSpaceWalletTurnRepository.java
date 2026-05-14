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
 *
 * <p>DSL соответствует <a href="https://habr.com/ru/companies/sberbank/articles/662397/">
 * публичной документации Platform V DataSpace SDK</a>: фильтр через
 * {@code .setWhere(w -> w.ccXxxEq(value))}, проекция через {@code .withCcXxx()}.</p>
 *
 * <p>Требуется регенерация {@code modulex-model-sdk} из актуального
 * {@code modulex.xml} — getter'ы новых полей ({@code getCcBchOperationId}
 * и т.п.) появятся только после этого.</p>
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
                    .setWhere(w -> w.ccBchOperationIdEq(ccBchOperationId))
                    .withCcDate()
                    .withCcBchOperationId()
                    .withCcTxId()
                    .withCcBlockNumber()
                    .withCcContractId()
                    .withCcOwnerDt()
                    .withCcRegisterDt()
                    .withCcOwnerKt()
                    .withCcRegisterKt()
                    .withCcSum()
                    .withCcDateDoc()
                    .withCcPurpose()
                    .withCcOperationId()
                    .withCcTransactionId()
                    .withCcRqTm()
                    .withCcRqUId()
                    .withCcSignature()
                    .withSysLastChangeDate());
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
