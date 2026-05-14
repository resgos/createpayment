package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurn;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory мок DataSpace для {@code WalletTurn}.
 * Регистрируется как {@code @Bean} в {@code AppConfig}.
 * Можно засеивать через {@link #put(WalletTurn)}.
 */
@Slf4j
public class InMemoryWalletTurnRepository implements WalletTurnRepository {

    private final Map<String, WalletTurn> store = new ConcurrentHashMap<>();

    public void put(WalletTurn wt) {
        if (wt != null && wt.getCcBchOperationId() != null) {
            store.put(wt.getCcBchOperationId(), wt);
            log.debug("InMemoryWalletTurnRepository.put: ccBchOperationId={}", wt.getCcBchOperationId());
        }
    }

    @Override
    public Optional<WalletTurn> findByBchOperationId(String ccBchOperationId) {
        Optional<WalletTurn> found = Optional.ofNullable(store.get(ccBchOperationId));
        if (found.isEmpty()) {
            log.debug("InMemoryWalletTurnRepository miss for ccBchOperationId={}", ccBchOperationId);
        }
        return found;
    }
}
