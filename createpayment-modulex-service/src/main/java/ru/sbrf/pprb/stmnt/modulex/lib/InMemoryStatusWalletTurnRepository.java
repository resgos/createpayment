package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory мок DataSpace для {@code status_WalletTurn}.
 * Уник-ключ {@code (ccWalletTurnObjectId, ccStatus)} — повторный upsert
 * с теми же ключами просто перезаписывает запись.
 *
 * <p>Регистрируется как {@code @Bean} в {@link ru.sbrf.pprb.stmnt.modulex.config.AppConfig} —
 * не @Component, чтобы избежать "tap dance" со Spring conditional-ами и corp ComponentScan.
 * Реальная DataSpace-имплементация подключается своим {@code @Component @Primary} (см. README).</p>
 */
@Slf4j
public class InMemoryStatusWalletTurnRepository implements StatusWalletTurnRepository {

    private final Map<String, StatusWalletTurnUpdate> store = new ConcurrentHashMap<>();

    @Override
    public void upsertStatus(StatusWalletTurnUpdate u) {
        String key = keyOf(u.getCcWalletTurnObjectId(), u.getCcStatus());
        store.put(key, u);
        log.debug("status_WalletTurn upsert: walletTurnObjectId={}, status={}, code={}, desc={}",
                u.getCcWalletTurnObjectId(), u.getCcStatus(), u.getCcStatusCode(), u.getCcStatusDesc());
    }

    public Collection<StatusWalletTurnUpdate> all() {
        return store.values();
    }

    public StatusWalletTurnUpdate find(String walletTurnObjectId, String status) {
        return store.get(keyOf(walletTurnObjectId, status));
    }

    private static String keyOf(String id, String status) {
        return (id == null ? "<null>" : id) + "|" + (status == null ? "<null>" : status);
    }
}
