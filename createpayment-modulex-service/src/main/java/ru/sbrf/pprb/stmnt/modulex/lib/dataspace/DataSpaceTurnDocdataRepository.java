package ru.sbrf.pprb.stmnt.modulex.lib.dataspace;

import com.sbt.pprb.ac.graph.collection.GraphCollection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;
import ru.sbrf.pprb.stmnt.modulex.graph.get.TurnDocdataGet;
import ru.sbrf.pprb.stmnt.modulex.lib.TurnDocdataRepository;
import ru.sbrf.pprb.stmnt.modulex.packet.CreateTurnDocdataParam;
import ru.sbrf.pprb.stmnt.modulex.packet.packet.Packet;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;
import sbp.sbt.sdk.exception.SdkJsonRpcClientException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;

/**
 * Реальная DataSpace-имплементация {@link TurnDocdataRepository}.
 *
 * <p>{@code save} → {@code Packet.turnDocdata.create(CreateTurnDocdataParam.create()....)};
 * {@code findByOperationId} → {@code searchTurnDocdata} с проекцией нужных полей.</p>
 *
 * <p>{@code ccRqTm} в SDK — {@link Date}, у нас в драфте {@link LocalDateTime} —
 * конвертация через {@link Timestamp#valueOf(LocalDateTime)}.</p>
 */
@Slf4j
@Primary
@Component
public class DataSpaceTurnDocdataRepository implements TurnDocdataRepository {

    private final DataSpaceApi dsApi;

    public DataSpaceTurnDocdataRepository(DataSpaceApi dsApi) {
        this.dsApi = dsApi;
    }

    @Override
    public void save(TurnDocdataDraft d) {
        if (d == null || d.getCcOperationId() == null) return;
        try {
            Packet packet = new Packet();
            packet.turnDocdata.create(CreateTurnDocdataParam.create()
                    .setCcRegisterId(d.getCcRegisterId())
                    .setCcWalletId(d.getCcWalletId())
                    .setCcDate(d.getCcDate())
                    .setCcOperationDay(d.getCcOperationDay())
                    .setCcOperationId(d.getCcOperationId())
                    .setCcTransactionId(d.getCcTransactionId())
                    .setCcContractId(d.getCcContractId())
                    .setCcRqTm(toDate(d.getCcRqTm()))
                    .setCcRqUId(d.getCcRqUId())
                    .setCcPayStatus(d.getCcPayStatus())
                    .setCcDT(d.getCcDT())
                    .setCcTypeOper(d.getCcTypeOper())
                    .setCcSum(d.getCcSum())
                    .setCcSumNAT(d.getCcSumNAT())
                    .setCcSumPO(d.getCcSumPO())
                    .setCcSumPL(d.getCcSumPL())
                    .setCcTypeDoc(d.getCcTypeDoc())
                    .setCcNum(d.getCcNum())
                    .setCcDateDoc(d.getCcDateDoc())
                    .setCcPurpose(d.getCcPurpose())
                    .setCcPurposeCode(d.getCcPurposeCode())
                    .setCcDTName(d.getCcDTName())
                    .setCcDTINN(d.getCcDTINN())
                    .setCcDTKPP(d.getCcDTKPP())
                    .setCcDTAcc(d.getCcDTAcc())
                    .setCcDTBIC(d.getCcDTBIC())
                    .setCcDTNameBank(d.getCcDTNameBank())
                    .setCcDTBankCorrAcc(d.getCcDTBankCorrAcc())
                    .setCcDTRegisterId(d.getCcDTRegisterId())
                    .setCcKTName(d.getCcKTName())
                    .setCcKTINN(d.getCcKTINN())
                    .setCcKTKPP(d.getCcKTKPP())
                    .setCcKTAcc(d.getCcKTAcc())
                    .setCcKTBIC(d.getCcKTBIC())
                    .setCcKTNameBank(d.getCcKTNameBank())
                    .setCcKTBankCorrAcc(d.getCcKTBankCorrAcc())
                    .setCcKTRegisterId(d.getCcKTRegisterId())
                    .setCcContrName(d.getCcContrName())
                    .setCcContrINN(d.getCcContrINN())
                    .setCcContrKPP(d.getCcContrKPP())
                    .setCcContrAcc(d.getCcContrAcc())
                    .setCcContrBIC(d.getCcContrBIC())
                    .setCcContrNameBank(d.getCcContrNameBank())
                    .setCcContrBankCorrAcc(d.getCcContrBankCorrAcc())
                    .setCcContrRegisterId(d.getCcContrRegisterId())
                    .setCcRateDT(d.getCcRateDT())
                    .setCcRateKT(d.getCcRateKT())
                    .setCcValutaDT(d.getCcValutaDT())
                    .setCcValutaKT(d.getCcValutaKT())
                    .setCcValutaTrans(d.getCcValutaTrans())
                    .setCcPriority(d.getCcPriority())
                    .setCcSystemId(d.getCcSystemId())
                    .setCcReceiptDate(d.getCcReceiptDate())
                    .setCcDivisionId(d.getCcDivisionId()));
            dsApi.execute(packet);
            log.debug("turn_docdata created: ccOperationId={}, ccTransactionId={}, ccBchOperationId={}",
                    d.getCcOperationId(), d.getCcTransactionId(), d.getCcBchOperationId());
        } catch (SdkJsonRpcClientException e) {
            log.error("turn_docdata save failed for ccOperationId={}: {}",
                    d.getCcOperationId(), e.getMessage(), e);
            throw new IllegalStateException("turn_docdata save failed", e);
        }
    }

