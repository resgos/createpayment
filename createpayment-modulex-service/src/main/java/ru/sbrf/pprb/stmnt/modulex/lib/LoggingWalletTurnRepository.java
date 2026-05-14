package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurn;

import java.util.Optional;

/**
 * Заглушка — возвращает {@link Optional#empty()} и пишет warning.
 * Будет вытеснена реальной DataSpace-имплементацией через
 * {@link ConditionalOnMissingBean}.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(WalletTurnRepository.class)
public class LoggingWalletTurnRepository implements WalletTurnRepository {

    @Override
    public Optional<WalletTurn> findByBchOperationId(String ccBchOperationId) {
        log.warn("WalletTurn lookup is not wired to DataSpace yet — returning empty for ccBchOperationId={}",
                ccBchOperationId);
        return Optional.empty();
    }
}
