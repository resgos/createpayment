package ru.sbrf.pprb.stmnt.modulex.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTurnInput {

    private String ccWalletTurnId;
    private String ccTransactionId;
    private String ccOperationId;
    private String ccContractId;

    private String ccWalletDt;
    private String ccRegisterDt;
    private String ccWalletKt;
    private String ccRegisterKt;

    private LocalDateTime ccDate;

    private String ccTypeDoc;
    private String ccNum;
    private LocalDateTime ccDateDoc;
    private String ccPurpose;
    private String ccPurposeCode;

    private BigDecimal ccSum;
    private BigDecimal ccSumNAT;
    private BigDecimal ccSumPO;
    private BigDecimal ccSumPL;
    private BigDecimal ccPriority;

    private String ccValutaTrans;
    private String ccValutaDT;
    private String ccValutaKT;
}
