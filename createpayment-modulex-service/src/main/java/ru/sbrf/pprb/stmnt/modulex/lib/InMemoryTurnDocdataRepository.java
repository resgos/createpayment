package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory мок DataSpace для {@code turn_docdata}.
 * Ключ — {@code ccOperationId}. Регистрируется как {@code @Bean} в {@code AppConfig}.
 */
@Slf4j
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
