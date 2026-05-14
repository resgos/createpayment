package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory мок DataSpace для {@code turn_docdata}.
 * Ключ — {@code ccOperationId}. Подключи реальную DataSpace-имплементацию через
 * свой {@code @Component @Primary} — она вытеснит этот fallback.
 */
@Slf4j
@Component
public class InMemoryTurnDocdataRepository implements TurnDocdataRepository {

    private final Map<String, TurnDocdataDraft> store = new ConcurrentHashMap<>();

    @Override
    public void save(TurnDocdataDraft draft) {
        if (draft == null || draft.getCcOperationId() == null) return;
        store.put(draft.getCcOperationId(), draft);
        log.debug("turn_docdata save: ccOperationId={}, ccTransactionId={}, ccRqUId={}, ccBchOperationId={}",
                draft.getCcOperationId(), draft.getCcTransactionId(),
                draft.getCcRqUId(), draft.getCcBchOperationId());
    }

    @Override
    public Optional<TurnDocdataDraft> findByOperationId(String ccOperationId) {
        return Optional.ofNullable(store.get(ccOperationId));
    }
}
