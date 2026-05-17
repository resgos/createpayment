package ru.sbrf.pprb.stmnt.modulex.lib.outbox;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory fallback {@link UpdOutboxRepository}. Используется в local-run / тестах.
 */
@Slf4j
public class InMemoryUpdOutboxRepository implements UpdOutboxRepository {

    private final Map<String, UpdOutboxEntry> store = new ConcurrentHashMap<>();

    @Override
    public void save(UpdOutboxEntry entry) {
        if (entry == null || entry.getCcRequestId() == null) return;
        store.put(entry.getCcRequestId(), entry);
    }

    @Override
    public Optional<UpdOutboxEntry> findByRequestId(String requestId) {
        if (requestId == null) return Optional.empty();
        return Optional.ofNullable(store.get(requestId));
    }

    @Override
    public List<UpdOutboxEntry> findPending(LocalDateTime now, int limit) {
        return store.values().stream()
                .filter(e -> UpdOutboxEntry.STATUS_PENDING.equals(e.getCcStatus()))
                .filter(e -> e.getCcNextRetryAt() == null || !e.getCcNextRetryAt().isAfter(now))
                .sorted(Comparator.comparing(UpdOutboxEntry::getCcNextRetryAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .limit(limit)
                .collect(Collectors.toList());
    }
}
