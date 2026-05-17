package ru.sbrf.pprb.stmnt.modulex.lib;

import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResultStatus;

/**
 * Маппинг resultStatus + statusInfo.code из ответа PGW → значение ccStatus в status_WalletTurn.
 *
 * <p><b>Контракт PGW по statusInfo.code</b>:</p>
 * <pre>
 *   SUCCESS + code 300 / 301 / 315  → PPRB_EXECUTED   (финальный успех)
 *   SUCCESS + code 202..299         → PPRB_PROCESSING (промежуточный)
 *   SUCCESS без кода / иной код     → PPRB_PROCESSING (ждём финальную квитанцию)
 *   ERROR   + code 100..199         → PPRB_FAILED
 *   ERROR   без кода                → PPRB_FAILED
 *   иное                            → UNKNOWN
 * </pre>
 *
 * <p><b>Правило для финала</b>: только явный код {@code 300} (либо 301/315)
 * переводит платёж в {@code PPRB_EXECUTED}. Любой другой SUCCESS-ticket
 * (включая случай с пустым {@code statusInfo}) трактуется как
 * {@code PPRB_PROCESSING} — ждём следующую квитанцию с финальным кодом.
 * Так избегаем преждевременного зачисления при hold-операциях, когда
 * PGW не присылает явный финальный код.</p>
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
                // Любой другой SUCCESS-ticket (включая null/пустой statusInfo)
                // трактуем как PROCESSING — ждём явный финальный код 300/301/315.
                return PROCESSING;
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
