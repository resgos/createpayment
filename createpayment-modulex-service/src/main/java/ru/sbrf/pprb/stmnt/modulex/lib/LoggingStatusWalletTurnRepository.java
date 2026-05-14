package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Заглушка-реализация: ничего не пишет, только логирует.
 * Подключается, если нет более «настоящей» имплементации (например, поверх DataSpace).
 */
@Slf4j
@Component
@ConditionalOnMissingBean(StatusWalletTurnRepository.class)
public class LoggingStatusWalletTurnRepository implements StatusWalletTurnRepository {

    @Override
    public void upsertStatus(StatusWalletTurnUpdate u) {
        log.info("status_WalletTurn upsert (placeholder): operationId={}, status={}, code={}, desc={}, rqUID={}, idempotencyKey={}",
                u.getCcOperationId(), u.getCcStatus(), u.getCcStatusCode(),
                u.getCcStatusDesc(), u.getCcRqUId(), u.getCcIdempotencyKey());
    }
}
