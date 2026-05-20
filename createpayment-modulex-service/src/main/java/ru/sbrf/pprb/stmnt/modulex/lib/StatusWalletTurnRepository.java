package ru.sbrf.pprb.stmnt.modulex.lib;

import java.util.Optional;

/**
 * Контракт работы со status_WalletTurn:
 * <ul>
 *   <li>{@link #upsertStatus(StatusWalletTurnUpdate)} — upsert по уник-ключу
 *       {@code (ccWalletTurnObjectId, ccStatus)}.</li>
 *   <li>{@link #findFirstByOperationId(String)} — найти первую строку по
 *       {@code ccOperationId} для резолва ccWalletTurnObjectId / ccTransactionId
 *       в callback-flow (PGW знает только updUID = ccOperationId).</li>
 * </ul>
 */
public interface StatusWalletTurnRepository {

    void upsertStatus(StatusWalletTurnUpdate update);

    Optional<StatusWalletTurnView> findFirstByOperationId(String ccOperationId);

    /**
     * Поиск последнего терминального статуса по blockchain-операции.
     * Возвращает {@code PPRB_EXECUTED} (приоритет) или {@code PPRB_FAILED},
     * если для walletTurn уже есть финальная строка. Используется для
     * идемпотентности при повторных вызовах createPayment без
     * {@code forceResend=true}.
     */
    Optional<String> findLastFinalStatus(String ccWalletTurnObjectId);
}
