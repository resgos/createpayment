package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.config.AppConfig;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResponseTicketRequest;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.StatusInfo;

import java.time.LocalDateTime;

/**
 * Принимает квитанцию PGW, маппит статус и пишет в status_WalletTurn.
 * Должен отрабатывать быстро — PGW ждёт синхронный ответ; «тяжёлую» логику
 * выносить в отдельный процесс.
 */
@Slf4j
@Component
public class ExecuteResponseHandler {

    private final StatusWalletTurnRepository repository;

    public ExecuteResponseHandler(StatusWalletTurnRepository repository) {
        this.repository = repository;
    }

    public ApiResult handle(String correlationId, String idempotencyKey, ResponseTicketRequest ticket) {
        if (ticket == null) {
            log.warn("Received null ResponseTicket for correlationId={}", correlationId);
            return errorResult(correlationId, idempotencyKey, "Empty body");
        }

        StatusInfo statusInfo = ticket.getStatusInfo();
        String code = statusInfo != null ? statusInfo.getCode() : null;
        String desc = statusInfo != null ? statusInfo.getMessage() : null;
        String ccStatus = CcStatusMapper.map(ticket.getResultStatus(), code);

        log.debug("PGW response: correlationId={}, idempotencyKey={}, updUID={}, resultStatus={}, code={}, ccStatus={}",
                correlationId, idempotencyKey, ticket.getUpdUID(), ticket.getResultStatus(), code, ccStatus);

        try {
            repository.upsertStatus(StatusWalletTurnUpdate.builder()
                    .ccOperationId(ticket.getUpdUID())
                    .ccRqUId(correlationId)
                    .ccIdempotencyKey(idempotencyKey)
                    .ccStatus(ccStatus)
                    .ccStatusCode(code)
                    .ccStatusDesc(desc)
                    .ccRqTm(LocalDateTime.now(AppConfig.ZONE_ID))
                    .build());
        } catch (Exception e) {
            log.error("Failed to persist status_WalletTurn for correlationId={}: {}", correlationId, e.getMessage(), e);
            return errorResult(correlationId, idempotencyKey, e.getMessage());
        }

        return ApiResult.builder()
                .correlationId(correlationId)
                .idempotencyKey(idempotencyKey)
                .status("SUCCESS")
                .message("Ticket accepted, ccStatus=" + ccStatus)
                .build();
    }

    private ApiResult errorResult(String correlationId, String idempotencyKey, String message) {
        return ApiResult.builder()
                .correlationId(correlationId)
                .idempotencyKey(idempotencyKey)
                .status("ERROR")
                .message(message)
                .build();
    }
}
