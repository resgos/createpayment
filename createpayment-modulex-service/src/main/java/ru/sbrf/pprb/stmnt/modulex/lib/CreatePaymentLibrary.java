package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePayment;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePaymentResponse;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft.TurnDocdataDraftBuilder;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnInput;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnResult;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnResult.Status;
import ru.sbrf.pprb.stmnt.modulex.config.AppConfig;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.SberIntegrationClient;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult.Participant;
import ru.sbrf.pprb.stmnt.modulex.validator.SimpleValidator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class CreatePaymentLibrary {

    private final SimpleValidator simpleValidator;
    private final SberIntegrationClient sberClient;
    private final TurnDocdataIdGenerator idGenerator;

    public CreatePaymentLibrary(SimpleValidator simpleValidator,
                                SberIntegrationClient sberClient,
                                TurnDocdataIdGenerator idGenerator) {
        this.simpleValidator = simpleValidator;
        this.sberClient = sberClient;
        this.idGenerator = idGenerator;
    }

    public CreatePaymentResponse execute(CreatePayment request) {
        simpleValidator.requireNonNull(request, "request");
        simpleValidator.requireNonNull(request.getWalletTurns(), "walletTurns");

        String rqUID = request.getRqUID();
        LocalDateTime rqTm = request.getRqTm() != null ? request.getRqTm() : LocalDateTime.now(AppConfig.ZONE_ID);

        Map<String, Participant> bicDirectory = loadBicDirectory(rqUID);

        List<WalletTurnResult> results = new ArrayList<>(request.getWalletTurns().size());
        for (WalletTurnInput wt : request.getWalletTurns()) {
            results.add(buildDraft(wt, rqUID, rqTm, bicDirectory));
        }

        int failed = (int) results.stream().filter(r -> r.getStatus() == Status.FAILED).count();
        return CreatePaymentResponse.builder()
                .rqUID(rqUID)
                .rqTm(LocalDateTime.now(AppConfig.ZONE_ID))
                .statusCode(failed == results.size() && !results.isEmpty() ? 1 : 0)
                .statusDesc("Created drafts: " + (results.size() - failed) + ", failed: " + failed)
                .results(results)
                .build();
    }

    private Map<String, Participant> loadBicDirectory(String rqUID) {
        try {
            GetSberIntegrationResult res = sberClient.getBicDirectory(rqUID);
            if (res == null || res.getNsi() == null || res.getNsi().getParticipant() == null) {
                return Map.of();
            }
            Map<String, Participant> map = new HashMap<>();
            for (Participant p : res.getNsi().getParticipant()) {
                if (p.getBic() != null) {
                    map.put(p.getBic(), p);
                }
            }
            log.debug("Loaded BIC directory: {} participants", map.size());
            return map;
        } catch (Exception e) {
            log.warn("Failed to load BIC directory: {}", e.getMessage());
            return Map.of();
        }
    }

    private WalletTurnResult buildDraft(WalletTurnInput wt, String rqUID, LocalDateTime rqTm,
                                        Map<String, Participant> bicDirectory) {
        WalletTurnResult.WalletTurnResultBuilder rb = WalletTurnResult.builder()
                .ccBchOperationId(wt != null ? wt.getCcBchOperationId() : null);

        try {
            simpleValidator.requireNonNull(wt, "walletTurn");
            simpleValidator.requireNonBlank(wt.getCcRegisterDt(), "ccRegisterDt");
            simpleValidator.requireNonBlank(wt.getCcRegisterKt(), "ccRegisterKt");
            simpleValidator.requireNonNull(wt.getCcDate(), "ccDate");
            simpleValidator.requireNonNull(wt.getCcSum(), "ccSum");

            String txId = idGenerator.transactionId();
            rb.ccTransactionId(txId);

            TurnDocdataDraftBuilder b = TurnDocdataDraft.builder()
                    .ccRegisterId(wt.getCcRegisterDt())
                    .ccWalletId(wt.getCcOwnerDt())
                    .ccOperationId(idGenerator.operationId())
                    .ccBchOperationId(wt.getCcBchOperationId())
                    .ccTransactionId(txId)
                    .ccContractId(wt.getCcContractId())
                    .ccRqTm(rqTm)
                    .ccRqUId(idGenerator.rqUId())
                    .ccPayStatus(TurnDocdataDefaults.PAY_STATUS_DRAFT)
                    .ccDT(TurnDocdataDefaults.DT_DEBIT)
                    .ccTypeOper(TurnDocdataDefaults.TYPE_OPER_CURRENT_DAY)
                    .ccDate(wt.getCcDate())
                    .ccOperationDay(wt.getCcDate().toLocalDate())
                    .ccDateDoc(wt.getCcDateDoc())
                    .ccSum(wt.getCcSum())
                    .ccSumNAT(wt.getCcSum())
                    .ccSumPO(wt.getCcSum())
                    .ccSumPL(wt.getCcSum())
                    .ccTypeDoc(TurnDocdataDefaults.TYPE_DOC_PP)
                    .ccNum(idGenerator.docNum())
                    .ccPurpose(wt.getCcPurpose())
                    .ccDTRegisterId(wt.getCcRegisterDt())
                    .ccKTRegisterId(wt.getCcRegisterKt())
                    .ccRateDT(TurnDocdataDefaults.RATE_DEFAULT)
                    .ccRateKT(TurnDocdataDefaults.RATE_DEFAULT)
                    .ccValutaDT(TurnDocdataDefaults.CURRENCY_RUB)
                    .ccValutaKT(TurnDocdataDefaults.CURRENCY_RUB)
                    .ccValutaTrans(TurnDocdataDefaults.CURRENCY_RUB)
                    .ccPriority(TurnDocdataDefaults.PRIORITY_DEFAULT)
                    .ccSystemId(TurnDocdataDefaults.SYSTEM_ID)
                    .sysLastChangeDate(LocalDateTime.now(AppConfig.ZONE_ID));

            enrichDt(b, wt.getCcRegisterDt(), rqUID);
            enrichKt(b, wt.getCcRegisterKt(), rqUID);

            TurnDocdataDraft draft = b.build();
            applyBicDirectory(draft, bicDirectory);
            applyContraFromKt(draft);

            return rb.status(Status.DRAFT_CREATED).statusDesc("OK").turnDocdata(draft).build();
        } catch (Exception e) {
            log.warn("Build draft failed for ccBchOperationId={}: {}",
                    wt != null ? wt.getCcBchOperationId() : null, e.getMessage());
            return rb.status(Status.FAILED).statusDesc(e.getMessage()).build();
        }
    }

    /** registerId → FSKK → EPK. Названия банков подставит applyBicDirectory. */
    private void enrichDt(TurnDocdataDraftBuilder b, String registerDt, String rqUID) {
        GetSberIntegrationResult byRegister = sberClient.getByRegisterId(registerDt, rqUID);
        GetSberIntegrationResult.Fskk fskk = byRegister != null ? byRegister.getFskk() : null;
        if (fskk == null) return;

        b.ccDTAcc(fskk.getAccNum())
                .ccDTBIC(fskk.getAccBic())
                .ccDTBankCorrAcc(fskk.getAccBankCorrAcc());

        if (fskk.getUcpId() != null && !fskk.getUcpId().isBlank()) {
            GetSberIntegrationResult byUcp = sberClient.getByUcpId(fskk.getUcpId(), rqUID);
            if (byUcp != null && byUcp.getEpk() != null && !byUcp.getEpk().isEmpty()) {
                GetSberIntegrationResult.Epk epk = byUcp.getEpk().get(0);
                b.ccDTName(epk.getOrgName())
                        .ccDTINN(epk.getOrgINN())
                        .ccDTKPP(epk.getOrgKPP());
            }
        }
    }

    private void enrichKt(TurnDocdataDraftBuilder b, String registerKt, String rqUID) {
        GetSberIntegrationResult byRegister = sberClient.getByRegisterId(registerKt, rqUID);
        GetSberIntegrationResult.Fskk fskk = byRegister != null ? byRegister.getFskk() : null;
        if (fskk == null) return;

        b.ccKTAcc(fskk.getAccNum())
                .ccKTBIC(fskk.getAccBic())
                .ccKTBankCorrAcc(fskk.getAccBankCorrAcc());

        if (fskk.getUcpId() != null && !fskk.getUcpId().isBlank()) {
            GetSberIntegrationResult byUcp = sberClient.getByUcpId(fskk.getUcpId(), rqUID);
            if (byUcp != null && byUcp.getEpk() != null && !byUcp.getEpk().isEmpty()) {
                GetSberIntegrationResult.Epk epk = byUcp.getEpk().get(0);
                b.ccKTName(epk.getOrgName())
                        .ccKTINN(epk.getOrgINN())
                        .ccKTKPP(epk.getOrgKPP());
            }
        }
    }

    /** Подставляет название банка и корсчёт из NSI.bicDirectory по BIC. */
    private void applyBicDirectory(TurnDocdataDraft d, Map<String, Participant> bicDirectory) {
        if (bicDirectory == null || bicDirectory.isEmpty()) return;

        Participant dtBank = d.getCcDTBIC() != null ? bicDirectory.get(d.getCcDTBIC()) : null;
        if (dtBank != null) {
            d.setCcDTNameBank(dtBank.getName());
            if (d.getCcDTBankCorrAcc() == null) {
                d.setCcDTBankCorrAcc(dtBank.getCorrespondentAcc());
            }
        }

        Participant ktBank = d.getCcKTBIC() != null ? bicDirectory.get(d.getCcKTBIC()) : null;
        if (ktBank != null) {
            d.setCcKTNameBank(ktBank.getName());
            if (d.getCcKTBankCorrAcc() == null) {
                d.setCcKTBankCorrAcc(ktBank.getCorrespondentAcc());
            }
        }
    }

    /** ccDT=1 → контрагент = сторона KT. Вызывается ПОСЛЕ applyBicDirectory. */
    private void applyContraFromKt(TurnDocdataDraft d) {
        d.setCcContrName(d.getCcKTName());
        d.setCcContrINN(d.getCcKTINN());
        d.setCcContrKPP(d.getCcKTKPP());
        d.setCcContrAcc(d.getCcKTAcc());
        d.setCcContrBIC(d.getCcKTBIC());
        d.setCcContrNameBank(d.getCcKTNameBank());
        d.setCcContrBankCorrAcc(d.getCcKTBankCorrAcc());
        d.setCcContrRegisterId(d.getCcKTRegisterId());
    }
}
