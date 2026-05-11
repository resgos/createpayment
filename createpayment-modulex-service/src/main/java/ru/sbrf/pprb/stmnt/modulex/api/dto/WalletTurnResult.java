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

    private String ccWalletTurnId;
    private String ccTransactionId;
    private Status status;
    private String statusDesc;

    private PartyEnrichment debit;
    private PartyEnrichment credit;

    public enum Status {
        ENRICHED,
        PARTIALLY_ENRICHED,
        FAILED
    }
}
