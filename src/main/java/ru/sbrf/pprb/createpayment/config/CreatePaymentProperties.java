package ru.sbrf.pprb.createpayment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@Getter
@Setter
@ConfigurationProperties(prefix = "createpayment")
public class CreatePaymentProperties {

    private BigDecimal maxAmount = new BigDecimal("100000000");
    private BigDecimal minAmount = new BigDecimal("0.01");
    private String defaultCurrency = "810";
    private int purposeMaxLength = 210;
}
