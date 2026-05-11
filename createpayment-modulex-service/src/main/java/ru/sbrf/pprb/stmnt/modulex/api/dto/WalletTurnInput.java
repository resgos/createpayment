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

    private String ccBchOperationId;
    private String ccContractId;

    private String ccOwnerDt;
    private String ccRegisterDt;
    private String ccOwnerKt;
    private String ccRegisterKt;

    private LocalDateTime ccDate;
    private LocalDateTime ccDateDoc;

    private BigDecimal ccSum;
    private String ccPurpose;
}
