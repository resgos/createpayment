package ru.sbrf.pprb.stmnt.modulex.lib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePayment;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePaymentResponse;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionResult;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionStatus;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurn;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnInput;
import ru.sbrf.pprb.stmnt.modulex.integration.callback.ResultCallbackClient;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.PgwClient;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResponseTicketRequest;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResultStatus;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.StatusInfo;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.UPDDTO;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.FakeSberIntegrationClient;
import ru.sbrf.pprb.stmnt.modulex.validator.SimpleValidator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной тест: JSON-RPC execute → асинхронная квитанция PGW → callback инициатору.
 * Без Mockito — все интеграции заменены фейками, чтобы видеть реальный pipeline.
 */
class CreatePaymentEndToEndTest {

    private static final String BCH_OP = "BCH-OP-7574027350321135617";
    private static final String CONTRACT = "CONTRACT-1";
    private static final String REG_DT = "7574027350321135617";
    private static final String REG_KT = "7574027350321135618";
    private static final String UCP_DT = "1962515517001713805";
    private static final String UCP_KT = "1726117282931230771";
    private static final String DIV_DT = "38903801697";
    private static final String DIV_KT = "38903801679";
    private static final String SBERBANK_BIC = "044525225";

    private FakeSberIntegrationClient sber;
    private InMemoryWalletTurnRepository walletTurnRepo;
    private InMemoryTurnDocdataRepository turnDocdataRepo;
    private InMemoryStatusWalletTurnRepository statusRepo;
    private CapturingPgwClient pgw;
    private CapturingResultCallback callback;

    private CreatePaymentLibrary library;
    private ExecuteResponseHandler responseHandler;

