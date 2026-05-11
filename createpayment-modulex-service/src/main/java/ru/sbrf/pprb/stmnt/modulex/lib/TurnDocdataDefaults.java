package ru.sbrf.pprb.stmnt.modulex.lib;

import java.math.BigDecimal;

/**
 * Константы-заглушки для TurnDocdata, пока поставщик не передаёт реальные значения.
 */
public final class TurnDocdataDefaults {

    public static final String PAY_STATUS_DRAFT = "DRAFT";
    public static final String DT_DEBIT = "1";
    public static final BigDecimal TYPE_OPER_CURRENT_DAY = BigDecimal.ZERO;
    public static final String TYPE_DOC_PP = "01";
    public static final BigDecimal RATE_DEFAULT = BigDecimal.ONE;
    public static final String CURRENCY_RUB = "810";
    public static final BigDecimal PRIORITY_DEFAULT = new BigDecimal("5");
    public static final String SYSTEM_ID = "stmnt-giganetwork";

    private TurnDocdataDefaults() {
    }
}
