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
 * <p><b>Не активен</b> — нет {@code @Primary}, в DI побеждает in-memory bean
 * из {@code AppConfig}. Текущий {@code modulex-model-sdk} (0.0.1-6468)
 * сгенерирован из старой схемы {@code WalletTurn} и не содержит новых
 * полей: {@code ccBchOperationId}, {@code ccDate}, {@code ccTxId},
 * {@code ccBlockNumber}, {@code ccOwnerDt/Kt}, {@code ccSum}, {@code ccDateDoc},
 * {@code ccPurpose}, {@code ccSignature}.</p>
 *
 * <p>Чтобы активировать:</p>
 * <ol>
 *   <li>Регенерируй SDK из актуального {@code modulex.xml}
 *       (mvn -P generate-model или эквивалент в твоей build-инфре);</li>
 *   <li>Реализуй тело {@link #findByBchOperationId(String)} через
 *       {@code dsApi.searchWalletTurn(g -> g.setWhere(w -> w.ccBchOperationIdEq(...))
 *           .withCcDate().withCcBchOperationId().withCcTxId()...)};</li>
 *   <li>Поставь {@code @Primary} обратно;</li>
 *   <li>{@link WalletTurnGet#getCcBchOperationId()} и пр. появятся в getter'ах.</li>
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
                "DataSpaceWalletTurnRepository: текущий modulex-model-sdk не содержит "
                        + "новых полей WalletTurn (ccBchOperationId/ccDate/ccTxId и др.). "
                        + "Регенерируй SDK из обновлённого modulex.xml и раскомментируй "
                        + "тело (см. javadoc).");
    }
}
