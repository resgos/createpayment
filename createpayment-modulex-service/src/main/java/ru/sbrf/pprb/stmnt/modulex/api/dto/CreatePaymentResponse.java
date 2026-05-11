package ru.sbrf.pprb.stmnt.modulex.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentResponse {

    private String paymentId;
    private PaymentStatus status;
    private LocalDateTime createdAt;
    private String message;

    public enum PaymentStatus {
        CREATED,
        REJECTED,
        DUPLICATE
    }
}
