package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Лёгкая проекция строки status_WalletTurn — для резолва ccWalletTurnObjectId
 * по ccOperationId (callback от PGW знает только updUID = ccOperationId).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusWalletTurnView {
    private String ccWalletTurnObjectId;
    private String ccOperationId;
    private String ccTransactionId;
    private String ccStatus;
}
