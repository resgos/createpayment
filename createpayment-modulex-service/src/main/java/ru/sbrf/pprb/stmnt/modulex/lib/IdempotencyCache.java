package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory кэш идемпотентности приёма ResponseTicket от PGW.
 *
 * <p>Контракт PGW: ПФ-инициатор обязан вернуть на дубль ResponseTicket с тем же
 * {@code idempotencyKey} <b>статус первого</b> полученного документа, без повторной
 * обработки бизнес-логики.</p>
 *
 * <p>Реализация: {@link ConcurrentHashMap} с lazy-TTL — на каждом {@code get}
 * проверяем срок жизни записи. Эвикшен не строго точный, но потенциальные
 * "просрочки" безвредны — PGW ретраит в окне минут, мы держим час.</p>
 *
 * <p><b>Ограничение</b>: при рестарте пода кэш теряется. На практике PGW
 * ретраит ResponseTicket в течение нескольких минут после первого ответа,
 * вероятность совпадения retry-окна и рестарта мала. Для bullet-proof решения
 * нужна персистенция в DataSpace (отдельная сущность с уник-индексом по
 * {@code idempotencyKey}) — следующая итерация после корп-обновления модели.</p>
 *
 * <p>Кэшируем <b>только успешные ApiResult</b> (status=SUCCESS). ERROR-результат
 * означает "обработка не завершена, PGW пусть повторит" → сохранять его как
 * idempotent-ответ нельзя.</p>
 */
@Slf4j
@Component
public class IdempotencyCache {

    /** TTL записи в кэше. PGW обычно ретраит в первые 5-10 минут. */
    private static final long DEFAULT_TTL_MS = 60L * 60L * 1000L; // 1 час
    /** Максимум записей — защита от утечки. При переполнении старые вытесняются. */
    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlMs;

    public IdempotencyCache() {
        this(DEFAULT_TTL_MS);
    }

    /** Для тестов — кастомный TTL. */
    public IdempotencyCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * Возвращает закешированный ApiResult, если для этого idempotencyKey
     * мы уже отвечали ранее и запись не истекла.
     */
    public Optional<ApiResult> get(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        Entry e = store.get(idempotencyKey);
        if (e == null) return Optional.empty();
        if (isExpired(e)) {
            store.remove(idempotencyKey, e);
            return Optional.empty();
        }
        return Optional.of(e.result);
    }

    /**
     * Сохраняет результат — только если он успешный. ERROR не сохраняем,
     * чтобы PGW мог повторить запрос и получить уже валидный ответ.
     */
    public void put(String idempotencyKey, ApiResult result) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || result == null) return;
        if (!"SUCCESS".equals(result.getStatus())) {
            log.debug("Skip caching non-success ApiResult: idempotencyKey={}, status={}",
                    idempotencyKey, result.getStatus());
            return;
        }
        if (store.size() >= MAX_ENTRIES) {
            evictOldest();
        }
        store.put(idempotencyKey, new Entry(result, System.currentTimeMillis() + ttlMs));
        log.debug("Cached ResponseTicket result: idempotencyKey={}, status={}",
                idempotencyKey, result.getStatus());
    }

    /** Принудительно очищает кэш (для тестов). */
    public void clear() {
        store.clear();
    }

    /** Размер кэша (для тестов/метрик). */
    public int size() {
        return store.size();
    }

    private boolean isExpired(Entry e) {
        return System.currentTimeMillis() > e.expiresAt;
    }

    private void evictOldest() {
        store.entrySet().stream()
                .min((a, b) -> Long.compare(a.getValue().expiresAt, b.getValue().expiresAt))
                .ifPresent(e -> store.remove(e.getKey()));
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
