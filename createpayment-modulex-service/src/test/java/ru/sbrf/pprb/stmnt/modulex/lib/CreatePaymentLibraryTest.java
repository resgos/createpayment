package ru.sbrf.pprb.stmnt.modulex.lib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePayment;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePaymentResponse;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnInput;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnResult;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnResult.Status;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.PgwClient;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.UPDDTO;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.SberIntegrationClient;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult.Participant;
import ru.sbrf.pprb.stmnt.modulex.validator.SimpleValidator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreatePaymentLibraryTest {

    private static final String REG_DT = "R-DT-1";
    private static final String REG_KT = "R-KT-1";
    private static final String UCP_DT = "UCP-DT";
    private static final String UCP_KT = "UCP-KT";

    private static final String GEN_OPERATION_ID = "0123456789abcdef0123456789abcdef";
    private static final String GEN_TX_ID = "11111111-1111-1111-1111-111111111111";
    private static final String GEN_RQ_UID = "22222222-2222-2222-2222-222222222222";
    private static final String GEN_DOC_NUM = "123456";

    @Mock
    private SberIntegrationClient sberClient;

    @Mock
    private TurnDocdataIdGenerator idGenerator;

    @Mock
    private PgwClient pgwClient;

    private CreatePaymentLibrary library;

    @BeforeEach
    void setUp() {
        SimpleValidator validator = new SimpleValidator();
        library = new CreatePaymentLibrary(validator, sberClient, idGenerator,
                new Pacs008Builder(), pgwClient);

        lenient().when(idGenerator.operationId()).thenReturn(GEN_OPERATION_ID);
        lenient().when(idGenerator.transactionId()).thenReturn(GEN_TX_ID);
        lenient().when(idGenerator.rqUId()).thenReturn(GEN_RQ_UID);
        lenient().when(idGenerator.docNum()).thenReturn(GEN_DOC_NUM);

        // По умолчанию справочник банков пустой — отдельные тесты переопределяют.
        lenient().when(sberClient.getBicDirectory(anyString())).thenReturn(emptyDirectoryResult());

        // PGW по умолчанию возвращает correlationId = requestId.
        lenient().when(pgwClient.transferUpd(anyString(), any(UPDDTO.class)))
                .thenAnswer(inv -> ApiResult.builder()
                        .correlationId(inv.getArgument(0))
                        .status("OK")
                        .build());
    }

    @Test
    void buildsFullyEnrichedDraft() {
        mockSber(REG_DT, UCP_DT, "40700111100000000001", "044525225", "30101810400000000225",
                "ООО Плательщик", "7707083893", "773601001");
        mockSber(REG_KT, UCP_KT, "40700222200000000002", "044030702", "30101810500000000653",
                "ООО Получатель", "7800123456", "780001001");

        CreatePayment request = baseRequest(REG_DT, REG_KT, new BigDecimal("1500.00"));

        CreatePaymentResponse response = library.execute(request);

        assertThat(response.getResults()).hasSize(1);
        WalletTurnResult result = response.getResults().get(0);
        assertThat(result.getStatus()).isEqualTo(Status.DRAFT_CREATED);
        assertThat(result.getStatusDesc()).isEqualTo("OK");

        TurnDocdataDraft d = result.getTurnDocdata();
        assertThat(d.getCcRegisterId()).isEqualTo(REG_DT);
        assertThat(d.getCcWalletId()).isEqualTo("OWNER-DT");
        assertThat(d.getCcOperationId()).isEqualTo(GEN_OPERATION_ID);
        assertThat(d.getCcTransactionId()).isEqualTo(GEN_TX_ID);
        assertThat(d.getCcRqUId()).isEqualTo(GEN_RQ_UID);
        assertThat(d.getCcNum()).isEqualTo(GEN_DOC_NUM);

        assertThat(d.getCcDTAcc()).isEqualTo("40700111100000000001");
        assertThat(d.getCcDTBIC()).isEqualTo("044525225");
        assertThat(d.getCcDTBankCorrAcc()).isEqualTo("30101810400000000225");
        assertThat(d.getCcDTName()).isEqualTo("ООО Плательщик");
        assertThat(d.getCcDTINN()).isEqualTo("7707083893");
        assertThat(d.getCcDTKPP()).isEqualTo("773601001");
        assertThat(d.getCcDTRegisterId()).isEqualTo(REG_DT);
        assertThat(d.getCcDTNameBank())
                .as("ccDTNameBank остаётся null, если BIC не нашёлся в справочнике")
                .isNull();

        assertThat(d.getCcKTAcc()).isEqualTo("40700222200000000002");
        assertThat(d.getCcKTBIC()).isEqualTo("044030702");
        assertThat(d.getCcKTBankCorrAcc()).isEqualTo("30101810500000000653");
        assertThat(d.getCcKTName()).isEqualTo("ООО Получатель");
        assertThat(d.getCcKTINN()).isEqualTo("7800123456");
        assertThat(d.getCcKTKPP()).isEqualTo("780001001");
        assertThat(d.getCcKTRegisterId()).isEqualTo(REG_KT);
        assertThat(d.getCcKTNameBank())
                .as("ccKTNameBank остаётся null, если BIC не нашёлся в справочнике")
                .isNull();
    }

    @Test
    void bankNamesFilledFromBicDirectory() {
        mockSber(REG_DT, UCP_DT, "40700111100000000001", "044525225", "30101810400000000225",
                "ООО Плательщик", "7707083893", "773601001");
        mockSber(REG_KT, UCP_KT, "40700222200000000002", "044030702", "30101810500000000653",
                "ООО Получатель", "7800123456", "780001001");

        when(sberClient.getBicDirectory(anyString())).thenReturn(directoryResult(Map.of(
                "044525225", "ПАО Сбербанк",
                "044030702", "АО Банк ПОЛУЧАТЕЛЬ"
        )));

        TurnDocdataDraft d = library.execute(baseRequest(REG_DT, REG_KT, BigDecimal.ONE))
                .getResults().get(0).getTurnDocdata();

        assertThat(d.getCcDTNameBank()).isEqualTo("ПАО Сбербанк");
        assertThat(d.getCcKTNameBank()).isEqualTo("АО Банк ПОЛУЧАТЕЛЬ");
        assertThat(d.getCcContrNameBank())
                .as("ccContrNameBank должен прийти из KT")
                .isEqualTo("АО Банк ПОЛУЧАТЕЛЬ");
    }

    @Test
    void sfsFillsBranchCodeAndCcDivisionId() {
        mockSber(REG_DT, UCP_DT, "acc-dt", "044525225", "corr-dt", "DT", "i", "k",
                "38903801697", "9038", "38");
        mockSber(REG_KT, UCP_KT, "acc-kt", "044030702", "corr-kt", "KT", "i", "k",
                "38903801679", "9039", "38");

        TurnDocdataDraft d = library.execute(baseRequest(REG_DT, REG_KT, BigDecimal.ONE))
                .getResults().get(0).getTurnDocdata();

        assertThat(d.getCcDivisionId()).isEqualTo("38903801697");
        assertThat(d.getDtBranchCode()).isEqualTo("9038");
        assertThat(d.getKtBranchCode()).isEqualTo("9039");
        assertThat(d.getPacs008Xml()).contains("<BrnchId><Id>9038</Id></BrnchId>");
        assertThat(d.getPacs008Xml()).contains("<BrnchId><Id>9039</Id></BrnchId>");
    }

    @Test
    void sfsFallsBackToCodeTbWhenCodeOsbMissing() {
        mockSber(REG_DT, UCP_DT, "acc-dt", "bic-dt", "corr-dt", "DT", "i", "k",
                "38", null, "38");
        mockSber(REG_KT, UCP_KT, "acc-kt", "bic-kt", "corr-kt", "KT", "i", "k",
                "38", null, "38");

        TurnDocdataDraft d = library.execute(baseRequest(REG_DT, REG_KT, BigDecimal.ONE))
                .getResults().get(0).getTurnDocdata();

        assertThat(d.getDtBranchCode()).isEqualTo("38");
        assertThat(d.getKtBranchCode()).isEqualTo("38");
    }

    @Test
    void pgwTransferUpdSentAfterPacs008WithCorrectFields() {
        mockSber(REG_DT, UCP_DT, "acc-dt", "bic-dt", "corr-dt", "DT", "i", "k");
        mockSber(REG_KT, UCP_KT, "acc-kt", "bic-kt", "corr-kt", "KT", "i", "k");

        ArgumentCaptor<UPDDTO> updCaptor = ArgumentCaptor.forClass(UPDDTO.class);
        ArgumentCaptor<String> reqIdCaptor = ArgumentCaptor.forClass(String.class);
        when(pgwClient.transferUpd(reqIdCaptor.capture(), updCaptor.capture()))
                .thenReturn(ApiResult.builder()
                        .correlationId("CORR-1")
                        .idempotencyKey("IDEMP-1")
                        .status("OK")
                        .build());

        TurnDocdataDraft d = library.execute(baseRequest(REG_DT, REG_KT, BigDecimal.TEN))
                .getResults().get(0).getTurnDocdata();

        assertThat(reqIdCaptor.getValue()).isEqualTo(GEN_RQ_UID);

        UPDDTO upd = updCaptor.getValue();
        assertThat(upd.getUpdUID()).isEqualTo(GEN_OPERATION_ID);
        assertThat(upd.getUpdType()).isEqualTo("pacs.008.001.08");
        assertThat(upd.getSendModuleId()).isEqualTo("stmnt-giganetwork");
        assertThat(upd.getSendServiceId()).isEqualTo(GEN_TX_ID);
        assertThat(upd.getRcvModuleId()).isEqualTo("in-house-execution-payment");
        assertThat(upd.getOriginalMessage()).contains("<MsgId>" + GEN_OPERATION_ID + "</MsgId>");
        assertThat(upd.getMsgAttributes())
                .containsEntry("ParentID", GEN_OPERATION_ID)
                .containsEntry("registerId", REG_DT)
                .containsEntry("execute_on_debit", "0")
                .containsEntry("compress", "0");

        assertThat(d.getPgwCorrelationId()).isEqualTo("CORR-1");
        assertThat(d.getCcIdempotencyKey()).isEqualTo("IDEMP-1");
    }

    @Test
    void pgwFailureMarksWalletTurnAsFailed() {
        mockSber(REG_DT, UCP_DT, "acc", "bic", "corr", "n", "i", "k");
        mockSber(REG_KT, UCP_KT, "acc", "bic", "corr", "n", "i", "k");

        when(pgwClient.transferUpd(anyString(), any(UPDDTO.class)))
                .thenThrow(new IllegalStateException("PGW unreachable"));

        WalletTurnResult result = library.execute(baseRequest(REG_DT, REG_KT, BigDecimal.ONE))
                .getResults().get(0);

        assertThat(result.getStatus()).isEqualTo(WalletTurnResult.Status.FAILED);
        assertThat(result.getStatusDesc()).contains("PGW unreachable");
        assertThat(result.getTurnDocdata()).isNull();
    }

    @Test
    void bankCorrAccFromDirectoryUsedAsFallback() {
        GetSberIntegrationResult.Fskk fskk = new GetSberIntegrationResult.Fskk();
        fskk.setAccNum("acc");
        fskk.setAccBic("044525225");
        fskk.setAccBankCorrAcc(null); // корсчёт не пришёл из FSKK
        fskk.setUcpId("U-1");
        GetSberIntegrationResult byRegister = new GetSberIntegrationResult();
        byRegister.setFskk(fskk);
        when(sberClient.getByRegisterId(eq(REG_DT), anyString())).thenReturn(byRegister);
        when(sberClient.getByRegisterId(eq(REG_KT), anyString())).thenReturn(byRegister);
        GetSberIntegrationResult.Epk epk = new GetSberIntegrationResult.Epk();
        GetSberIntegrationResult byUcp = new GetSberIntegrationResult();
        byUcp.setEpk(List.of(epk));
        when(sberClient.getByUcpId(eq("U-1"), anyString())).thenReturn(byUcp);

        Participant p = new Participant();
        p.setBic("044525225");
        p.setName("ПАО Сбербанк");
        p.setCorrespondentAcc("30101810400000000225");
        GetSberIntegrationResult dir = new GetSberIntegrationResult();
        GetSberIntegrationResult.Nsi nsi = new GetSberIntegrationResult.Nsi();
        nsi.setBicDirectory(true);
        nsi.setParticipant(List.of(p));
        dir.setNsi(nsi);
        when(sberClient.getBicDirectory(anyString())).thenReturn(dir);

        TurnDocdataDraft d = library.execute(baseRequest(REG_DT, REG_KT, BigDecimal.ONE))
                .getResults().get(0).getTurnDocdata();

        assertThat(d.getCcDTBankCorrAcc())
                .as("если FSKK не отдал корсчёт, берём из справочника")
                .isEqualTo("30101810400000000225");
    }

    @Test
    void appliesConstantsAndDefaults() {
        mockSber(REG_DT, UCP_DT, "acc-dt", "bic-dt", "corr-dt", "name-dt", "inn-dt", "kpp-dt");
        mockSber(REG_KT, UCP_KT, "acc-kt", "bic-kt", "corr-kt", "name-kt", "inn-kt", "kpp-kt");

        TurnDocdataDraft d = library.execute(baseRequest(REG_DT, REG_KT, BigDecimal.TEN))
                .getResults().get(0).getTurnDocdata();

        assertThat(d.getCcPayStatus()).isEqualTo("DRAFT");
        assertThat(d.getCcDT()).isEqualTo("1");
        assertThat(d.getCcTypeOper()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(d.getCcTypeDoc()).isEqualTo("01");
        assertThat(d.getCcPriority()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(d.getCcSystemId()).isEqualTo("stmnt-giganetwork");
        assertThat(d.getCcRateDT()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(d.getCcRateKT()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(d.getCcValutaDT()).isEqualTo("810");
        assertThat(d.getCcValutaKT()).isEqualTo("810");
        assertThat(d.getCcValutaTrans()).isEqualTo("810");
        assertThat(d.getSysLastChangeDate()).isNotNull();
    }

    @Test
    void sumsAreCopiedFromInput() {
        mockSber(REG_DT, UCP_DT, "a", "b", "c", "n", "i", "k");
        mockSber(REG_KT, UCP_KT, "a", "b", "c", "n", "i", "k");

        BigDecimal sum = new BigDecimal("12345.67");
        TurnDocdataDraft d = library.execute(baseRequest(REG_DT, REG_KT, sum))
                .getResults().get(0).getTurnDocdata();

        assertThat(d.getCcSum()).isEqualByComparingTo(sum);
        assertThat(d.getCcSumNAT()).isEqualByComparingTo(sum);
        assertThat(d.getCcSumPO()).isEqualByComparingTo(sum);
        assertThat(d.getCcSumPL()).isEqualByComparingTo(sum);
    }

    @Test
    void operationDayIsDateWithoutTime() {
        mockSber(REG_DT, UCP_DT, "a", "b", "c", "n", "i", "k");
        mockSber(REG_KT, UCP_KT, "a", "b", "c", "n", "i", "k");

        LocalDateTime ts = LocalDateTime.of(2026, 4, 24, 1, 8, 3, 46_000_000);
        CreatePayment request = baseRequest(REG_DT, REG_KT, BigDecimal.ONE);
        request.getWalletTurns().get(0).setCcDate(ts);

        TurnDocdataDraft d = library.execute(request).getResults().get(0).getTurnDocdata();

        assertThat(d.getCcDate()).isEqualTo(ts);
        assertThat(d.getCcOperationDay()).isEqualTo(LocalDate.of(2026, 4, 24));
    }

    @Test
    void contraFieldsTakenFromKtSide() {
        mockSber(REG_DT, UCP_DT, "acc-dt", "bic-dt", "corr-dt", "name-dt", "inn-dt", "kpp-dt");
        mockSber(REG_KT, UCP_KT, "acc-kt", "bic-kt", "corr-kt", "name-kt", "inn-kt", "kpp-kt");

        TurnDocdataDraft d = library.execute(baseRequest(REG_DT, REG_KT, BigDecimal.ONE))
                .getResults().get(0).getTurnDocdata();

        assertThat(d.getCcContrName()).isEqualTo("name-kt");
        assertThat(d.getCcContrINN()).isEqualTo("inn-kt");
        assertThat(d.getCcContrKPP()).isEqualTo("kpp-kt");
        assertThat(d.getCcContrAcc()).isEqualTo("acc-kt");
        assertThat(d.getCcContrBIC()).isEqualTo("bic-kt");
        assertThat(d.getCcContrBankCorrAcc()).isEqualTo("corr-kt");
        assertThat(d.getCcContrRegisterId()).isEqualTo(REG_KT);
        assertThat(d.getCcContrNameBank())
                .as("без справочника банк-контрагент null")
                .isNull();
    }

    @Test
    void rqTmFallsBackToNowWhenRequestRqTmIsNull() {
        mockSber(REG_DT, UCP_DT, "a", "b", "c", "n", "i", "k");
        mockSber(REG_KT, UCP_KT, "a", "b", "c", "n", "i", "k");

        CreatePayment request = baseRequest(REG_DT, REG_KT, BigDecimal.ONE);
        request.setRqTm(null);

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        TurnDocdataDraft d = library.execute(request).getResults().get(0).getTurnDocdata();
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        assertThat(d.getCcRqTm()).isAfter(before).isBefore(after);
    }

    @Test
    void rqTmTakenFromRequestWhenProvided() {
        mockSber(REG_DT, UCP_DT, "a", "b", "c", "n", "i", "k");
        mockSber(REG_KT, UCP_KT, "a", "b", "c", "n", "i", "k");

        LocalDateTime requestRqTm = LocalDateTime.of(2024, 1, 1, 12, 0);
        CreatePayment request = baseRequest(REG_DT, REG_KT, BigDecimal.ONE);
        request.setRqTm(requestRqTm);

        TurnDocdataDraft d = library.execute(request).getResults().get(0).getTurnDocdata();

        assertThat(d.getCcRqTm()).isEqualTo(requestRqTm);
    }

    @Test
    void multipleWalletTurnsBuildIndependentDrafts() {
        mockSber(REG_DT, UCP_DT, "a", "b", "c", "n", "i", "k");
        mockSber(REG_KT, UCP_KT, "a", "b", "c", "n", "i", "k");

        when(idGenerator.operationId())
                .thenReturn("op1op1op1op1op1op1op1op1op1op101",
                        "op2op2op2op2op2op2op2op2op2op202");
        when(idGenerator.transactionId())
                .thenReturn(UUID.randomUUID().toString(), UUID.randomUUID().toString());

        CreatePayment request = baseRequest(REG_DT, REG_KT, BigDecimal.ONE);
        WalletTurnInput second = baseWalletTurn(REG_DT, REG_KT, BigDecimal.TEN);
        second.setCcBchOperationId("BCH-2");
        request.setWalletTurns(List.of(request.getWalletTurns().get(0), second));

        CreatePaymentResponse response = library.execute(request);

        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults().get(0).getTurnDocdata().getCcOperationId())
                .isNotEqualTo(response.getResults().get(1).getTurnDocdata().getCcOperationId());
        assertThat(response.getStatusCode()).isZero();
    }

    @Test
    void missingRegisterDtMarksWalletTurnAsFailed() {
        CreatePayment request = baseRequest(null, REG_KT, BigDecimal.ONE);

        WalletTurnResult result = library.execute(request).getResults().get(0);

        assertThat(result.getStatus()).isEqualTo(Status.FAILED);
        assertThat(result.getStatusDesc()).contains("ccRegisterDt");
        assertThat(result.getTurnDocdata()).isNull();
    }

    @Test
    void sberRegisterReturningNullKeepsPartyFieldsEmpty() {
        when(sberClient.getByRegisterId(eq(REG_DT), anyString())).thenReturn(null);
        when(sberClient.getByRegisterId(eq(REG_KT), anyString())).thenReturn(null);

        WalletTurnResult result = library.execute(baseRequest(REG_DT, REG_KT, BigDecimal.ONE))
                .getResults().get(0);

        assertThat(result.getStatus()).isEqualTo(Status.DRAFT_CREATED);
        TurnDocdataDraft d = result.getTurnDocdata();
        assertThat(d.getCcDTAcc()).isNull();
        assertThat(d.getCcDTName()).isNull();
        assertThat(d.getCcKTAcc()).isNull();
        assertThat(d.getCcContrName()).isNull();
        assertThat(d.getCcRegisterId()).isEqualTo(REG_DT);
        verify(sberClient, never()).getByUcpId(anyString(), anyString());
    }

    @Test
    void fskkWithoutUcpIdSkipsEpkCall() {
        GetSberIntegrationResult.Fskk fskk = new GetSberIntegrationResult.Fskk();
        fskk.setAccNum("only-acc");
        GetSberIntegrationResult res = new GetSberIntegrationResult();
        res.setFskk(fskk);
        when(sberClient.getByRegisterId(eq(REG_DT), anyString())).thenReturn(res);
        when(sberClient.getByRegisterId(eq(REG_KT), anyString())).thenReturn(res);

        TurnDocdataDraft d = library.execute(baseRequest(REG_DT, REG_KT, BigDecimal.ONE))
                .getResults().get(0).getTurnDocdata();

        assertThat(d.getCcDTAcc()).isEqualTo("only-acc");
        assertThat(d.getCcDTName()).isNull();
        verify(sberClient, never()).getByUcpId(anyString(), anyString());
    }

    @Test
    void responseSummaryReflectsFailedCount() {
        when(sberClient.getByRegisterId(anyString(), anyString())).thenReturn(null);

        WalletTurnInput goodWt = baseWalletTurn(REG_DT, REG_KT, BigDecimal.ONE);
        WalletTurnInput badWt = baseWalletTurn(null, REG_KT, BigDecimal.ONE);
        CreatePayment request = CreatePayment.builder()
                .rqUID(UUID.randomUUID().toString())
                .rqTm(LocalDateTime.now())
                .walletTurns(List.of(goodWt, badWt))
                .build();

        CreatePaymentResponse response = library.execute(request);

        assertThat(response.getStatusCode()).isZero();
        assertThat(response.getStatusDesc()).isEqualTo("Created drafts: 1, failed: 1");
    }

    // ---------- helpers ----------

    private void mockSber(String registerId, String ucpId,
                          String accNum, String bic, String corrAcc,
                          String orgName, String inn, String kpp) {
        mockSber(registerId, ucpId, accNum, bic, corrAcc, orgName, inn, kpp, null, null, null);
    }

    private void mockSber(String registerId, String ucpId,
                          String accNum, String bic, String corrAcc,
                          String orgName, String inn, String kpp,
                          String divisionId, String codeOSB, String codeTB) {
        GetSberIntegrationResult.Fskk fskk = new GetSberIntegrationResult.Fskk();
        fskk.setAccNum(accNum);
        fskk.setAccBic(bic);
        fskk.setAccBankCorrAcc(corrAcc);
        fskk.setUcpId(ucpId);
        fskk.setDivisionId(divisionId);
        GetSberIntegrationResult byReg = new GetSberIntegrationResult();
        byReg.setFskk(fskk);
        when(sberClient.getByRegisterId(eq(registerId), anyString())).thenReturn(byReg);

        GetSberIntegrationResult.Epk epk = new GetSberIntegrationResult.Epk();
        epk.setOrgName(orgName);
        epk.setOrgINN(inn);
        epk.setOrgKPP(kpp);
        GetSberIntegrationResult byUcp = new GetSberIntegrationResult();
        byUcp.setEpk(List.of(epk));
        when(sberClient.getByUcpId(eq(ucpId), anyString())).thenReturn(byUcp);

        if (divisionId != null) {
            GetSberIntegrationResult.Sfs sfs = new GetSberIntegrationResult.Sfs();
            sfs.setDivisionId(divisionId);
            sfs.setCodeOSB(codeOSB);
            sfs.setCodeTB(codeTB);
            GetSberIntegrationResult byDiv = new GetSberIntegrationResult();
            byDiv.setSfs(List.of(sfs));
            // lenient — в кейсе общего divisionId DT+KT кеш сделает только 1 вызов,
            // второй stub переопределит первый и без lenient Mockito бы ругнулся.
            lenient().when(sberClient.getByDivisionId(eq(divisionId), anyString())).thenReturn(byDiv);
        }
    }

    private CreatePayment baseRequest(String registerDt, String registerKt, BigDecimal sum) {
        return CreatePayment.builder()
                .rqUID(UUID.randomUUID().toString())
                .rqTm(LocalDateTime.of(2026, 4, 24, 1, 8, 3))
                .version("1.0")
                .walletTurns(List.of(baseWalletTurn(registerDt, registerKt, sum)))
                .build();
    }

    private GetSberIntegrationResult emptyDirectoryResult() {
        GetSberIntegrationResult res = new GetSberIntegrationResult();
        GetSberIntegrationResult.Nsi nsi = new GetSberIntegrationResult.Nsi();
        nsi.setBicDirectory(true);
        nsi.setParticipant(List.of());
        res.setNsi(nsi);
        return res;
    }

    private GetSberIntegrationResult directoryResult(Map<String, String> bicToName) {
        java.util.List<Participant> ps = new java.util.ArrayList<>();
        bicToName.forEach((bic, name) -> {
            Participant p = new Participant();
            p.setBic(bic);
            p.setName(name);
            p.setCorrespondentAcc("30101810000000000" + bic.substring(Math.max(0, bic.length() - 3)));
            ps.add(p);
        });
        GetSberIntegrationResult res = new GetSberIntegrationResult();
        GetSberIntegrationResult.Nsi nsi = new GetSberIntegrationResult.Nsi();
        nsi.setBicDirectory(true);
        nsi.setParticipant(ps);
        res.setNsi(nsi);
        return res;
    }

    private WalletTurnInput baseWalletTurn(String registerDt, String registerKt, BigDecimal sum) {
        return WalletTurnInput.builder()
                .ccBchOperationId("BCH-1")
                .ccContractId("CONTRACT-1")
                .ccOwnerDt("OWNER-DT")
                .ccRegisterDt(registerDt)
                .ccOwnerKt("OWNER-KT")
                .ccRegisterKt(registerKt)
                .ccDate(LocalDateTime.of(2026, 4, 24, 1, 8, 3))
                .ccDateDoc(LocalDateTime.of(2026, 4, 24, 0, 0))
                .ccSum(sum)
                .ccPurpose("За товары по договору №1")
                .build();
    }
}
