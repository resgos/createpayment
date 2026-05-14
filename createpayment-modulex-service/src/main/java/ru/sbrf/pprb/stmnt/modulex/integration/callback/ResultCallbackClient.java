package ru.sbrf.pprb.stmnt.modulex.integration.callback;

import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionResult;

public interface ResultCallbackClient {
    /**
     * Отправляет финальный итог по walletTurn инициатору на сконфигурированный REST URL.
     * Поведение при отключённом callback — no-op + лог.
     */
    void send(ExecutionResult result);
}
