package ru.sbrf.pprb.stmnt.modulex.lib;

import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;

import java.util.Optional;

/**
 * Хранилище ключей идемпотентности приёма ResponseTicket от PGW.
 *
 * <p>Контракт: {@link #find} возвращает ApiResult первой обработки,
 * если этот {@code idempotencyKey} уже был обработан. {@link #save}
 * сохраняет результат первой обработки (только успешный — ERROR не
 * сохраняем, чтобы PGW мог повторить).</p>
 *
 * <p>Имплементации:</p>
 * <ul>
 *   <li>{@link InMemoryIdempotencyStore} — fallback для local-run / тестов.</li>
 *   <li>{@code DataSpaceIdempotencyStore} — продовая, через сущность
 *       {@code responseTicket_idempotency}.</li>
 * </ul>
 */
public interface IdempotencyStore {

    /** Поиск кешированного результата по ключу. */
    Optional<ApiResult> find(String idempotencyKey);

    /** Сохранение результата первой обработки (только SUCCESS). */
    void save(String idempotencyKey, String correlationId, String updUID, ApiResult result);
}
