package ru.sbrf.pprb.stmnt.modulex.lib;

import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;

import java.util.Optional;

/**
 * Сохранение и выборка {@code turn_docdata}.
 * Через {@code ccOperationId} находим запись по PGW-квитанции и достаём
 * исходные идентификаторы для callback'а в адрес инициатора.
 */
public interface TurnDocdataRepository {
    void save(TurnDocdataDraft draft);
    Optional<TurnDocdataDraft> findByOperationId(String ccOperationId);
}
