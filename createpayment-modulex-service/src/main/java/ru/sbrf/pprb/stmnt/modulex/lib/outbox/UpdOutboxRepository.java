package ru.sbrf.pprb.stmnt.modulex.lib.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Хранилище upd_outbox для гарант-доставки УРД в PGW.
 */
public interface UpdOutboxRepository {

    /** Сохранить новую запись (или обновить, если уже есть по ccRequestId). */
    void save(UpdOutboxEntry entry);

    /** Найти по requestId — стабильный ключ УРД. */
    Optional<UpdOutboxEntry> findByRequestId(String requestId);

    /** Достать PENDING-записи, готовые к попытке (ccNextRetryAt &le; now), лимит. */
    List<UpdOutboxEntry> findPending(LocalDateTime now, int limit);
}
