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
 *   <li>{@link #PPRB_QUEUED}     — sync-доставка в PGW не удалась, УРД поставлен
 *       в outbox для background-retry с тем же requestId. Документ <b>не</b> в
 *       терминальной ошибке — гарант-доставка ещё работает.</li>
 *   <li>{@link #PPRB_STARTED}    — PGW принял УРД (sync ACK успешный, или worker
 *       успешно доставил из outbox). Ждём async-квитанцию.</li>
 *   <li>{@link #PPRB_PROCESSING} — PGW квитанция с промежуточным статусом.</li>
 *   <li>{@link #PPRB_EXECUTED}   — финальная успешная квитанция от PGW.</li>
 *   <li>{@link #PPRB_FAILED}     — терминальная ошибка: либо валидация /
 *       enrichment / pacs.008 build упали на нашей стороне; либо outbox исчерпал
 *       все попытки (GIVEUP); либо PGW прислал финальный ERROR в callback.</li>
 * </ul>
 *
 * <p><b>Типичные траектории</b>:</p>
 * <pre>
 *   happy path:        GET → STARTED → EXECUTED
 *   outbox retry:      GET → QUEUED → STARTED → EXECUTED
 *   outbox giveup:     GET → QUEUED → FAILED
 *   pgw business err:  GET → STARTED → FAILED
 *   validation fail:   GET → FAILED
 * </pre>
 */
public enum ExecutionStatus {
    PPRB_GET,
    PPRB_QUEUED,
    PPRB_STARTED,
    PPRB_PROCESSING,
    PPRB_EXECUTED,
    PPRB_FAILED
}
