package ru.sbrf.pprb.stmnt.modulex.rpc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResponseTicketRequest;
import ru.sbrf.pprb.stmnt.modulex.lib.ExecuteResponseHandler;

/**
 * Эндпоинт для приёма квитанций от PGW.
 *
 * <p><b>Контракт PGW</b>: ПФ-инициатор должен ответить {@code 200 OK} как можно
 * быстрее (sync timeout PGW ~300 мс). Сразу после ответа — бизнес-обработка
 * (lookup, persist status, callback) выполняется в фоне.</p>
 *
 * <p>Идемпотентность по {@code idempotencyKey} проверяется внутри
 * {@link ExecuteResponseHandler#handle} — в async-режиме. На дубль PGW
 * получает свежий {@code 200 OK} без повторной обработки.</p>
 *
 * <p>Корп. путь: {@code /stmnt-module-x-payment-server/upd/response/execute} —
 * префикс добавляет ingress.</p>
 */
@Slf4j
@RestController
@RequestMapping("/upd/response/execute")
public class ExecuteResponseController {

    private final ExecuteResponseHandler handler;

    public ExecuteResponseController(ExecuteResponseHandler handler) {
        this.handler = handler;
    }

    @PostMapping
    public ResponseEntity<ApiResult> executeResponse(
            @RequestParam("correlationId") String correlationId,
            @RequestParam("idempotencyKey") String idempotencyKey,
            @RequestBody ResponseTicketRequest responseTicket) {

        log.debug("executeResponse received: correlationId={}, idempotencyKey={}, updUID={} — accepting + processing async",
                correlationId, idempotencyKey,
                responseTicket != null ? responseTicket.getUpdUID() : null);

        // Бизнес-обработка в фоне (на пуле pgwCallbackExecutor).
        // Spring proxy интерсептит вызов и крутит метод асинхронно.
        handler.handleAsync(correlationId, idempotencyKey, responseTicket);

        // Мгновенный 200 OK PGW.
        ApiResult ack = ApiResult.builder()
                .correlationId(correlationId)
                .idempotencyKey(idempotencyKey)
                .status("SUCCESS")
                .message("Ticket accepted for async processing")
                .build();
        return ResponseEntity.ok(ack);
    }
}
