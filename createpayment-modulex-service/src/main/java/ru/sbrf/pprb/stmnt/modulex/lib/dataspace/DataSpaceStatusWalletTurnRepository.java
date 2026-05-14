package ru.sbrf.pprb.stmnt.modulex.lib.dataspace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnRepository;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnUpdate;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;

/**
 * Шаблон DataSpace-имплементации {@link StatusWalletTurnRepository}.
 *
 * <p>Не активен (нет {@code @Primary}). См. описание подхода в
 * {@link DataSpaceWalletTurnRepository}.</p>
 *
 * <p>Логика upsert по уник-ключу {@code (ccWalletTurnObjectId, ccStatus)}:</p>
 * <ol>
 *   <li>search по этой паре через {@code dsApi.searchStatusWalletTurn(...)};</li>
 *   <li>если запись есть — {@code packet.statusWalletTurn.update(...)};</li>
 *   <li>если нет — {@code packet.statusWalletTurn.create(...)};</li>
 *   <li>в конце {@code dsApi.execute(packet)}.</li>
 * </ol>
 *
 * <p>Обрати внимание: в текущей версии SDK (если поле ещё называется
 * {@code ccWalletTurnId}) используй его — методы {@code setCcWalletTurnObjectId}
 * / {@code ccWalletTurnObjectId()} ещё не сгенерированы.</p>
 */
@Slf4j
@Component
public class DataSpaceStatusWalletTurnRepository implements StatusWalletTurnRepository {

    private final DataSpaceApi dsApi;

    public DataSpaceStatusWalletTurnRepository(DataSpaceApi dsApi) {
        this.dsApi = dsApi;
    }

    @Override
    public void upsertStatus(StatusWalletTurnUpdate u) {
        throw new UnsupportedOperationException(
                "DataSpaceStatusWalletTurnRepository.upsertStatus не реализован — "
                        + "нужны корректные имена методов под актуальный SDK "
                        + "(searchStatusWalletTurn / Create*Param.setCcXxx / "
                        + "Update*Param.withObjectId, тип ccRqTm: Date vs LocalDateTime).");
    }
}
