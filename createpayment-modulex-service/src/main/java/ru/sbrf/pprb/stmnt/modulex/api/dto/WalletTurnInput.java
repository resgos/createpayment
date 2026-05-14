package ru.sbrf.pprb.stmnt.modulex.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Вход: ссылка на оборот кошелька. Полная walletTurn-запись извлекается из БД
 * по {@code ccBchOperationId} (см. {@code WalletTurnRepository}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTurnInput {
    private String ccBchOperationId;
    private String ccContractId;
}
