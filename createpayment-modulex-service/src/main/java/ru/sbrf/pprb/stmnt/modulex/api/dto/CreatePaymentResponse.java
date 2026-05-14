package ru.sbrf.pprb.stmnt.modulex.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Тело результата JSON-RPC метода {@code execute} (имя верхнего ключа в спеке —
 * {@code executionResult}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentResponse {
    private LocalDateTime rqTm;
    private String rqUID;
    private String version;
    /** Результаты по каждой walletTurn из запроса. */
    private List<ExecutionResult> executionResults;
}
