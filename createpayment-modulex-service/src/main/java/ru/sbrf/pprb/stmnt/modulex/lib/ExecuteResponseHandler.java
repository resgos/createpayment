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
 * Обработчик квитанции от PGW ({@code POST /upd/response/execute}).
 *
 * <p>Здесь происходит вся async-часть жизненного цикла walletTurn:</p>
 * <ol>
 *   <li>Резолвим {@code ccWalletTurnObjectId / ccTransactionId} по
 *       {@code ccOperationId=updUID} через status_WalletTurn (записанная
 *       в синке строка PPRB_STARTED знает эти соответствия).</li>
 *   <li>Маппим {@code resultStatus} + {@code statusInfo.code} в ccStatus
 *       (PPRB_PROCESSING / PPRB_EXECUTED / PPRB_FAILED).</li>
 *   <li>Если статус финальный (EXECUTED/FAILED) — парсим operationDto в
 *       {@link TurnDocdataDraft} и сохраняем {@code turn_docdata} (идемпотентно).</li>
 *   <li>Обновляем строку status_WalletTurn новым ccStatus + кодом + описанием.</li>
 *   <li>Только на финальном статусе — шлём ExecutionResult инициатору
 *       на его REST URL через {@link ResultCallbackClient}.</li>
 * </ol>
 */
@Slf4j
@Component
public class ExecuteResponseHandler {

    private final StatusWalletTurnRepository statusRepository;
    private final TurnDocdataRepository turnDocdataRepository;
    private final PgwOperationDtoParser operationDtoParser;
    private final ResultCallbackClient resultCallback;

    public ExecuteResponseHandler(StatusWalletTurnRepository statusRepository,
                                  TurnDocdataRepository turnDocdataRepository,
                                  PgwOperationDtoParser operationDtoParser,
                                  ResultCallbackClient resultCallback) {
        this.statusRepository = statusRepository;
        this.turnDocdataRepository = turnDocdataRepository;
        this.operationDtoParser = operationDtoParser;
        this.resultCallback = resultCallback;
    }

    public ApiResult handle(String correlationId, String idempotencyKey, ResponseTicketRequest ticket) {
        if (ticket == null) {
            log.warn("Received null ResponseTicket for correlationId={}", correlationId);
            return errorResult(correlationId, idempotencyKey, "Empty body");
        }

        String updUID = ticket.getUpdUID();
        StatusInfo statusInfo = ticket.getStatusInfo();
        String code = statusInfo != null ? statusInfo.getCode() : null;
        String desc = statusInfo != null ? statusInfo.getMessage() : null;
        String ccStatus = CcStatusMapper.map(ticket.getResultStatus(), code);

        log.debug("PGW ticket: correlationId={}, idempotencyKey={}, updUID={}, resultStatus={}, code={}, ccStatus={}",
                correlationId, idempotencyKey, updUID, ticket.getResultStatus(), code, ccStatus);

        // 1. Резолвим ccWalletTurnObjectId / ccTransactionId через status_WalletTurn по ccOperationId.
        Optional<StatusWalletTurnView> ctx = statusRepository.findFirstByOperationId(updUID);
        if (ctx.isEmpty()) {
            log.warn("No status_WalletTurn rows found for ccOperationId={} — нечем сшить квитанцию, fallback по updUID", updUID);
        }
        String walletTurnObjectId = ctx.map(StatusWalletTurnView::getCcWalletTurnObjectId).orElse(null);
        String transactionId = ctx.map(StatusWalletTurnView::getCcTransactionId).orElse(null);

        boolean isFinal = isFinal(ccStatus);

        // 2. На финальном статусе — сохраняем turn_docdata (один раз, идемпотентно).
        if (isFinal) {
            try {
                saveTurnDocdataIfAbsent(updUID, transactionId, walletTurnObjectId, ticket);
            } catch (Exception e) {
                log.error("turn_docdata save failed for updUID={}: {}", updUID, e.getMessage(), e);
                // продолжаем — статус всё равно фиксируем, чтобы был след
            }
        }

        // 3. Обновляем status_WalletTurn новым ccStatus.
        try {
            statusRepository.upsertStatus(StatusWalletTurnUpdate.builder()
                    .ccWalletTurnObjectId(walletTurnObjectId)
                    .ccOperationId(updUID)
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

        // 4. На финальном статусе — кидаем result callback инициатору.
        if (isFinal) {
            try {
                ExecutionResult er = ExecutionResult.builder()
                        .transactionId(transactionId)
                        .operationId(updUID)
                        .bchOperationId(walletTurnObjectId)
                        .resultStatus(toExecutionStatus(ccStatus))
                        .statusDescription(CcStatusMapper.FAILED.equals(ccStatus) ? desc : null)
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

    private void saveTurnDocdataIfAbsent(String updUID,
                                         String transactionId,
                                         String walletTurnObjectId,
                                         ResponseTicketRequest ticket) {
        if (updUID == null || updUID.isBlank()) {
            log.warn("Skip turn_docdata save — updUID is null");
            return;
        }
        Optional<TurnDocdataDraft> existing = turnDocdataRepository.findByOperationId(updUID);
        if (existing.isPresent()) {
            log.debug("turn_docdata already exists for ccOperationId={} — skip save", updUID);
            return;
        }

        TurnDocdataDraft draft = operationDtoParser.parse(ticket.getOperationDto());
        if (draft == null) {
            log.warn("Cannot build turn_docdata from operationDto — empty/invalid payload for updUID={}", updUID);
            return;
        }
        // Поля, которые приходят НЕ из operationDto, проставляем сами.
        draft.setCcOperationId(updUID);
        draft.setCcBchOperationId(walletTurnObjectId);
        if (transactionId != null && (draft.getCcTransactionId() == null || draft.getCcTransactionId().isBlank())) {
            draft.setCcTransactionId(transactionId);
        }
        draft.setCcPayStatus(toCcPayStatus(ticket));
        draft.setSysLastChangeDate(LocalDateTime.now(AppConfig.ZONE_ID));

        turnDocdataRepository.save(draft);
        log.info("turn_docdata saved from PGW callback: ccOperationId={}, ccTransactionId={}, ccBchOperationId={}",
                draft.getCcOperationId(), draft.getCcTransactionId(), draft.getCcBchOperationId());
    }

    private static String toCcPayStatus(ResponseTicketRequest ticket) {
        return ticket.getResultStatus() != null ? ticket.getResultStatus().name() : null;
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
