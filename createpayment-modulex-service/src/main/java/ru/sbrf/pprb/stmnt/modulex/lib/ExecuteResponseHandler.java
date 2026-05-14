package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionResult;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionStatus;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;
import ru.sbrf.pprb.stmnt.modulex.config.AppConfig;
import ru.sbrf.pprb.stmnt.modulex.integration.callback.ResultCallbackClient;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResponseTicketRequest;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.StatusInfo;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Принимает квитанцию PGW, мапит статус, обновляет status_WalletTurn и шлёт
 * финальный ExecutionResult на внешний REST инициатора.
 *
 * <p>Отрабатывает быстро — PGW ждёт синхронный ответ; тяжёлая логика идёт
 * в этом же треде, но в реальной нагрузке её нужно выносить в очередь.</p>
 */
@Slf4j
@Component
public class ExecuteResponseHandler {

    private final StatusWalletTurnRepository statusRepository;
    private final TurnDocdataRepository turnDocdataRepository;
    private final ResultCallbackClient resultCallback;

    public ExecuteResponseHandler(StatusWalletTurnRepository statusRepository,
                                  TurnDocdataRepository turnDocdataRepository,
                                  ResultCallbackClient resultCallback) {
        this.statusRepository = statusRepository;
        this.turnDocdataRepository = turnDocdataRepository;
        this.resultCallback = resultCallback;
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

        log.debug("PGW ticket: correlationId={}, idempotencyKey={}, updUID={}, resultStatus={}, code={}, ccStatus={}",
                correlationId, idempotencyKey, ticket.getUpdUID(), ticket.getResultStatus(), code, ccStatus);

        // turn_docdata по updUID = ccOperationId — нужен для резолва ccWalletTurnObjectId и
        // ccTransactionId, а также для callback.
        Optional<TurnDocdataDraft> draftOpt = turnDocdataRepository.findByOperationId(ticket.getUpdUID());
        String walletTurnObjectId = draftOpt.map(TurnDocdataDraft::getCcBchOperationId).orElse(null);
        String transactionId = draftOpt.map(TurnDocdataDraft::getCcTransactionId).orElse(null);
        String contractId = draftOpt.map(TurnDocdataDraft::getCcContractId).orElse(null);

        try {
            statusRepository.upsertStatus(StatusWalletTurnUpdate.builder()
                    .ccWalletTurnObjectId(walletTurnObjectId)
                    .ccOperationId(ticket.getUpdUID())
                    .ccTransactionId(transactionId)
                    .ccStatus(ccStatus)
                    .ccStatusCode(code)
                    .ccStatusDesc(desc)
                    .sysLastChangeDate(LocalDateTime.now(AppConfig.ZONE_ID))
                    .build());
        } catch (Exception e) {
            log.error("Failed to persist status_WalletTurn for correlationId={}: {}", correlationId, e.getMessage(), e);
            return errorResult(correlationId, idempotencyKey, e.getMessage());
        }

        // Если квитанция финальная — шлём ExecutionResult инициатору.
        if (isFinal(ccStatus)) {
            try {
                ExecutionResult er = ExecutionResult.builder()
                        .transactionId(transactionId)
                        .operationId(ticket.getUpdUID())
                        .bchOperationId(walletTurnObjectId)
                        .contractId(contractId)
                        .resultStatus(toExecutionStatus(ccStatus))
                        .statusDescription(ExecutionStatus.PPRB_FAILED.name().equals(ccStatus.toUpperCase()) ? desc : null)
                        .build();
                resultCallback.send(er);
            } catch (Exception e) {
                log.error("Result callback dispatch failed for correlationId={}: {}", correlationId, e.getMessage(), e);
            }
        }

        return ApiResult.builder()
                .correlationId(correlationId)
                .idempotencyKey(idempotencyKey)
                .status("SUCCESS")
                .message("Ticket accepted, ccStatus=" + ccStatus)
                .build();
    }

    private boolean isFinal(String ccStatus) {
        return CcStatusMapper.EXECUTED.equals(ccStatus) || CcStatusMapper.FAILED.equals(ccStatus);
    }

    private ExecutionStatus toExecutionStatus(String ccStatus) {
        if (CcStatusMapper.EXECUTED.equals(ccStatus)) return ExecutionStatus.PPRB_EXECUTED;
        if (CcStatusMapper.FAILED.equals(ccStatus)) return ExecutionStatus.PPRB_FAILED;
        return ExecutionStatus.PPRB_PROCESSING;
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
