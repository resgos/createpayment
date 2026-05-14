package ru.sbrf.pprb.stmnt.modulex.lib;

/**
 * Контракт сохранения квитанции в status_WalletTurn.
 * Реализация поверх DataSpace появится отдельно — сейчас работает logging-стаб.
 */
public interface StatusWalletTurnRepository {
    void upsertStatus(StatusWalletTurnUpdate update);
}
