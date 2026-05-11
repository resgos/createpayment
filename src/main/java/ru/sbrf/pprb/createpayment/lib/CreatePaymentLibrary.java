package ru.sbrf.pprb.createpayment.lib;

import lombok.extern.slf4j.Slf4j;
import ru.sbrf.pprb.createpayment.api.dto.CreatePayment;
import ru.sbrf.pprb.createpayment.api.dto.CreatePaymentResponse;
import ru.sbrf.pprb.createpayment.api.dto.CreatePaymentResponse.PaymentStatus;
import ru.sbrf.pprb.createpayment.config.AppConfig;
import ru.sbrf.pprb.createpayment.config.CreatePaymentProperties;
import ru.sbrf.pprb.createpayment.validator.SimpleValidator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
public class CreatePaymentLibrary {

    private static final String CURRENCY_RUB_CODE = "810";
    private static final String CURRENCY_RUB_CODE2 = "643";

    private final SimpleValidator simpleValidator;
    private final CreatePaymentProperties properties;

    public CreatePaymentLibrary(SimpleValidator simpleValidator, CreatePaymentProperties properties) {
        this.simpleValidator = simpleValidator;
        this.properties = properties;
    }

    public CreatePaymentResponse execute(CreatePayment request) {
        validate(request);

        LocalDate valueDate = request.getValueDate() != null
                ? request.getValueDate()
                : LocalDate.now(AppConfig.ZONE_ID);

        String paymentId = AppConfig.PAYMENT_ID_PREFIX + UUID.randomUUID();
        log.debug("CreatePayment: generated paymentId={}, valueDate={}", paymentId, valueDate);

        return CreatePaymentResponse.builder()
                .paymentId(paymentId)
                .status(PaymentStatus.CREATED)
                .createdAt(LocalDateTime.now(AppConfig.ZONE_ID))
                .message("Payment accepted")
                .build();
    }

    private void validate(CreatePayment request) {
        simpleValidator.requireNonNull(request, "request");
        simpleValidator.requireNonBlank(request.getPayerAccount(), "payerAccount");
        simpleValidator.requireNonBlank(request.getPayeeAccount(), "payeeAccount");
        simpleValidator.requireNonNull(request.getAmount(), "amount");

        BigDecimal amount = request.getAmount();
        if (amount.compareTo(properties.getMinAmount()) < 0
                || amount.compareTo(properties.getMaxAmount()) > 0) {
            throw new IllegalArgumentException(
                    "amount must be within [" + properties.getMinAmount()
                            + ", " + properties.getMaxAmount() + "]");
        }

        String currency = request.getCurrency() != null ? request.getCurrency() : properties.getDefaultCurrency();
        if (!CURRENCY_RUB_CODE.equals(currency) && !CURRENCY_RUB_CODE2.equals(currency)) {
            throw new IllegalArgumentException("Unsupported currency: " + currency);
        }

        if (request.getPurpose() != null && request.getPurpose().length() > properties.getPurposeMaxLength()) {
            throw new IllegalArgumentException(
                    "purpose exceeds max length " + properties.getPurposeMaxLength());
        }
    }
}
