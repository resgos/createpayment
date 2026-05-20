package ru.sbrf.pprb.stmnt.modulex.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфиг интеграции с PGW.
 *
 * <p><b>Гарантированная доставка</b> (контракт PGW рекомендует 5-10 попыток,
 * интервал 3-5 минут, суммарно ≥1 минута):</p>
 * <ul>
 *   <li>{@code maxAttempts=5}, {@code retryDelayMs=180000} (3 мин) →
 *       при сбое: 5 × 3 мин = до 15 минут общего ретрая.</li>
 * </ul>
 *
 * <p>Sync-цикл в {@code PgwClientImpl} делает ровно ОДНУ попытку — на сбое
 * УРД кладётся в {@code upd_outbox} со status=PENDING, attempts=1, и затем
 * {@code PgwOutboxWorker} (@Scheduled) переотправляет в фоне с тем же
 * {@code requestId}. Клиентский Tomcat-тред не блокируется ретраями.</p>
 *
 * <p>{@code maxAttempts} и {@code retryDelayMs} применяются background-воркером.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "pgw")
public class PgwProperties {

    private boolean enabled = true;
    private String url = "https://ingress-pgw-4g-ift.https.dev-sh5.ocp-geo.delta.sbrf.ru";
    private String transferPath = "/upd/transfer";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 30000;

    /**
     * Гарант-доставка: количество попыток.
     * Контракт PGW: 5–10. Снижай в проде, если важно, чтобы клиент
     * не висел дольше своего timeout.
     */
    private int maxAttempts = 5;

    /**
     * Гарант-доставка: задержка между попытками, мс.
     * Контракт PGW: 180000–300000 (3–5 минут).
     * <p>Минимальный допустимый интервал от первой до последней — 1 минута.</p>
     */
    private long retryDelayMs = 180_000;

    /**
     * Срок ожидания исполнения УРД на стороне executor-PF, минут.
     * Уходит в {@code msgAttributes.executionDeadline} как
     * {@code now() + executionDeadlineMinutes} в формате
     * {@code yyyy-MM-dd'T'HH:mm:ss.SSS}.
     *
     * <p>Без этого атрибута PGW отвечает ошибкой 102 «Срок ожидания исполнения
     * ПФ не задан, ожидание невозможно».</p>
     */
    private int executionDeadlineMinutes = 1;
}
