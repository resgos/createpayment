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

    /**
     * Принудительная повторная обработка платежа.
     * <p>По умолчанию {@code false}: если у этого walletTurn уже есть финальный
     * статус ({@code PPRB_EXECUTED} или {@code PPRB_FAILED}) — отказываем без
     * новой отправки в PGW (идемпотентность по walletTurn).</p>
     * <p>При {@code true}: генерируется новый {@code ccOperationId}/
     * {@code ccTransactionId}, отправляется новый УРД в PGW (даже после
     * предыдущего FAILED). PGW дедуплицирует по requestId — каждая попытка
     * получит свой свежий requestId, так что для PGW это новый документ.</p>
     */
    private boolean forceResend;
}
