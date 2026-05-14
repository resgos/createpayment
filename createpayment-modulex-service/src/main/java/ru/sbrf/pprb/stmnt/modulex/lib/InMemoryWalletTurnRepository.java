package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurn;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory мок DataSpace для {@code WalletTurn}.
 * Можно предварительно засеивать через {@link #put(WalletTurn)} (полезно в тестах
 * и локальном запуске). По умолчанию таблица пустая.
 *
 * <p>Подключи реальную DataSpace-имплементацию через свой {@code @Component @Primary} —
 * она вытеснит этот fallback.</p>
 */
@Slf4j
@Component
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
