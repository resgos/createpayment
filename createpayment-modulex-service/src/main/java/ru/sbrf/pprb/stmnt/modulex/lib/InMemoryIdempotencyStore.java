package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fallback {@link IdempotencyStore}. Используется в local-run / тестах,
 * когда DataSpace-сущность {@code responseTicket_idempotency} недоступна.
 *
 * <p>Регистрируется как {@code @Bean} в {@code AppConfig} — реальная
 * DataSpace-имплементация подключается своим {@code @Primary @Component}.</p>
 */
@Slf4j
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, ApiResult> store = new ConcurrentHashMap<>();

    @Override
    public Optional<ApiResult> find(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return Optional.ofNullable(store.get(idempotencyKey));
    }

    @Override
    public void save(String idempotencyKey, String correlationId, String updUID, ApiResult result) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || result == null) return;
        if (!"SUCCESS".equals(result.getStatus())) {
            log.debug("Skip caching non-success ApiResult: idempotencyKey={}, status={}",
                    idempotencyKey, result.getStatus());
            return;
        }
        store.put(idempotencyKey, result);
        log.debug("In-memory idempotency saved: idempotencyKey={}, updUID={}", idempotencyKey, updUID);
    }

    /** Для тестов / метрик. */
    public int size() {
        return store.size();
    }

    public void clear() {
        store.clear();
    }
}
