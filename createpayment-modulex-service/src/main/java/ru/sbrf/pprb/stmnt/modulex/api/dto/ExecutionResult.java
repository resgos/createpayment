package ru.sbrf.pprb.stmnt.modulex.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Результат пайплайна по одной walletTurn.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ExecutionResult {
    private String transactionId;
    private String operationId;
    private String bchOperationId;
    private String contractId;
    private ExecutionStatus resultStatus;
    /** Заполнено только при {@link ExecutionStatus#PPRB_FAILED}, иначе null. */
    private String statusDescription;
}
