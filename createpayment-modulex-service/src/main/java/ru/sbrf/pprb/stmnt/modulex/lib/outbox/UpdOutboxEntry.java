package ru.sbrf.pprb.stmnt.modulex.lib.outbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Запись в upd_outbox для гарант-доставки УРД в PGW.
 *
 * <p>Логика жизненного цикла:</p>
 * <ol>
 *   <li>Sync-attempt: попытка отправить УРД в PGW синхронно. Успех → запись в
 *       outbox НЕ создаётся, продолжаем как раньше.</li>
 *   <li>Если sync-attempt упал — создаём запись со статусом {@code PENDING} и
 *       {@code ccNextRetryAt = now + retryDelay}.</li>
 *   <li>Background-воркер каждые N секунд берёт {@code PENDING} с
 *       {@code ccNextRetryAt &le; now}, пытается отправить, на каждой неудаче
 *       инкрементит {@code ccAttempts} и сдвигает {@code ccNextRetryAt}.</li>
 *   <li>На {@code ccAttempts &ge; ccMaxAttempts} → {@code GIVEUP}.</li>
 *   <li>На успехе → {@code SENT}.</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdOutboxEntry {

    /** requestId УРД — стабилен между попытками, по нему PGW делает дедуп. */
    private String ccRequestId;

    /** updUID — для трассировки. */
    private String ccUpdUID;

    /** Сериализованный UPDDTO (JSON). */
    private String ccPayload;

    /** PENDING / SENT / GIVEUP. */
    private String ccStatus;

    private int ccAttempts;
    private int ccMaxAttempts;

    private LocalDateTime ccNextRetryAt;
    private String ccLastError;

    private LocalDateTime ccCreatedAt;
    private LocalDateTime sysLastChangeDate;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_GIVEUP = "GIVEUP";
}
