package ru.sbrf.pprb.stmnt.modulex.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTurnResult {

    private String ccBchOperationId;
    private String ccTransactionId;
    private Status status;
    private String statusDesc;
    private TurnDocdataDraft turnDocdata;

    public enum Status {
        DRAFT_CREATED,
        FAILED
    }
}
