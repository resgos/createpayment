package ru.sbrf.pprb.stmnt.modulex.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePayment {

    private String payerAccount;
    private String payerInn;
    private String payeeAccount;
    private String payeeInn;
    private String payeeName;
    private BigDecimal amount;
    private String currency;
    private String purpose;
    private LocalDate valueDate;
    private String externalId;
}
