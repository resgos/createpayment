package ru.sbrf.pprb.stmnt.modulex.lib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePayment;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePaymentResponse;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionResult;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionStatus;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurn;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnInput;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.PgwClient;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.UPDDTO;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.SberIntegrationClient;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult.Participant;
import ru.sbrf.pprb.stmnt.modulex.validator.SimpleValidator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreatePaymentLibraryTest {

    private static final String BCH_OP_1 = "BCH-1";
    private static final String CONTRACT_1 = "CONTRACT-1";
    private static final String REG_DT = "R-DT-1";
    private static final String REG_KT = "R-KT-1";
    private static final String UCP_DT = "UCP-DT";
    private static final String UCP_KT = "UCP-KT";

    private static final String GEN_OPERATION_ID = "0123456789abcdef0123456789abcdef";
    private static final String GEN_TX_ID = "11111111-1111-1111-1111-111111111111";
    private static final String GEN_RQ_UID = "22222222-2222-2222-2222-222222222222";
    private static final String GEN_DOC_NUM = "123456";

    @Mock private SberIntegrationClient sberClient;
    @Mock private TurnDocdataIdGenerator idGenerator;
    @Mock private PgwClient pgwClient;

    private InMemoryWalletTurnRepository walletTurnRepository;
    private InMemoryTurnDocdataRepository turnDocdataRepository;
    private InMemoryStatusWalletTurnRepository statusRepository;
    private CreatePaymentLibrary library;

    @BeforeEach
    void setUp() {
        walletTurnRepository = new InMemoryWalletTurnRepository();
        turnDocdataRepository = new InMemoryTurnDocdataRepository();
        statusRepository = new InMemoryStatusWalletTurnRepository();
        library = new CreatePaymentLibrary(new SimpleValidator(), sberClient, idGenerator,
                new Pacs008Builder(), pgwClient, walletTurnRepository,
                turnDocdataRepository, statusRepository);

        lenient().when(idGenerator.operationId()).thenReturn(GEN_OPERATION_ID);
        lenient().when(idGenerator.transactionId()).thenReturn(GEN_TX_ID);
        lenient().when(idGenerator.rqUId()).thenReturn(GEN_RQ_UID);
        lenient().when(idGenerator.docNum()).thenReturn(GEN_DOC_NUM);

        lenient().when(sberClient.getBicDirectory(anyString())).thenReturn(emptyDirectory());
        lenient().when(pgwClient.transferUpd(anyString(), any(UPDDTO.class)))
                .thenAnswer(inv -> ApiResult.builder().correlationId(inv.getArgument(0)).status("OK").build());
    }

    @Test
    void syncWritesGetAndStartedStatusesWhenPipelineSucceeds() {
        walletTurnRepository.put(sampleWalletTurn());
        mockSber(REG_DT, UCP_DT, "acc-dt", "bic-dt", "corr-dt", "ООО Плательщик", "INN-1", "KPP-1");
        mockSber(REG_KT, UCP_KT, "acc-kt", "bic-kt", "corr-kt", "ООО Получатель", "INN-2", "KPP-2");

        CreatePaymentResponse response = library.execute(baseRequest(BCH_OP_1, CONTRACT_1));

        assertThat(response.getExecutionResults()).hasSize(1);
        ExecutionResult result = response.getExecutionResults().get(0);
        // Синхронный возврат — всегда PPRB_PROCESSING (финальный статус придёт от PGW).
        assertThat(result.getResultStatus()).isEqualTo(ExecutionStatus.PPRB_PROCESSING);
        assertThat(result.getStatusDescription()).isNull();
        assertThat(result.getTransactionId()).isEqualTo(GEN_TX_ID);
        assertThat(result.getOperationId()).isEqualTo(GEN_OPERATION_ID);
        assertThat(result.getBchOperationId()).isEqualTo(BCH_OP_1);
        assertThat(result.getContractId()).isEqualTo(CONTRACT_1);

        // turn_docdata в синке НЕ сохраняется — будет создан после callback от PGW.
        assertThat(turnDocdataRepository.findByOperationId(GEN_OPERATION_ID)).isEmpty();

        // status_WalletTurn содержит PPRB_GET (после валидации) и PPRB_STARTED (после PGW).
        StatusWalletTurnUpdate getRow = statusRepository.find(BCH_OP_1, "PPRB_GET");
        assertThat(getRow).isNotNull();
        assertThat(getRow.getCcOperationId()).isEqualTo(GEN_OPERATION_ID);
        assertThat(getRow.getCcTransactionId()).isEqualTo(GEN_TX_ID);

        StatusWalletTurnUpdate startedRow = statusRepository.find(BCH_OP_1, "PPRB_STARTED");
        assertThat(startedRow).isNotNull();
        assertThat(startedRow.getCcOperationId()).isEqualTo(GEN_OPERATION_ID);
        assertThat(startedRow.getCcTransactionId()).isEqualTo(GEN_TX_ID);
    }

    @Test
    void failedWhenWalletTurnNotFound() {
        // в репозитории пусто
        ExecutionResult result = library.execute(baseRequest(BCH_OP_1, CONTRACT_1))
                .getExecutionResults().get(0);

        assertThat(result.getResultStatus()).isEqualTo(ExecutionStatus.PPRB_FAILED);
        assertThat(result.getStatusDescription()).contains("WalletTurn not found");
        assertThat(result.getBchOperationId()).isEqualTo(BCH_OP_1);
        assertThat(result.getContractId()).isEqualTo(CONTRACT_1);
        // ID-ы генерируются до try — даже при ошибке они в результате есть.
        assertThat(result.getTransactionId()).isEqualTo(GEN_TX_ID);
        assertThat(result.getOperationId()).isEqualTo(GEN_OPERATION_ID);

        // FAILED-статус всё равно зафиксирован
        StatusWalletTurnUpdate row = statusRepository.find(BCH_OP_1, "PPRB_FAILED");
        assertThat(row).isNotNull();
        assertThat(row.getCcStatusDesc()).contains("WalletTurn not found");
    }

    @Test
    void failedWhenBchOperationIdMissing() {
        ExecutionResult result = library.execute(baseRequest(null, CONTRACT_1))
                .getExecutionResults().get(0);

        assertThat(result.getResultStatus()).isEqualTo(ExecutionStatus.PPRB_FAILED);
        assertThat(result.getStatusDescription()).contains("ccBchOperationId");
    }

    @Test
    void pgwFailureMarksItemFailedAndPersistsFailedStatus() {
        walletTurnRepository.put(sampleWalletTurn());
        mockSber(REG_DT, UCP_DT, "a", "b", "c", "n", "i", "k");
        mockSber(REG_KT, UCP_KT, "a", "b", "c", "n", "i", "k");
        when(pgwClient.transferUpd(anyString(), any(UPDDTO.class)))
                .thenThrow(new IllegalStateException("PGW unreachable"));

        ExecutionResult result = library.execute(baseRequest(BCH_OP_1, CONTRACT_1))
                .getExecutionResults().get(0);

        assertThat(result.getResultStatus()).isEqualTo(ExecutionStatus.PPRB_FAILED);
        assertThat(result.getStatusDescription()).contains("PGW unreachable");
        // PPRB_GET записался (после валидации), PPRB_STARTED НЕ записался (PGW бросил), PPRB_FAILED есть.
        assertThat(statusRepository.find(BCH_OP_1, "PPRB_GET")).isNotNull();
        assertThat(statusRepository.find(BCH_OP_1, "PPRB_STARTED")).isNull();
        assertThat(statusRepository.find(BCH_OP_1, "PPRB_FAILED")).isNotNull();
    }

    @Test
    void batchMixesProcessingAndFailed() {
        WalletTurn ok = sampleWalletTurn().toBuilder().ccBchOperationId("BCH-OK").build();
        walletTurnRepository.put(ok);
        mockSber(REG_DT, UCP_DT, "a", "b", "c", "n", "i", "k");
        mockSber(REG_KT, UCP_KT, "a", "b", "c", "n", "i", "k");
        when(idGenerator.transactionId()).thenReturn("tx-1", "tx-2");
        when(idGenerator.operationId()).thenReturn("op-1", "op-2");

        CreatePayment request = CreatePayment.builder()
                .rqUID(UUID.randomUUID().toString())
                .rqTm(LocalDateTime.now())
                .version("2.0")
                .walletTurns(List.of(
                        WalletTurnInput.builder().ccBchOperationId("BCH-OK").ccContractId("C-1").build(),
                        WalletTurnInput.builder().ccBchOperationId("BCH-MISS").ccContractId("C-2").build()))
                .build();

        List<ExecutionResult> results = library.execute(request).getExecutionResults();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getResultStatus()).isEqualTo(ExecutionStatus.PPRB_PROCESSING);
        assertThat(results.get(1).getResultStatus()).isEqualTo(ExecutionStatus.PPRB_FAILED);
    }

    @Test
    void upddtoCarriesPacs008AndCorrectAttributes() {
        walletTurnRepository.put(sampleWalletTurn());
        mockSber(REG_DT, UCP_DT, "a", "b", "c", "n", "i", "k");
        mockSber(REG_KT, UCP_KT, "a", "b", "c", "n", "i", "k");
        ArgumentCaptor<UPDDTO> upd = ArgumentCaptor.forClass(UPDDTO.class);
        ArgumentCaptor<String> reqId = ArgumentCaptor.forClass(String.class);
        when(pgwClient.transferUpd(reqId.capture(), upd.capture()))
                .thenReturn(ApiResult.builder().correlationId("CORR-1").status("OK").build());

        library.execute(baseRequest(BCH_OP_1, CONTRACT_1));

        assertThat(reqId.getValue()).isEqualTo(GEN_RQ_UID);
        UPDDTO u = upd.getValue();
        assertThat(u.getUpdUID()).isEqualTo(GEN_OPERATION_ID);
        assertThat(u.getUpdType()).isEqualTo("pacs.008.001.08");
        assertThat(u.getSendModuleId()).isEqualTo("stmnt-giganetwork");
        assertThat(u.getSendServiceId()).isEqualTo(GEN_TX_ID);
        assertThat(u.getRcvModuleId()).isEqualTo("in-house-execution-payment");
        assertThat(u.getMsgAttributes())
                .containsEntry("ParentID", GEN_OPERATION_ID)
                .containsEntry("registerId", REG_DT)
                .containsEntry("execute_on_debit", "0")
                .containsEntry("compress", "0");
        assertThat(u.getOriginalMessage()).contains("<MsgId>" + GEN_OPERATION_ID + "</MsgId>");
    }

    // ---------- helpers ----------

    private void mockSber(String registerId, String ucpId,
                          String accNum, String bic, String corrAcc,
                          String orgName, String inn, String kpp) {
        GetSberIntegrationResult.Fskk fskk = new GetSberIntegrationResult.Fskk();
        fskk.setAccNum(accNum);
        fskk.setAccBic(bic);
        fskk.setAccBankCorrAcc(corrAcc);
        fskk.setUcpId(ucpId);
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
    }

    private GetSberIntegrationResult emptyDirectory() {
        GetSberIntegrationResult res = new GetSberIntegrationResult();
        GetSberIntegrationResult.Nsi nsi = new GetSberIntegrationResult.Nsi();
        nsi.setBicDirectory(true);
        nsi.setParticipant(List.of());
        res.setNsi(nsi);
        return res;
    }

    private CreatePayment baseRequest(String bchOpId, String contractId) {
        return CreatePayment.builder()
                .rqUID(UUID.randomUUID().toString())
                .rqTm(LocalDateTime.of(2026, 5, 11, 18, 0))
                .version("2.0")
                .walletTurns(List.of(WalletTurnInput.builder()
                        .ccBchOperationId(bchOpId)
                        .ccContractId(contractId)
                        .build()))
                .build();
    }

    private WalletTurn sampleWalletTurn() {
        return WalletTurn.builder()
                .ccBchOperationId(BCH_OP_1)
                .ccContractId(CONTRACT_1)
                .ccOwnerDt("OWNER-DT")
                .ccRegisterDt(REG_DT)
                .ccOwnerKt("OWNER-KT")
                .ccRegisterKt(REG_KT)
                .ccDate(LocalDateTime.of(2026, 5, 11, 10, 0))
                .ccDateDoc(LocalDateTime.of(2021, 10, 4, 11, 42, 28))
                .ccSum(new BigDecimal("1500.00"))
                .ccPurpose("Test payment")
                .build();
    }
}
