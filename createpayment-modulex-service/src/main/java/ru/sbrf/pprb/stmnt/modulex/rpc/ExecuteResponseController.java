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
 * Корп. путь: {@code /stmnt-module-x-payment-server/upd/response/execute} — префикс
 * добавляет ingress; в самом приложении эндпоинт расположен на {@code /upd/response/execute}.
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

        log.debug("executeResponse received: correlationId={}, idempotencyKey={}, updUID={}",
                correlationId, idempotencyKey,
                responseTicket != null ? responseTicket.getUpdUID() : null);

        ApiResult result = handler.handle(correlationId, idempotencyKey, responseTicket);
        return ResponseEntity.ok(result);
    }
}
