package ru.sbrf.pprb.stmnt.modulex.api.dto;

/**
 * Жизненный цикл одного walletTurn в status_WalletTurn.
 *
 * <p>Запись в таблицу делается на каждом значимом переходе — по уник-индексу
 * {@code (ccWalletTurnObjectId, ccStatus)} это одна строка на каждый этап.</p>
 *
 * <ul>
 *   <li>{@link #PPRB_GET}        — приняли запрос createPayment, провалидировали,
 *       сгенерировали ccOperationId / ccTransactionId.</li>
 *   <li>{@link #PPRB_STARTED}    — успешно отправили pacs.008 в PGW (transferUpd
 *       вернул resultStatus=SUCCESS). Ждём async-квитанцию.</li>
 *   <li>{@link #PPRB_PROCESSING} — PGW квитанция с кодами 202..299
 *       (промежуточный этап обработки на стороне PGW).</li>
 *   <li>{@link #PPRB_EXECUTED}   — PGW квитанция с кодами 300/301/315 (успех).</li>
 *   <li>{@link #PPRB_FAILED}     — сбой пайплайна на нашей стороне ИЛИ PGW коды
 *       100..199.</li>
 * </ul>
 */
public enum ExecutionStatus {
    PPRB_GET,
    PPRB_STARTED,
    PPRB_PROCESSING,
    PPRB_EXECUTED,
    PPRB_FAILED
}
