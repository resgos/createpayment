package ru.sbrf.pprb.stmnt.modulex.lib;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Двухуровневый кэш идемпотентности приёма ResponseTicket:
 *
 * <ul>
 *   <li><b>L1 (in-memory)</b>: {@link ConcurrentHashMap} с TTL. Быстрая защита от
 *       параллельных PGW-ретраев в окне минут — без round-trip в DataSpace.</li>
 *   <li><b>L2 (persistent)</b>: {@link IdempotencyStore} — переживает рестарт пода.</li>
 * </ul>
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>{@link #find} — сперва L1, при miss → L2 → если найдено, прогреваем L1.</li>
 *   <li>{@link #save} — пишем и в L1, и в L2 (только SUCCESS).</li>
 * </ol>
 *
 * <p>Метрики (Micrometer):</p>
 * <ul>
 *   <li>{@code idempotency_cache_l1_hits} / {@code l1_misses} / {@code l2_hits}</li>
 *   <li>{@code idempotency_cache_saves} / {@code skipped_non_success}</li>
 *   <li>{@code idempotency_cache_l1_size} (gauge)</li>
 * </ul>
 */
@Slf4j
@Component
public class IdempotencyCache {

    /** TTL записи в L1. PGW обычно ретраит в первые 5-10 минут. */
    private static final long L1_TTL_MS = 60L * 60L * 1000L; // 1 час
    /** Максимум записей в L1 — защита от утечки. */
    private static final int L1_MAX_ENTRIES = 10_000;

    private final Map<String, Entry> l1 = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final IdempotencyStore store;

    // Метрики
    private final Counter l1Hits;
    private final Counter l1Misses;
    private final Counter l2Hits;
    private final Counter saves;
    private final Counter skippedNonSuccess;
    private final AtomicLong l1Size = new AtomicLong();

    @Autowired
    public IdempotencyCache(IdempotencyStore store, MeterRegistry registry) {
        this(store, registry, L1_TTL_MS);
    }

    /** Для тестов — кастомный TTL. */
    public IdempotencyCache(IdempotencyStore store, MeterRegistry registry, long ttlMs) {
        this.store = store;
        this.ttlMs = ttlMs;
        this.l1Hits = Counter.builder("idempotency_cache_hits")
                .tag("level", "l1").register(registry);
        this.l1Misses = Counter.builder("idempotency_cache_misses")
                .tag("level", "l1").register(registry);
        this.l2Hits = Counter.builder("idempotency_cache_hits")
                .tag("level", "l2").register(registry);
        this.saves = Counter.builder("idempotency_cache_saves").register(registry);
        this.skippedNonSuccess = Counter.builder("idempotency_cache_skipped_non_success")
                .register(registry);
        registry.gauge("idempotency_cache_l1_size", l1Size);
    }

    public Optional<ApiResult> find(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        // L1
        Entry e = l1.get(idempotencyKey);
        if (e != null && !isExpired(e)) {
            l1Hits.increment();
            return Optional.of(e.result);
        }
        if (e != null) {
            l1.remove(idempotencyKey, e);
            l1Size.set(l1.size());
        }
        // L2
        Optional<ApiResult> persisted = store.find(idempotencyKey);
        if (persisted.isPresent()) {
            l2Hits.increment();
            warmL1(idempotencyKey, persisted.get());
            return persisted;
        }
        l1Misses.increment();
        return Optional.empty();
    }

    public void save(String idempotencyKey, String correlationId, String updUID, ApiResult result) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || result == null) return;
        if (!"SUCCESS".equals(result.getStatus())) {
            skippedNonSuccess.increment();
            log.debug("Skip caching non-success ApiResult: idempotencyKey={}, status={}",
                    idempotencyKey, result.getStatus());
            return;
        }
        warmL1(idempotencyKey, result);
        store.save(idempotencyKey, correlationId, updUID, result);
        saves.increment();
    }

    /** Размер L1 — для метрик. */
    public int size() {
        return l1.size();
    }

    private void warmL1(String key, ApiResult result) {
        if (l1.size() >= L1_MAX_ENTRIES) {
            evictOldest();
        }
        l1.put(key, new Entry(result, System.currentTimeMillis() + ttlMs));
        l1Size.set(l1.size());
    }

    private boolean isExpired(Entry e) {
        return System.currentTimeMillis() > e.expiresAt;
    }

    private void evictOldest() {
        l1.entrySet().stream()
                .min((a, b) -> Long.compare(a.getValue().expiresAt, b.getValue().expiresAt))
                .ifPresent(e -> l1.remove(e.getKey()));
        l1Size.set(l1.size());
    }

    private static class Entry {
        final ApiResult result;
        final long expiresAt;

        Entry(ApiResult result, long expiresAt) {
            this.result = result;
            this.expiresAt = expiresAt;
        }
    }
}
