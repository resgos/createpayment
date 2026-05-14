package ru.sbrf.pprb.stmnt.modulex.lib.dataspace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurn;
import ru.sbrf.pprb.stmnt.modulex.lib.WalletTurnRepository;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;

import java.util.Optional;

/**
 * Шаблон DataSpace-имплементации {@link WalletTurnRepository}.
 *
 * <p><b>Этот класс не активен.</b> {@code @Primary} снят сознательно — пока
 * заглушка кидает {@link UnsupportedOperationException}, в DI побеждает
 * in-memory bean из {@code AppConfig}.</p>
 *
 * <p>Что нужно сделать:</p>
 * <ol>
 *   <li>Регенерировать {@code stmnt-model-sdk} (или {@code modulex-model-sdk})
 *       из актуального {@code modulex.xml} — чтобы появились getter'ы новых
 *       полей ({@code getCcBchOperationId}, {@code getCcTxId},
 *       {@code getCcOwnerDt/Kt} и т.д.).</li>
 *   <li>Реализовать {@link #findByBchOperationId(String)} через
 *       {@code dsApi.searchWalletTurn(g -> g.ccBchOperationId().equal(...))}
 *       — точная сигнатура graph-DSL зависит от версии SDK.</li>
 *   <li>Поставить обратно {@code @Primary}.</li>
 * </ol>
 */
@Slf4j
@Component
public class DataSpaceWalletTurnRepository implements WalletTurnRepository {

    private final DataSpaceApi dsApi;

    public DataSpaceWalletTurnRepository(DataSpaceApi dsApi) {
        this.dsApi = dsApi;
    }

    @Override
    public Optional<WalletTurn> findByBchOperationId(String ccBchOperationId) {
        throw new UnsupportedOperationException(
                "DataSpaceWalletTurnRepository не реализован для текущей версии "
                        + "stmnt-model-sdk. Регенерируй SDK из modulex.xml и подставь точный "
                        + "graph-DSL — структура остаётся та же, что в комментарии класса.");
    }
}
