package ru.sbrf.pprb.stmnt.modulex.lib;

import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResultStatus;

/**
 * Маппинг {@code resultStatus} из ответа PGW → {@code ccStatus} в status_WalletTurn.
 *
 * <p><b>Простая логика</b>: смотрим только на {@code resultStatus}, числовые коды
 * (1xx/2xx/3xx) игнорируем. Любой {@code SUCCESS} → финальное состояние,
 * любой {@code ERROR} → отказ.</p>
 *
 * <pre>
 *   SUCCESS  → PPRB_EXECUTED
 *   ERROR    → PPRB_FAILED
 *   null/иное → UNKNOWN
 * </pre>
 *
 * <p>Параметр {@code code} оставлен в сигнатуре для логирования/трассировки —
 * по нему не маппимся.</p>
 */
public final class CcStatusMapper {

    public static final String GET = "PPRB_GET";
    public static final String STARTED = "PPRB_STARTED";
    public static final String EXECUTED = "PPRB_EXECUTED";
    public static final String PROCESSING = "PPRB_PROCESSING";
    public static final String FAILED = "PPRB_FAILED";
    public static final String UNKNOWN = "UNKNOWN";

    private CcStatusMapper() {
    }

    public static String map(ResultStatus status, String code) {
        if (status == null) {
            return UNKNOWN;
        }
        switch (status) {
            case SUCCESS: return EXECUTED;
            case ERROR:   return FAILED;
            default:      return UNKNOWN;
        }
    }
}
