package ru.sbrf.pprb.stmnt.modulex.api.dto;

/**
 * Жизненный цикл итога по walletTurn:
 * <ul>
 *   <li>{@link #PPRB_PROCESSING} — синхронно после {@code transferUpd} в PGW;
 *       финального ответа от PGW ещё нет.</li>
 *   <li>{@link #PPRB_EXECUTED}   — пришла успешная квитанция от PGW (коды 300/301/315).</li>
 *   <li>{@link #PPRB_FAILED}     — сбой пайплайна или ошибка от PGW (коды 100-199).</li>
 * </ul>
 */
public enum ExecutionStatus {
    PPRB_PROCESSING,
    PPRB_EXECUTED,
    PPRB_FAILED
}
