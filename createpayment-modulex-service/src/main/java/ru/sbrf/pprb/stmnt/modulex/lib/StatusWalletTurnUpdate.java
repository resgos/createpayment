package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Upsert в {@code t_modulex_statuswalletturn} после квитанции PGW.
 *
 * <p>Унике-индекс таблицы: {@code (ccWalletTurnObjectId, ccStatus)} — на один
 * внешний blockchain-платёж может быть несколько строк, по одной на каждый статус.</p>
 *
 * <p>Поле {@code ccWalletTurnObjectId} раньше называлось {@code ccBchOperationId} —
 * переименование сделано на уровне БД.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusWalletTurnUpdate {

    /**
     * Внешний идентификатор платежа в blockchain — берётся из связанной walletTurn по
     * {@code ccBchOperationId} (поле walletTurn осталось со старым именем).
     * При наличии полного DataSpace-репозитория будет резолвиться через JOIN
     * turn_docdata → walletTurn по {@code ccOperationId}. Сейчас может быть null
     * до подключения реальной выборки.
     */
    private String ccWalletTurnObjectId;
    private String ccOperationId;
    private String ccTransactionId;
    private String ccStatus;
    private String ccStatusCode;
    private String ccStatusDesc;
    private LocalDateTime sysLastChangeDate;
}
