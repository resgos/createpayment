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
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult.Sfs;
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
    private final Pacs008Builder pacs008Builder;

    public CreatePaymentLibrary(SimpleValidator simpleValidator,
                                SberIntegrationClient sberClient,
                                TurnDocdataIdGenerator idGenerator,
                                Pacs008Builder pacs008Builder) {
        this.simpleValidator = simpleValidator;
        this.sberClient = sberClient;
        this.idGenerator = idGenerator;
        this.pacs008Builder = pacs008Builder;
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

            Map<String, Sfs> sfsCache = new HashMap<>();
            enrichDt(b, wt.getCcRegisterDt(), rqUID, sfsCache);
            enrichKt(b, wt.getCcRegisterKt(), rqUID, sfsCache);

            TurnDocdataDraft draft = b.build();
            applyBicDirectory(draft, bicDirectory);
            applyContraFromKt(draft);

            try {
                draft.setPacs008Xml(pacs008Builder.build(draft));
            } catch (Exception e) {
                log.warn("Pacs008 build failed for ccOperationId={}: {}",
                        draft.getCcOperationId(), e.getMessage());
            }

            return rb.status(Status.DRAFT_CREATED).statusDesc("OK").turnDocdata(draft).build();
        } catch (Exception e) {
            log.warn("Build draft failed for ccBchOperationId={}: {}",
                    wt != null ? wt.getCcBchOperationId() : null, e.getMessage());
            return rb.status(Status.FAILED).statusDesc(e.getMessage()).build();
        }
    }

    /** registerId → FSKK → EPK (по ucpId) + SFS (по divisionId). */
    private void enrichDt(TurnDocdataDraftBuilder b, String registerDt, String rqUID,
                          Map<String, Sfs> sfsCache) {
        GetSberIntegrationResult byRegister = sberClient.getByRegisterId(registerDt, rqUID);
        logFskk("DT", registerDt, byRegister);
        GetSberIntegrationResult.Fskk fskk = byRegister != null ? byRegister.getFskk() : null;
        if (fskk == null) {
            log.warn("DT: FSKK is null for registerDt={}, skipping ccDT* enrichment", registerDt);
            return;
        }

        b.ccDTAcc(fskk.getAccNum())
                .ccDTBIC(fskk.getAccBic())
                .ccDTBankCorrAcc(fskk.getAccBankCorrAcc())
                .ccDivisionId(fskk.getDivisionId());

        if (fskk.getUcpId() != null && !fskk.getUcpId().isBlank()) {
            GetSberIntegrationResult byUcp = sberClient.getByUcpId(fskk.getUcpId(), rqUID);
            logEpk("DT", fskk.getUcpId(), byUcp);
            if (byUcp != null && byUcp.getEpk() != null && !byUcp.getEpk().isEmpty()) {
                GetSberIntegrationResult.Epk epk = byUcp.getEpk().get(0);
                b.ccDTName(epk.getOrgName())
                        .ccDTINN(epk.getOrgINN())
                        .ccDTKPP(epk.getOrgKPP());
            }
        } else {
            log.warn("DT: FSKK.ucpId is empty for registerDt={}, skipping EPK lookup", registerDt);
        }

        Sfs sfs = resolveSfs(fskk.getDivisionId(), rqUID, sfsCache);
        if (sfs != null) {
            b.dtBranchCode(branchCode(sfs));
        }
    }

    private void enrichKt(TurnDocdataDraftBuilder b, String registerKt, String rqUID,
                          Map<String, Sfs> sfsCache) {
        GetSberIntegrationResult byRegister = sberClient.getByRegisterId(registerKt, rqUID);
        logFskk("KT", registerKt, byRegister);
        GetSberIntegrationResult.Fskk fskk = byRegister != null ? byRegister.getFskk() : null;
        if (fskk == null) {
            log.warn("KT: FSKK is null for registerKt={}, skipping ccKT* enrichment", registerKt);
            return;
        }

        b.ccKTAcc(fskk.getAccNum())
                .ccKTBIC(fskk.getAccBic())
                .ccKTBankCorrAcc(fskk.getAccBankCorrAcc());

        if (fskk.getUcpId() != null && !fskk.getUcpId().isBlank()) {
            GetSberIntegrationResult byUcp = sberClient.getByUcpId(fskk.getUcpId(), rqUID);
            logEpk("KT", fskk.getUcpId(), byUcp);
            if (byUcp != null && byUcp.getEpk() != null && !byUcp.getEpk().isEmpty()) {
                GetSberIntegrationResult.Epk epk = byUcp.getEpk().get(0);
                b.ccKTName(epk.getOrgName())
                        .ccKTINN(epk.getOrgINN())
                        .ccKTKPP(epk.getOrgKPP());
            }
        } else {
            log.warn("KT: FSKK.ucpId is empty for registerKt={}, skipping EPK lookup", registerKt);
        }

        Sfs sfs = resolveSfs(fskk.getDivisionId(), rqUID, sfsCache);
        if (sfs != null) {
            b.ktBranchCode(branchCode(sfs));
        }
    }

    /** Кешированный запрос SFS по divisionId. Возвращает запись с тем же divisionId. */
    private Sfs resolveSfs(String divisionId, String rqUID, Map<String, Sfs> cache) {
        if (divisionId == null || divisionId.isBlank()) return null;
        if (cache.containsKey(divisionId)) {
            return cache.get(divisionId);
        }
        try {
            GetSberIntegrationResult res = sberClient.getByDivisionId(divisionId, rqUID);
            Sfs match = null;
            if (res != null && res.getSfs() != null) {
                for (Sfs s : res.getSfs()) {
                    if (divisionId.equals(s.getDivisionId())) {
                        match = s;
                        break;
                    }
                }
                // fallback: самая специфичная (с codeOSB)
                if (match == null) {
                    for (Sfs s : res.getSfs()) {
                        if (s.getCodeOSB() != null) {
                            match = s;
                            break;
                        }
                    }
                }
                if (match == null && !res.getSfs().isEmpty()) {
                    match = res.getSfs().get(res.getSfs().size() - 1);
                }
            }
            cache.put(divisionId, match);
            if (match == null) {
                log.warn("SFS not resolved for divisionId={}", divisionId);
            }
            return match;
        } catch (Exception e) {
            log.warn("SFS lookup failed for divisionId={}: {}", divisionId, e.getMessage());
            cache.put(divisionId, null);
            return null;
        }
    }

    private String branchCode(Sfs sfs) {
        return sfs.getCodeOSB() != null ? sfs.getCodeOSB() : sfs.getCodeTB();
    }

    private void logFskk(String side, String registerId, GetSberIntegrationResult res) {
        if (res == null) {
            log.warn("{}: sber returned null response for registerId={}", side, registerId);
            return;
        }
        GetSberIntegrationResult.Fskk f = res.getFskk();
        if (f == null) {
            log.warn("{}: sber response has FSKK=null, top-level statusCode={}, statusDesc={}",
                    side, res.getStatusCode(), res.getStatusDesc());
            return;
        }
        log.debug("{}: FSKK registerId={} → accNum={}, accBic={}, accBankCorrAcc={}, ucpId={}, divisionId={}, statusCode={}, statusDesc={}",
                side, registerId, f.getAccNum(), f.getAccBic(), f.getAccBankCorrAcc(),
                f.getUcpId(), f.getDivisionId(), f.getStatusCode(), f.getStatusDesc());
    }

    private void logEpk(String side, String ucpId, GetSberIntegrationResult res) {
        if (res == null) {
            log.warn("{}: sber returned null response for ucpId={}", side, ucpId);
            return;
        }
        if (res.getEpk() == null || res.getEpk().isEmpty()) {
            log.warn("{}: sber response has empty EPK for ucpId={}, statusCode={}, statusDesc={}",
                    side, ucpId, res.getStatusCode(), res.getStatusDesc());
            return;
        }
        GetSberIntegrationResult.Epk e = res.getEpk().get(0);
        log.debug("{}: EPK ucpId={} → orgName={}, orgINN={}, orgKPP={}, statusCode={}",
                side, ucpId, e.getOrgName(), e.getOrgINN(), e.getOrgKPP(), e.getStatusCode());
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
