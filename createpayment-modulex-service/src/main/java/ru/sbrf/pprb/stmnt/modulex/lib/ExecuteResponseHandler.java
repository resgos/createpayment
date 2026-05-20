package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
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
 * <p>Жизненный цикл вызова:</p>
 * <ol>
 *   <li><b>Idempotency check</b> — если {@code idempotencyKey} уже видели,
 *       возвращаем закешированный ApiResult без повторной обработки
 *       (требование контракта PGW: дубль ResponseTicket → статус первого).</li>
 *   <li><b>Resolve context</b> — ищем status_WalletTurn по {@code ccOperationId=updUID}
 *       (PPRB_STARTED-строка содержит привязку к ccWalletTurnObjectId/ccTransactionId).
 *       Если строки нет — синк ещё не завершился → возвращаем ERROR, PGW повторит.</li>
 *   <li><b>Map status</b> — resultStatus + statusInfo.code → ccStatus
 *       (PPRB_PROCESSING / PPRB_EXECUTED / PPRB_FAILED).</li>
 *   <li><b>Persist on final</b> — для финальных EXECUTED/FAILED парсим operationDto,
 *       сохраняем turn_docdata (idempotent: skip if exists).</li>
 *   <li><b>Update status</b> — пишем новую строку в status_WalletTurn.</li>
 *   <li><b>Outbound callback</b> — только на финальном статусе шлём
 *       ExecutionResult инициатору через {@link ResultCallbackClient}.</li>
 *   <li><b>Cache result</b> — кладём SUCCESS-результат в {@link IdempotencyCache}.</li>
 * </ol>
 */
@Slf4j
@Component
public class ExecuteResponseHandler {

    private final StatusWalletTurnRepository statusRepository;
    private final TurnDocdataRepository turnDocdataRepository;
    private final PgwOperationDtoParser operationDtoParser;
    private final ResultCallbackClient resultCallback;
    private final IdempotencyCache idempotencyCache;

    public ExecuteResponseHandler(StatusWalletTurnRepository statusRepository,
                                  TurnDocdataRepository turnDocdataRepository,
                                  PgwOperationDtoParser operationDtoParser,
                                  ResultCallbackClient resultCallback,
                                  IdempotencyCache idempotencyCache) {
        this.statusRepository = statusRepository;
        this.turnDocdataRepository = turnDocdataRepository;
        this.operationDtoParser = operationDtoParser;
        this.resultCallback = resultCallback;
        this.idempotencyCache = idempotencyCache;
    }

    /**
     * Async wrapper для {@link #handle}. Controller вызывает этот метод и
     * сразу возвращает {@code 200 OK} PGW — обработка происходит в фоне на
     * пуле {@code pgwCallbackExecutor}.
     *
     * <p>По контракту PGW: ответ должен прийти быстро (timeout ~300 мс), а
     * персистенция и бизнес-логика — на наше усмотрение.</p>
     */
    @Async("pgwCallbackExecutor")
    public void handleAsync(String correlationId, String idempotencyKey, ResponseTicketRequest ticket) {
        try {
            handle(correlationId, idempotencyKey, ticket);
        } catch (Exception e) {
            log.error("Async PGW callback processing failed: correlationId={}, idempotencyKey={}, error={}",
                    correlationId, idempotencyKey, e.getMessage(), e);
        }
    }

    public ApiResult handle(String correlationId, String idempotencyKey, ResponseTicketRequest ticket) {
        // 0. Idempotency-проверка — самое первое, что делаем.
        Optional<ApiResult> cached = idempotencyCache.find(idempotencyKey);
        if (cached.isPresent()) {
            log.info("Duplicate ResponseTicket by idempotencyKey={} — returning cached ApiResult, no re-processing",
                    idempotencyKey);
            return cached.get();
        }

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

        // 1. Резолвим контекст по ccOperationId=updUID. Если синк ещё не дошёл до
        //    PPRB_STARTED — возвращаем ERROR, PGW повторит позже (механизм гар-доставки).
        Optional<StatusWalletTurnView> ctx = statusRepository.findFirstByOperationId(updUID);
        if (ctx.isEmpty()) {
            log.warn("ResponseTicket arrived before sync wrote PPRB_STARTED for updUID={} — returning ERROR, PGW will retry",
                    updUID);
            return errorResult(correlationId, idempotencyKey,
                    "Context not yet persisted for updUID=" + updUID + " — retry expected");
        }
        String walletTurnObjectId = ctx.get().getCcWalletTurnObjectId();
        String transactionId = ctx.get().getCcTransactionId();

        boolean isFinal = isFinal(ccStatus);
        boolean isExecuted = CcStatusMapper.EXECUTED.equals(ccStatus);

        // 2. turn_docdata пишется в синке (двойная запись DT+KT) — здесь только
        //    fallback на случай, если sync не успел. Использует данные из
        //    operationDto PGW (без ccKTRegisterId — он только в синке).
        if (isExecuted) {
            try {
                saveTurnDocdataIfAbsent(updUID, transactionId, walletTurnObjectId, ticket);
            } catch (Exception e) {
                log.error("turn_docdata fallback save failed for updUID={}: {}", updUID, e.getMessage(), e);
                // продолжаем — статус всё равно фиксируем
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

        ApiResult result = ApiResult.builder()
                .correlationId(correlationId)
                .idempotencyKey(idempotencyKey)
                .status("SUCCESS")
                .message("Ticket accepted, ccStatus=" + ccStatus)
                .build();

        // 5. Кэшируем успешный результат — для дедупа повторных PGW-вызовов.
        //    L1 (in-memory, мгновенно) + L2 (DataSpace, переживает рестарт пода).
        idempotencyCache.save(idempotencyKey, correlationId, updUID, result);
        return result;
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
