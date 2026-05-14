package ru.sbrf.pprb.stmnt.modulex.lib;

import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurn;

import java.util.Optional;

/**
 * Выборка полной walletTurn по идентификатору операции в blockchain.
 * Реальная реализация поверх DataSpace появится отдельно;
 * сейчас работает {@link LoggingWalletTurnRepository}-стаб.
 */
public interface WalletTurnRepository {
    Optional<WalletTurn> findByBchOperationId(String ccBchOperationId);
}
