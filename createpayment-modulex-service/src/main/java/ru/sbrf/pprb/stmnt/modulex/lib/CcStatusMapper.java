package ru.sbrf.pprb.stmnt.modulex.lib;

import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResultStatus;

/**
 * Маппинг resultStatus + statusInfo.code из ответа PGW → значение ccStatus в status_WalletTurn.
 *
 * <pre>
 *   SUCCESS + code 300 / 301 / 315  → PPRB_EXECUTED
 *   SUCCESS + code 202..299         → PPRB_PROCESSING
 *   ERROR   + code 100..199         → PPRB_FAILED
 *   иное                            → UNKNOWN
 * </pre>
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
        int n = parseCode(code);
        switch (status) {
            case SUCCESS:
                if (n == 300 || n == 301 || n == 315) return EXECUTED;
                if (n >= 202 && n <= 299) return PROCESSING;
                // SUCCESS без явного кода или с неизвестным успешным кодом —
                // считаем финальным успешным состоянием.
                return n == -1 ? EXECUTED : UNKNOWN;
            case ERROR:
                if (n >= 100 && n <= 199) return FAILED;
                return n == -1 ? FAILED : UNKNOWN;
            default:
                return UNKNOWN;
        }
    }

    private static int parseCode(String code) {
        if (code == null || code.isBlank()) return -1;
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
