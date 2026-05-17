package ru.sbrf.pprb.stmnt.modulex.lib.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Gauge-метрики upd_outbox для Prometheus/Actuator.
 *
 * <p>Метрики:</p>
 * <ul>
 *   <li>{@code outbox_size{status="PENDING"}} — записей в очереди на ретрай</li>
 *   <li>{@code outbox_size{status="SENT"}} — успешно отправленных (накопительно)</li>
 *   <li>{@code outbox_size{status="GIVEUP"}} — отказались переотправлять</li>
 * </ul>
 *
 * <p>Обновляются по {@link Scheduled} раз в 30 секунд — чтобы не дёргать
 * DataSpace на каждый Prometheus scrape.</p>
 */
@Slf4j
@Component
public class OutboxMetrics {

    private final UpdOutboxRepository repo;

    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong giveupCount = new AtomicLong();

    public OutboxMetrics(UpdOutboxRepository repo, MeterRegistry registry) {
        this.repo = repo;
        registry.gauge("outbox_size", io.micrometer.core.instrument.Tags.of("status", "PENDING"), pendingCount);
        registry.gauge("outbox_size", io.micrometer.core.instrument.Tags.of("status", "SENT"), sentCount);
        registry.gauge("outbox_size", io.micrometer.core.instrument.Tags.of("status", "GIVEUP"), giveupCount);
    }

    /** Раз в 30 секунд обновляем gauge — отдельно от scrape Prometheus-а. */
    @Scheduled(fixedDelayString = "${pgw.outbox.metrics-refresh-ms:30000}",
            initialDelayString = "${pgw.outbox.initial-delay-ms:30000}")
    public void refresh() {
        try {
            pendingCount.set(repo.countByStatus(UpdOutboxEntry.STATUS_PENDING));
            sentCount.set(repo.countByStatus(UpdOutboxEntry.STATUS_SENT));
            giveupCount.set(repo.countByStatus(UpdOutboxEntry.STATUS_GIVEUP));
        } catch (Exception e) {
            log.warn("OutboxMetrics refresh failed: {}", e.getMessage());
        }
    }
}
