package ru.sbrf.pprb.stmnt.modulex.lib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionResult;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionStatus;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;
import ru.sbrf.pprb.stmnt.modulex.integration.callback.ResultCallbackClient;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResponseTicketRequest;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResultStatus;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.StatusInfo;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecuteResponseHandlerTest {

    private static final String CORR_ID = "corr-1";
    private static final String IDEMP_KEY = "idemp-1";
    private static final String UPD_UID = "0123456789abcdef0123456789abcdef";
    private static final String BCH_OP = "BCH-1";
    private static final String TX_ID = "tx-1";

    private InMemoryStatusWalletTurnRepository statusRepo;
    private InMemoryTurnDocdataRepository turnDocdataRepo;
    private CapturingCallback callback;
    private ExecuteResponseHandler handler;

    @BeforeEach
    void setUp() {
        statusRepo = new InMemoryStatusWalletTurnRepository();
        turnDocdataRepo = new InMemoryTurnDocdataRepository();
        callback = new CapturingCallback();
        // Преднаполнили turn_docdata — он сохраняется при выполнении execute.
        turnDocdataRepo.save(TurnDocdataDraft.builder()
                .ccOperationId(UPD_UID)
                .ccTransactionId(TX_ID)
                .ccBchOperationId(BCH_OP)
                .ccContractId("C-1")
                .build());

        handler = new ExecuteResponseHandler(statusRepo, turnDocdataRepo, callback);
    }

    @Test
    void successTicketWritesExecutedStatusAndSendsCallback() {
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(UPD_UID)
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("300").message("Документ успешно обработан").build())
                .build();

        ApiResult ack = handler.handle(CORR_ID, IDEMP_KEY, ticket);
        assertThat(ack.getStatus()).isEqualTo("SUCCESS");

        // status_WalletTurn: запись PPRB_EXECUTED с правильными полями
        StatusWalletTurnUpdate row = statusRepo.find(BCH_OP, "PPRB_EXECUTED");
        assertThat(row).isNotNull();
        assertThat(row.getCcOperationId()).isEqualTo(UPD_UID);
        assertThat(row.getCcTransactionId()).isEqualTo(TX_ID);
        assertThat(row.getCcStatusCode()).isEqualTo("300");
        assertThat(row.getCcStatusDesc()).isEqualTo("Документ успешно обработан");

        // ResultCallback унесёт PPRB_EXECUTED с обоими ID
        assertThat(callback.sent).hasSize(1);
        ExecutionResult er = callback.sent.get(0);
        assertThat(er.getResultStatus()).isEqualTo(ExecutionStatus.PPRB_EXECUTED);
        assertThat(er.getOperationId()).isEqualTo(UPD_UID);
        assertThat(er.getTransactionId()).isEqualTo(TX_ID);
        assertThat(er.getBchOperationId()).isEqualTo(BCH_OP);
        assertThat(er.getContractId()).isEqualTo("C-1");
        assertThat(er.getStatusDescription()).isNull();
    }

    @Test
    void errorTicketWritesFailedStatusAndCallbackWithDescription() {
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(UPD_UID)
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
    void processingTicketDoesNotSendCallback() {
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(UPD_UID)
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("250").message("В обработке").build())
                .build();

        handler.handle(CORR_ID, IDEMP_KEY, ticket);

        assertThat(statusRepo.find(BCH_OP, "PPRB_PROCESSING")).isNotNull();
        assertThat(callback.sent).isEmpty();
    }

    @Test
    void nullBodyReturnsErrorApiResult() {
        ApiResult ack = handler.handle(CORR_ID, IDEMP_KEY, null);

        assertThat(ack.getStatus()).isEqualTo("ERROR");
        assertThat(ack.getMessage()).isEqualTo("Empty body");
        assertThat(callback.sent).isEmpty();
    }

    @Test
    void missingTurnDocdataStillUpdatesStatusButCallbackHasNullIds() {
        // нет turn_docdata в репозитории под этим updUID
        turnDocdataRepo = new InMemoryTurnDocdataRepository();
        handler = new ExecuteResponseHandler(statusRepo, turnDocdataRepo, callback);

        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID("UNKNOWN-UID")
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("300").build())
                .build();

        handler.handle(CORR_ID, IDEMP_KEY, ticket);

        // Запись о статусе ушла без ccWalletTurnObjectId / ccTransactionId
        assertThat(statusRepo.all()).hasSize(1);
        // Callback всё равно отправлен (финальное состояние), но без id
        assertThat(callback.sent).hasSize(1);
        ExecutionResult er = callback.sent.get(0);
        assertThat(er.getOperationId()).isEqualTo("UNKNOWN-UID");
        assertThat(er.getBchOperationId()).isNull();
        assertThat(er.getTransactionId()).isNull();
    }

    private static class CapturingCallback implements ResultCallbackClient {
        final List<ExecutionResult> sent = new ArrayList<>();
        @Override
        public void send(ExecutionResult result) { sent.add(result); }
    }
}