    @BeforeEach
    void setUp() {
        // --- Fake sberIntegration: register DT + KT, две UCP, divisionId, BIC справочник ---
        sber = new FakeSberIntegrationClient()
                .putRegister(REG_DT, "40802810838710018558", SBERBANK_BIC,
                        "30101810400000000225", UCP_DT, DIV_DT)
                .putRegister(REG_KT, "40802840238000001738", SBERBANK_BIC,
                        "30101810400000000225", UCP_KT, DIV_KT)
                .putUcp(UCP_DT, "ИП БЕЛИКОВА И. П.", "253401125465", "0")
                .putUcp(UCP_KT, "ООО \"ДИЗЕЛЬ\"", "4437928005", "441619001")
                .putDivision(DIV_DT, "9038", "38", "Доп.офис №9038/01697")
                .putDivision(DIV_KT, "9039", "38", "Доп.офис №9039/01679")
                .putBic(SBERBANK_BIC, "ПАО Сбербанк", "30101810400000000225");

        // --- In-memory DataSpace ---
        walletTurnRepo = new InMemoryWalletTurnRepository();
        turnDocdataRepo = new InMemoryTurnDocdataRepository();
        statusRepo = new InMemoryStatusWalletTurnRepository();
        walletTurnRepo.put(WalletTurn.builder()
                .ccBchOperationId(BCH_OP)
                .ccContractId(CONTRACT)
                .ccOwnerDt("OWNER-DT")
                .ccRegisterDt(REG_DT)
                .ccOwnerKt("OWNER-KT")
                .ccRegisterKt(REG_KT)
                .ccDate(LocalDateTime.of(2026, 5, 11, 10, 0))
                .ccDateDoc(LocalDateTime.of(2021, 10, 4, 11, 42, 28))
                .ccSum(new BigDecimal("1500.00"))
                .ccPurpose("End-to-end test payment")
                .build());

        // --- Fakes для PGW и callback ---
        pgw = new CapturingPgwClient();
        callback = new CapturingResultCallback();

        // --- Реальная библиотека: pacs.008 строится, идентификаторы генерятся ---
        library = new CreatePaymentLibrary(new SimpleValidator(), sber, new TurnDocdataIdGenerator(),
                new Pacs008Builder(), pgw, walletTurnRepo, turnDocdataRepo, statusRepo);
        responseHandler = new ExecuteResponseHandler(statusRepo, turnDocdataRepo,
                new PgwOperationDtoParser(new com.fasterxml.jackson.databind.ObjectMapper()),
                callback,
                new IdempotencyCache(new InMemoryIdempotencyStore(),
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    /** Минимальный operationDto для имитации PGW callback. */
    private static String operationDtoJson(String txId) {
        return "{\"performedOperations\":[{"
                + "\"accountingDate\":\"11.05.2026 00:00:00\","
                + "\"register\":{\"objectId\":\"" + REG_DT + "\"},"
                + "\"operationSum\":1500,"
                + "\"operationCurrency\":\"810\","
                + "\"documentReason\":{"
                + "\"orderDate\":\"15.05.2026 00:00:00\","
                + "\"payerAccount\":\"40702810538000097171\","
                + "\"payerName\":\"ИП БЕЛИКОВА И. П.\","
                + "\"receiverAccount\":\"40702810138000101218\","
                + "\"receiverName\":\"ООО \\\"ДИЗЕЛЬ\\\"\","
                + "\"payerBankName\":\"ПАО Сбербанк\","
                + "\"receiverBankName\":\"ПАО Сбербанк\","
                + "\"paymentAmount\":1500"
                + "},"
                + "\"externalAttributes\":{\"processNumber\":\"" + txId + "\"}"
                + "}]}";
    }

    @Test
    void fullHappyPath_syncProcessing_thenPgwTicket_thenCallback() {
        // 1. JSON-RPC execute → sync приняли + отправили в PGW
        CreatePayment request = CreatePayment.builder()
                .rqUID(UUID.randomUUID().toString())
                .rqTm(LocalDateTime.now())
                .version("2.0")
                .walletTurns(List.of(WalletTurnInput.builder()
                        .ccBchOperationId(BCH_OP).ccContractId(CONTRACT).build()))
                .build();
        CreatePaymentResponse syncResponse = library.execute(request);

        // sync вернул PROCESSING — финального ответа от PGW ещё нет
        assertThat(syncResponse.getExecutionResults()).hasSize(1);
        ExecutionResult sync = syncResponse.getExecutionResults().get(0);
        assertThat(sync.getResultStatus()).isEqualTo(ExecutionStatus.PPRB_PROCESSING);
        assertThat(sync.getBchOperationId()).isEqualTo(BCH_OP);
        assertThat(sync.getContractId()).isEqualTo(CONTRACT);
        assertThat(sync.getOperationId()).isNotBlank();
        assertThat(sync.getTransactionId()).isNotBlank();

        // 2. PGW реально получил pacs.008
        assertThat(pgw.sent).hasSize(1);
        UPDDTO upd = pgw.sent.get(0);
        assertThat(upd.getUpdUID()).isEqualTo(sync.getOperationId());
        assertThat(upd.getUpdType()).isEqualTo("pacs.008.001.08");
        assertThat(upd.getOriginalMessage())
                .contains("<MsgId>" + sync.getOperationId() + "</MsgId>")
                .contains("<Dbtr><Nm>ИП БЕЛИКОВА И. П.</Nm>")
                .contains("<Cdtr><Nm>ООО \"ДИЗЕЛЬ\"</Nm>")
                .contains("<DbtrAgt>")
                .doesNotContain("<BrnchId>"); // BrnchId не выпускается по эталону

        // 3. В синке turn_docdata НЕ сохраняется (это асинхронно после callback PGW).
        assertThat(turnDocdataRepo.findByOperationId(sync.getOperationId())).isEmpty();

        // А статусы PPRB_GET и PPRB_STARTED — записаны.
        StatusWalletTurnUpdate getRow = statusRepo.find(BCH_OP, "PPRB_GET");
        assertThat(getRow).isNotNull();
        assertThat(getRow.getCcOperationId()).isEqualTo(sync.getOperationId());

        StatusWalletTurnUpdate startedRow = statusRepo.find(BCH_OP, "PPRB_STARTED");
        assertThat(startedRow).isNotNull();
        assertThat(startedRow.getCcOperationId()).isEqualTo(sync.getOperationId());
        assertThat(startedRow.getCcTransactionId()).isEqualTo(sync.getTransactionId());

        // Callback ещё НЕ отправлен — статус не финальный
        assertThat(callback.sent).isEmpty();

        // 4. PGW потом дёргает нас на /upd/response/execute с финальным кодом 300 (исполнено)
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(sync.getOperationId())
                .operationDto(operationDtoJson(sync.getTransactionId()))
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("300").message("Документ успешно обработан").build())
                .build();
        ApiResult ack = responseHandler.handle("CORR-1",
                "fa163e86-2c97-1eed-aaa6-2d82577ece51", ticket);

        // turn_docdata создан из callback payload
        TurnDocdataDraft persistedDraft = turnDocdataRepo.findByOperationId(sync.getOperationId())
                .orElseThrow();
        assertThat(persistedDraft.getCcBchOperationId()).isEqualTo(BCH_OP);
        assertThat(persistedDraft.getCcTransactionId()).isEqualTo(sync.getTransactionId());
        assertThat(persistedDraft.getCcDTName()).isEqualTo("ИП БЕЛИКОВА И. П.");
        assertThat(persistedDraft.getCcKTName()).isEqualTo("ООО \"ДИЗЕЛЬ\"");

        assertThat(ack.getStatus()).isEqualTo("SUCCESS");

        // 5. status_WalletTurn пополнился записью PPRB_EXECUTED
        StatusWalletTurnUpdate executedRow = statusRepo.find(BCH_OP, "PPRB_EXECUTED");
        assertThat(executedRow).isNotNull();
        assertThat(executedRow.getCcStatusCode()).isEqualTo("300");
        assertThat(executedRow.getCcStatusDesc()).isEqualTo("Документ успешно обработан");
        assertThat(executedRow.getCcTransactionId()).isEqualTo(sync.getTransactionId());

        // 6. И ResultCallback унёс финальный ExecutionResult инициатору
        assertThat(callback.sent).hasSize(1);
        ExecutionResult final_ = callback.sent.get(0);
        assertThat(final_.getResultStatus()).isEqualTo(ExecutionStatus.PPRB_EXECUTED);
        assertThat(final_.getBchOperationId()).isEqualTo(BCH_OP);
        assertThat(final_.getOperationId()).isEqualTo(sync.getOperationId());
        assertThat(final_.getTransactionId()).isEqualTo(sync.getTransactionId());
        assertThat(final_.getStatusDescription()).isNull();
    }

    @Test
    void pgwTicketWithErrorTriggersFailedCallback() {
        // sync — стандартный PROCESSING
        CreatePayment request = CreatePayment.builder()
                .rqUID(UUID.randomUUID().toString())
                .rqTm(LocalDateTime.now())
                .version("2.0")
                .walletTurns(List.of(WalletTurnInput.builder()
                        .ccBchOperationId(BCH_OP).ccContractId(CONTRACT).build()))
                .build();
        ExecutionResult sync = library.execute(request).getExecutionResults().get(0);

        // PGW потом квитирует ошибкой
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(sync.getOperationId())
                .operationDto(operationDtoJson(sync.getTransactionId()))
                .resultStatus(ResultStatus.ERROR)
                .statusInfo(StatusInfo.builder().code("150").message("Регистр заблокирован").build())
                .build();
        responseHandler.handle("CORR-1", "IDEMP-1", ticket);

        StatusWalletTurnUpdate failed = statusRepo.find(BCH_OP, "PPRB_FAILED");
        assertThat(failed).isNotNull();
        assertThat(failed.getCcStatusDesc()).isEqualTo("Регистр заблокирован");

        assertThat(callback.sent).hasSize(1);
        ExecutionResult cb = callback.sent.get(0);
        assertThat(cb.getResultStatus()).isEqualTo(ExecutionStatus.PPRB_FAILED);
        assertThat(cb.getStatusDescription()).isEqualTo("Регистр заблокирован");
        assertThat(cb.getBchOperationId()).isEqualTo(BCH_OP);
    }

    @Test
    void pgwTicketWithProcessingCodeDoesNotTriggerCallback() {
        CreatePayment request = CreatePayment.builder()
                .rqUID(UUID.randomUUID().toString())
                .rqTm(LocalDateTime.now())
                .version("2.0")
                .walletTurns(List.of(WalletTurnInput.builder()
                        .ccBchOperationId(BCH_OP).ccContractId(CONTRACT).build()))
                .build();
        ExecutionResult sync = library.execute(request).getExecutionResults().get(0);

        // 250 — промежуточный код, не финальный
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(sync.getOperationId())
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("250").message("В обработке").build())
                .build();
        responseHandler.handle("CORR-1", "IDEMP-1", ticket);

        // Статус сохранён как PROCESSING, но callback инициатору НЕ ушёл
        assertThat(statusRepo.find(BCH_OP, "PPRB_PROCESSING")).isNotNull();
        assertThat(callback.sent).as("callback only on final state").isEmpty();
    }

    // ---------- captors ----------

    private static class CapturingPgwClient implements PgwClient {
        final List<UPDDTO> sent = new ArrayList<>();
        @Override
        public ApiResult transferUpd(String requestId, UPDDTO updDTO) {
            sent.add(updDTO);
            return ApiResult.builder().correlationId(requestId).status("OK").build();
        }
    }

    private static class CapturingResultCallback implements ResultCallbackClient {
        final List<ExecutionResult> sent = new ArrayList<>();
        @Override
        public void send(ExecutionResult result) { sent.add(result); }
    }
}
