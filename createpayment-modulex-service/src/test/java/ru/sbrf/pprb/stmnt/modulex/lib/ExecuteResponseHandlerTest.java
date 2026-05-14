package ru.sbrf.pprb.stmnt.modulex.lib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResponseTicketRequest;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResultStatus;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.StatusInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExecuteResponseHandlerTest {

    private static final String CORR_ID = "corr-1";
    private static final String IDEMP_KEY = "idemp-1";
    private static final String UPD_UID = "0123456789abcdef0123456789abcdef";

    @Mock
    private StatusWalletTurnRepository repository;

    private ExecuteResponseHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ExecuteResponseHandler(repository);
    }

    @Test
    void successTicketPersistsExecutedStatus() {
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(UPD_UID)
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("300").message("Документ успешно обработан").build())
                .build();

        ApiResult result = handler.handle(CORR_ID, IDEMP_KEY, ticket);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getCorrelationId()).isEqualTo(CORR_ID);
        assertThat(result.getIdempotencyKey()).isEqualTo(IDEMP_KEY);
        assertThat(result.getMessage()).contains("PPRB_EXECUTED");

        ArgumentCaptor<StatusWalletTurnUpdate> captor = ArgumentCaptor.forClass(StatusWalletTurnUpdate.class);
        verify(repository).upsertStatus(captor.capture());
        StatusWalletTurnUpdate u = captor.getValue();
        assertThat(u.getCcOperationId()).isEqualTo(UPD_UID);
        assertThat(u.getCcRqUId()).isEqualTo(CORR_ID);
        assertThat(u.getCcIdempotencyKey()).isEqualTo(IDEMP_KEY);
        assertThat(u.getCcStatus()).isEqualTo("PPRB_EXECUTED");
        assertThat(u.getCcStatusCode()).isEqualTo("300");
        assertThat(u.getCcStatusDesc()).isEqualTo("Документ успешно обработан");
        assertThat(u.getCcRqTm()).isNotNull();
    }

    @Test
    void processingCodeMapsToPprbProcessing() {
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(UPD_UID)
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("250").message("В обработке").build())
                .build();

        handler.handle(CORR_ID, IDEMP_KEY, ticket);

        ArgumentCaptor<StatusWalletTurnUpdate> captor = ArgumentCaptor.forClass(StatusWalletTurnUpdate.class);
        verify(repository).upsertStatus(captor.capture());
        assertThat(captor.getValue().getCcStatus()).isEqualTo("PPRB_PROCESSING");
    }

    @Test
    void errorTicketPersistsFailedStatus() {
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(UPD_UID)
                .resultStatus(ResultStatus.ERROR)
                .statusInfo(StatusInfo.builder().code("150").message("Ошибка").build())
                .build();

        ApiResult result = handler.handle(CORR_ID, IDEMP_KEY, ticket);

        assertThat(result.getStatus()).isEqualTo("SUCCESS"); // ApiResult сам тут SUCCESS — мы приняли тикет
        ArgumentCaptor<StatusWalletTurnUpdate> captor = ArgumentCaptor.forClass(StatusWalletTurnUpdate.class);
        verify(repository).upsertStatus(captor.capture());
        assertThat(captor.getValue().getCcStatus()).isEqualTo("PPRB_FAILED");
    }

    @Test
    void emptyBodyReturnsError() {
        ApiResult result = handler.handle(CORR_ID, IDEMP_KEY, null);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).isEqualTo("Empty body");
    }

    @Test
    void repositoryFailureReturnsErrorApiResult() {
        ResponseTicketRequest ticket = ResponseTicketRequest.builder()
                .updUID(UPD_UID)
                .resultStatus(ResultStatus.SUCCESS)
                .statusInfo(StatusInfo.builder().code("300").build())
                .build();
        doThrow(new RuntimeException("db down")).when(repository).upsertStatus(org.mockito.ArgumentMatchers.any());

        ApiResult result = handler.handle(CORR_ID, IDEMP_KEY, ticket);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).contains("db down");
    }
}
