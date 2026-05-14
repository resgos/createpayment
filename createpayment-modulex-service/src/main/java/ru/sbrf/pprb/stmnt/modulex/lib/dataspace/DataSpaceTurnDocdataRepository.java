package ru.sbrf.pprb.stmnt.modulex.lib.dataspace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;
import ru.sbrf.pprb.stmnt.modulex.lib.TurnDocdataRepository;
import ru.sbrf.pprb.stmnt.services.simple.dataspacemodulex.DataSpaceApi;

import java.util.Optional;

/**
 * Шаблон DataSpace-имплементации {@link TurnDocdataRepository}.
 *
 * <p>Не активен (нет {@code @Primary}). См. описание подхода в
 * {@link DataSpaceWalletTurnRepository}.</p>
 *
 * <p>Реализация: {@code save} = {@code Packet.turnDocdata.create + dsApi.execute(packet)};
 * {@code findByOperationId} = {@code dsApi.searchTurnDocdata(g -> g.ccOperationId().equal(...))}.
 * Тип {@code ccRqTm} в SDK — {@link java.util.Date}; нужна конвертация из
 * {@link java.time.LocalDateTime}.</p>
 */
@Slf4j
@Component
public class DataSpaceTurnDocdataRepository implements TurnDocdataRepository {

    private final DataSpaceApi dsApi;

    public DataSpaceTurnDocdataRepository(DataSpaceApi dsApi) {
        this.dsApi = dsApi;
    }

    @Override
    public void save(TurnDocdataDraft draft) {
        throw new UnsupportedOperationException(
                "DataSpaceTurnDocdataRepository.save не реализован — нужна "
                        + "регенерация SDK или адаптация под текущие имена методов "
                        + "Packet.turnDocdata.create / CreateTurnDocdataParam.setCcXxx.");
    }

    @Override
    public Optional<TurnDocdataDraft> findByOperationId(String ccOperationId) {
        throw new UnsupportedOperationException(
                "DataSpaceTurnDocdataRepository.findByOperationId не реализован — "
                        + "нужна актуальная сигнатура graph-DSL searchTurnDocdata.");
    }
}