    @Override
    public Optional<TurnDocdataDraft> findByOperationId(String ccOperationId) {
        if (ccOperationId == null || ccOperationId.isBlank()) {
            return Optional.empty();
        }
        try {
            log.debug("DataSpace searchTurnDocdata ccOperationId={}", ccOperationId);
            GraphCollection<TurnDocdataGet> coll = dsApi.searchTurnDocdata(g -> g
                    .setWhere(w -> w.ccOperationIdEq(ccOperationId))
                    .withCcRegisterId()
                    .withCcWalletId()
                    .withCcDate()
                    .withCcOperationDay()
                    .withCcOperationId()
                    .withCcTransactionId()
                    .withCcContractId()
                    .withCcRqUId()
                    .withCcPayStatus()
                    .withCcDT()
                    .withCcSum()
                    .withCcSumNAT()
                    .withCcSumPO()
                    .withCcSumPL()
                    .withCcTypeDoc()
                    .withCcNum()
                    .withCcDateDoc()
                    .withCcPurpose()
                    .withCcDTName()
                    .withCcDTINN()
                    .withCcDTKPP()
                    .withCcDTAcc()
                    .withCcDTBIC()
                    .withCcDTNameBank()
                    .withCcDTBankCorrAcc()
                    .withCcDTRegisterId()
                    .withCcKTName()
                    .withCcKTINN()
                    .withCcKTKPP()
                    .withCcKTAcc()
                    .withCcKTBIC()
                    .withCcKTNameBank()
                    .withCcKTBankCorrAcc()
                    .withCcKTRegisterId()
                    .withCcDivisionId());
            return coll.stream().findFirst().map(this::map);
        } catch (SdkJsonRpcClientException e) {
            log.error("turn_docdata lookup failed for ccOperationId={}: {}",
                    ccOperationId, e.getMessage(), e);
            throw new IllegalStateException("turn_docdata lookup failed", e);
        }
    }

    private TurnDocdataDraft map(TurnDocdataGet g) {
        return TurnDocdataDraft.builder()
                .ccRegisterId(g.getCcRegisterId())
                .ccWalletId(g.getCcWalletId())
                .ccDate(g.getCcDate())
                .ccOperationDay(g.getCcOperationDay())
                .ccOperationId(g.getCcOperationId())
                .ccTransactionId(g.getCcTransactionId())
                .ccContractId(g.getCcContractId())
                .ccRqUId(g.getCcRqUId())
                .ccPayStatus(g.getCcPayStatus())
                .ccDT(g.getCcDT())
                .ccSum(g.getCcSum())
                .ccSumNAT(g.getCcSumNAT())
                .ccSumPO(g.getCcSumPO())
                .ccSumPL(g.getCcSumPL())
                .ccTypeDoc(g.getCcTypeDoc())
                .ccNum(g.getCcNum())
                .ccDateDoc(g.getCcDateDoc())
                .ccPurpose(g.getCcPurpose())
                .ccDTName(g.getCcDTName())
                .ccDTINN(g.getCcDTINN())
                .ccDTKPP(g.getCcDTKPP())
                .ccDTAcc(g.getCcDTAcc())
                .ccDTBIC(g.getCcDTBIC())
                .ccDTNameBank(g.getCcDTNameBank())
                .ccDTBankCorrAcc(g.getCcDTBankCorrAcc())
                .ccDTRegisterId(g.getCcDTRegisterId())
                .ccKTName(g.getCcKTName())
                .ccKTINN(g.getCcKTINN())
                .ccKTKPP(g.getCcKTKPP())
                .ccKTAcc(g.getCcKTAcc())
                .ccKTBIC(g.getCcKTBIC())
                .ccKTNameBank(g.getCcKTNameBank())
                .ccKTBankCorrAcc(g.getCcKTBankCorrAcc())
                .ccKTRegisterId(g.getCcKTRegisterId())
                .ccDivisionId(g.getCcDivisionId())
                .build();
    }

    private static Date toDate(LocalDateTime ldt) {
        return ldt == null ? null : Timestamp.valueOf(ldt);
    }
}
