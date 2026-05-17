package ru.sbrf.pprb.stmnt.modulex.lib;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionResult;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionStatus;
import ru.sbrf.pprb.stmnt.modulex.integration.callback.ResultCallbackClient;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResponseTicketRequest;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResultStatus;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.StatusInfo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecuteResponseHandlerTest {

    private static final String CORR_ID = "corr-1";
    private static final String IDEMP_KEY = "idemp-1";
    private static final String UPD_UID = "0123456789abcdef0123456789abcdef";
    private static final String BCH_OP = "BCH-1";
    private static final String TX_ID = "tx-1";

    private static final String OPERATION_DTO_JSON = "{\"performedOperations\":[{"
            + "\"accountingDate\":\"11.05.2026 00:00:00\","
            + "\"register\":{\"objectId\":\"7357234983332151303\"},"
            + "\"party\":{\"clientId\":\"1935680441079484251\"},"
            + "\"operationSum\":1500,"
            + "\"operationCurrency\":\"810\","
            + "\"documentReason\":{"
            + "\"inDate\":\"11.05.2026 00:00:00\","
            + "\"orderDate\":\"15.05.2026 00:00:00\","
            + "\"payerAccount\":\"40702810538000097171\","
            + "\"payerName\":\"ООО Плательщик\","
            + "\"receiverAccount\":\"40702810138000101218\","
            + "\"receiverName\":\"ООО Получатель\","
            + "\"paymentAmount\":1500"
            + "},"
            + "\"externalAttributes\":{\"processNumber\":\"" + TX_ID + "\"}"
            + "}]}";

    private InMemoryStatusWalletTurnRepository statusRepo;
    private InMemoryTurnDocdataRepository turnDocdataRepo;
    private PgwOperationDtoParser parser;
    private IdempotencyCache idempotencyCache;
    private CapturingCallback callback;
    private ExecuteResponseHandler handler;

    @BeforeEach
    void setUp() {
        statusRepo = new InMemoryStatusWalletTurnRepository();
        turnDocdataRepo = new InMemoryTurnDocdataRepository();
        parser = new PgwOperationDtoParser(new ObjectMapper());
        idempotencyCache = new IdempotencyCache();
        callback = new CapturingCallback();
        // Преднаполнили status_WalletTurn PPRB_STARTED — таким он был после синка.
        statusRepo.upsertStatus(StatusWalletTurnUpdate.builder()
                .ccWalletTurnObjectId(BCH_OP)
                .ccOperationId(UPD_UID)
                .ccTransactionId(TX_ID)
                .ccStatus(ExecutionStatus.PPRB_STARTED.name())
                .sysLastChangeDate(LocalDateTime.now())
                .build());

        handler = new ExecuteResponseHandler(statusRepo, turnDocdataRepo, parser, callback, idempotencyCache);
    }

    @Test
    void successTicketSavesTurnDocdataWritesExecutedAndSendsCallback() {
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(UPD_UID)
                .operationDto(OPERATION_DTO_JSON)
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("300").message("Документ успешно обработан").build())
                .build();

        ApiResult ack = handler.handle(CORR_ID, IDEMP_KEY, ticket);
        assertThat(ack.getStatus()).isEqualTo("SUCCESS");

        assertThat(turnDocdataRepo.findByOperationId(UPD_UID)).isPresent();

        StatusWalletTurnUpdate row = statusRepo.find(BCH_OP, "PPRB_EXECUTED");
        assertThat(row).isNotNull();
        assertThat(row.getCcOperationId()).isEqualTo(UPD_UID);
        assertThat(row.getCcTransactionId()).isEqualTo(TX_ID);

        assertThat(callback.sent).hasSize(1);
        ExecutionResult er = callback.sent.get(0);
        assertThat(er.getResultStatus()).isEqualTo(ExecutionStatus.PPRB_EXECUTED);
        assertThat(er.getBchOperationId()).isEqualTo(BCH_OP);
    }

    @Test
    void duplicateIdempotencyKeyReturnsCachedAndDoesNotReprocess() {
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(UPD_UID)
                .operationDto(OPERATION_DTO_JSON)
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("300").message("Документ успешно обработан").build())
                .build();

        // Первый вызов — обычная обработка
        ApiResult first = handler.handle(CORR_ID, IDEMP_KEY, ticket);
        assertThat(first.getStatus()).isEqualTo("SUCCESS");
        assertThat(callback.sent).hasSize(1);

        // Второй вызов с тем же idempotencyKey — должен вернуть кеш
        ApiResult second = handler.handle(CORR_ID, IDEMP_KEY, ticket);
        assertThat(second).isEqualTo(first);

        // Callback инициатору НЕ дублируется, статус не пишется повторно
        assertThat(callback.sent).hasSize(1);
        // PPRB_EXECUTED строка осталась одна (в InMemory upsert перезаписал бы — проверяем что не было повторной обработки)
        assertThat(idempotencyCache.size()).isEqualTo(1);
    }

    @Test
    void errorTicketWritesFailedStatusAndCallbackWithDescription() {
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(UPD_UID)
                .operationDto(OPERATION_DTO_JSON)
                .resultStatus(ResultStatus.ERROR)
                .statusInfo(StatusInfo.builder().code("150").message("Ошибка обработки").build())
                .build();

        handler.handle(CORR_ID, IDEMP_KEY, ticket);

        assertThat(statusRepo.find(BCH_OP, "PPRB_FAILED")).isNotNull();
        assertThat(callback.sent).hasSize(1);
        ExecutionResult er = callback.sent.get(0);
        assertThat(er.getResultStatus()).isEqualTo(ExecutionStatus.PPRB_FAILED);
        assertThat(er.getStatusDescription()).isEqualTo("Ошибка обработки");
    }

    @Test
    void processingTicketDoesNotSaveDocdataAndDoesNotSendCallback() {
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(UPD_UID)
                .operationDto(OPERATION_DTO_JSON)
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("250").message("В обработке").build())
                .build();

        handler.handle(CORR_ID, IDEMP_KEY, ticket);

        assertThat(statusRepo.find(BCH_OP, "PPRB_PROCESSING")).isNotNull();
        assertThat(turnDocdataRepo.findByOperationId(UPD_UID)).isEmpty();
        assertThat(callback.sent).isEmpty();
    }

    @Test
    void nullBodyReturnsErrorApiResult() {
        ApiResult ack = handler.handle(CORR_ID, IDEMP_KEY, null);

        assertThat(ack.getStatus()).isEqualTo("ERROR");
        assertThat(ack.getMessage()).isEqualTo("Empty body");
        assertThat(callback.sent).isEmpty();
        // ERROR не кэшируется — PGW сможет повторить
        assertThat(idempotencyCache.size()).isZero();
    }

    @Test
    void missingPprbStartedReturnsErrorForPgwRetry() {
        // нет PPRB_STARTED строки для этого updUID — синк ещё не завершился
        statusRepo = new InMemoryStatusWalletTurnRepository();
        idempotencyCache = new IdempotencyCache();
        handler = new ExecuteResponseHandler(statusRepo, turnDocdataRepo, parser, callback, idempotencyCache);

        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID("UNKNOWN-UID")
                .operationDto(OPERATION_DTO_JSON)
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("300").build())
                .build();

        ApiResult ack = handler.handle(CORR_ID, IDEMP_KEY, ticket);

        // Возвращаем ERROR — PGW повторит. Callback инициатору НЕ шлём,
        // status не пишем, turn_docdata не сохраняем.
        assertThat(ack.getStatus()).isEqualTo("ERROR");
        assertThat(ack.getMessage()).contains("Context not yet persisted");
        assertThat(callback.sent).isEmpty();
        assertThat(turnDocdataRepo.findByOperationId("UNKNOWN-UID")).isEmpty();
        // ERROR не кэшируется → PGW сможет повторить
        assertThat(idempotencyCache.size()).isZero();
    }

    private static class CapturingCallback implements ResultCallbackClient {
        final List<ExecutionResult> sent = new ArrayList<>();
        @Override
        public void send(ExecutionResult result) { sent.add(result); }
    }
}
