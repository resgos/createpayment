package ru.sbrf.pprb.stmnt.modulex.integration.pgw;

import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.UPDDTO;

public interface PgwClient {

    /**
     * Отправляет УРД в PGW. С гарант-доставкой (ретраи по спеке, мин. 30 сек).
     *
     * @param requestId транспортный ID, идёт в query — синхронно вернётся в correlationId.
     * @param updDTO    контейнер УРД с pacs.008 внутри originalMessage.
     * @return синхронный ответ PGW.
     * @throws IllegalStateException если PGW недоступен после всех попыток.
     */
    ApiResult transferUpd(String requestId, UPDDTO updDTO);
}
