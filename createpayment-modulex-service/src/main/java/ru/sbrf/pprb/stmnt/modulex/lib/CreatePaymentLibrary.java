package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePayment;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePaymentResponse;
import ru.sbrf.pprb.stmnt.modulex.api.dto.PartyEnrichment;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnInput;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnResult;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnResult.Status;
import ru.sbrf.pprb.stmnt.modulex.config.AppConfig;
import ru.sbrf.pprb.stmnt.modulex.config.CreatePaymentProperties;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.SberIntegrationClient;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult;
import ru.sbrf.pprb.stmnt.modulex.validator.SimpleValidator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class CreatePaymentLibrary {

    private final SimpleValidator simpleValidator;
    private final CreatePaymentProperties properties;
    private final SberIntegrationClient sberClient;

    public CreatePaymentLibrary(SimpleValidator simpleValidator,
                                CreatePaymentProperties properties,
                                SberIntegrationClient sberClient) {
        this.simpleValidator = simpleValidator;
        this.properties = properties;
        this.sberClient = sberClient;
    }

    public CreatePaymentResponse execute(CreatePayment request) {
        simpleValidator.requireNonNull(request, "request");
        simpleValidator.requireNonNull(request.getWalletTurns(), "walletTurns");

        String rqUID = request.getRqUID();
        List<WalletTurnResult> results = new ArrayList<>(request.getWalletTurns().size());

        for (WalletTurnInput wt : request.getWalletTurns()) {
            results.add(enrich(wt, rqUID));
        }

        int failed = (int) results.stream().filter(r -> r.getStatus() == Status.FAILED).count();
        int partial = (int) results.stream().filter(r -> r.getStatus() == Status.PARTIALLY_ENRICHED).count();

        return CreatePaymentResponse.builder()
                .rqUID(rqUID)
                .rqTm(LocalDateTime.now(AppConfig.ZONE_ID))
                .statusCode(failed == results.size() ? 1 : 0)
                .statusDesc(buildSummary(results.size(), failed, partial))
                .results(results)
                .build();
    }

    private WalletTurnResult enrich(WalletTurnInput wt, String rqUID) {
        simpleValidator.requireNonBlank(wt.getCcWalletTurnId(), "ccWalletTurnId");

        WalletTurnResult.WalletTurnResultBuilder b = WalletTurnResult.builder()
                .ccWalletTurnId(wt.getCcWalletTurnId())
                .ccTransactionId(wt.getCcTransactionId());

        try {
            PartyEnrichment dt = enrichByRegister(wt.getCcRegisterDt(), rqUID);
            PartyEnrichment kt = enrichByRegister(wt.getCcRegisterKt(), rqUID);

            Status status;
            if (dt == null && kt == null) {
                status = Status.FAILED;
            } else if (isComplete(dt) && isComplete(kt)) {
                status = Status.ENRICHED;
            } else {
                status = Status.PARTIALLY_ENRICHED;
            }

            return b.debit(dt).credit(kt)
                    .status(status)
                    .statusDesc(status == Status.FAILED ? "No data resolved" : "OK")
                    .build();
        } catch (Exception e) {
            log.warn("Enrichment failed for walletTurnId={}: {}", wt.getCcWalletTurnId(), e.getMessage());
            return b.status(Status.FAILED).statusDesc(e.getMessage()).build();
        }
    }

    /**
     * Цепочка: registerId → FSKK(ucpId, divisionId) → EPK(orgName, INN) → SFS(BIC, corrAcc).
     * Каждый шаг последовательный, потому что параметры следующего вызова берутся из предыдущего ответа.
     */
    private PartyEnrichment enrichByRegister(String registerId, String rqUID) {
        if (registerId == null || registerId.isBlank()) {
            return null;
        }

        GetSberIntegrationResult byRegister = sberClient.getByRegisterId(registerId, rqUID);
        GetSberIntegrationResult.Fskk fskk = byRegister != null ? byRegister.getFskk() : null;
        if (fskk == null) {
            return PartyEnrichment.builder().registerId(registerId).build();
        }

        PartyEnrichment.PartyEnrichmentBuilder eb = PartyEnrichment.builder()
                .registerId(registerId)
                .accNum(fskk.getAccNum())
                .accCurrency(fskk.getAccCurrency())
                .accBic(fskk.getAccBic())
                .accBankCorrAcc(fskk.getAccBankCorrAcc())
                .ucpId(fskk.getUcpId())
                .divisionId(fskk.getDivisionId());

        if (fskk.getUcpId() != null && !fskk.getUcpId().isBlank()) {
            GetSberIntegrationResult byUcp = sberClient.getByUcpId(fskk.getUcpId(), rqUID);
            if (byUcp != null && byUcp.getEpk() != null && !byUcp.getEpk().isEmpty()) {
                GetSberIntegrationResult.Epk epk = byUcp.getEpk().get(0);
                eb.orgName(epk.getOrgName()).orgINN(epk.getOrgINN()).orgKPP(epk.getOrgKPP());
            }
        }

        if (fskk.getDivisionId() != null && !fskk.getDivisionId().isBlank()) {
            GetSberIntegrationResult byDiv = sberClient.getByDivisionId(fskk.getDivisionId(), rqUID);
            if (byDiv != null && byDiv.getSfs() != null && !byDiv.getSfs().isEmpty()) {
                GetSberIntegrationResult.Sfs sfs = byDiv.getSfs().get(0);
                eb.divisionName(sfs.getFullName());
                if (sfs.getRequisitesDivision() != null) {
                    GetSberIntegrationResult.RequisitesDivision rd = sfs.getRequisitesDivision();
                    if (eb.build().getAccBic() == null) {
                        eb.accBic(rd.getDivBIC());
                    }
                    if (eb.build().getAccBankCorrAcc() == null) {
                        eb.accBankCorrAcc(rd.getCorrespondentAcc());
                    }
                }
            }
        }

        return eb.build();
    }

    private boolean isComplete(PartyEnrichment p) {
        return p != null
                && p.getAccNum() != null
                && p.getAccBic() != null
                && p.getOrgName() != null;
    }

    private String buildSummary(int total, int failed, int partial) {
        if (failed == total && total > 0) {
            return "All " + total + " walletTurn(s) failed enrichment";
        }
        int ok = total - failed - partial;
        return "Enriched: " + ok + ", partial: " + partial + ", failed: " + failed;
    }
}
