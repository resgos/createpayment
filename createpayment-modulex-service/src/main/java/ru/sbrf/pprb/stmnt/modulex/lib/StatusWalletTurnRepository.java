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
     * {@code forceResend=true}. Сейчас блокируем повтор только на
     * {@code PPRB_EXECUTED} — для {@code FAILED} retry свободный.
     *
     * <p><b>Race</b>: read-then-act без распределённого lock. При
     * параллельных вызовах с одинаковым {@code ccBchOperationId} оба могут
     * пройти pre-check одновременно — PGW dedupe по {@code requestId} спасёт
     * на их стороне (у каждой попытки свой requestId), но в нашем DataSpace
     * получим две независимые серии статусов. Для bullet-proof нужен
     * mutex/advisory lock на bchOpId.</p>
     */
    Optional<String> findLastFinalStatus(String ccWalletTurnObjectId);
}
