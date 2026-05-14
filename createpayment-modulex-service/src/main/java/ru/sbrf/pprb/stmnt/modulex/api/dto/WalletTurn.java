package ru.sbrf.pprb.stmnt.modulex.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * Полная walletTurn-запись из БД (модель {@code WalletTurn} в modulex.xml).
 * Заполняется DataSpace-репозиторием по {@code ccBchOperationId}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WalletTurn {
    private LocalDateTime ccDate;
    private String ccBchOperationId;
    private String ccTxId;
    private Long ccBlockNumber;
    private String ccContractId;
    private String ccOwnerDt;
    private String ccRegisterDt;
    private String ccOwnerKt;
    private String ccRegisterKt;
    private BigDecimal ccSum;
    private LocalDateTime ccDateDoc;
    private String ccPurpose;
    private String ccOperationId;
    private String ccTransactionId;
    private Date ccRqTm;
    private String ccRqUId;
    private String ccSignature;
    private LocalDateTime sysLastChangeDate;
}
