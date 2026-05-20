package ru.sbrf.pprb.stmnt.modulex.integration.pgw;

import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.UPDDTO;

public interface PgwClient {

    /**
     * Отправляет УРД в PGW синхронно (одна попытка). Сбой sync-доставки
     * <b>не бросает исключения</b> — УРД кладётся в outbox для background-retry,
     * метод возвращает {@link ApiResult} с одним из значений {@code status}:
     *
     * <ul>
     *   <li>{@code SUCCESS} — PGW принял УРД, выдал sync-квитанцию.</li>
     *   <li>{@code QUEUED}  — sync упал, но УРД сохранён в {@code upd_outbox}
     *       для background-retry с тем же {@code requestId}. Гарант-доставка работает.</li>
     *   <li>{@code ERROR}   — sync упал И outbox-enqueue тоже упал. Терминал.</li>
     *   <li>{@code SKIPPED} — PGW отключён через {@code pgw.enabled=false}.</li>
     * </ul>
     *
     * <p>Background-retry обслуживается {@link ru.sbrf.pprb.stmnt.modulex.lib.outbox.PgwOutboxWorker}
     * (@Scheduled). По спеке PGW: до 5–10 попыток с интервалом 3–5 мин, общий
     * срок ≥1 минуты. Параметры в {@link ru.sbrf.pprb.stmnt.modulex.config.PgwProperties}.</p>
     *
     * @param requestId транспортный ID УРД (стабилен между попытками, по нему
     *                  PGW дедуплицирует на своей стороне).
     * @param updDTO    контейнер УРД с pacs.008 внутри originalMessage.
     * @return синхронный {@link ApiResult} — НИКОГДА не {@code null}.
     */
    ApiResult transferUpd(String requestId, UPDDTO updDTO);
}
