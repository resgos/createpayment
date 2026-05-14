package ru.sbrf.pprb.stmnt.modulex.api.dto;

/**
 * Итог пайплайна по одной walletTurn (синхронный — после отправки в PGW).
 * Финальный статус ({@code PPRB_EXECUTED} vs промежуточный {@code PPRB_PROCESSING})
 * приходит асинхронно через {@code /upd/response/execute}.
 */
public enum ExecutionStatus {
    /** Пайплайн собрал pacs.008 и отправил в PGW — приняло. */
    PPRB_EXECUTED,
    /** Любой сбой на пути: lookup, обогащение, сборка XML, PGW. */
    PPRB_FAILED
}
