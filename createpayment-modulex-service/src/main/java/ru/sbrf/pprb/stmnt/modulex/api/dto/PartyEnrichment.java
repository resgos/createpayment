package ru.sbrf.pprb.stmnt.modulex.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartyEnrichment {

    private String registerId;
    private String accNum;
    private String accBic;
    private String accBankCorrAcc;
    private String accCurrency;

    private String ucpId;
    private String orgName;
    private String orgINN;
    private String orgKPP;

    private String divisionId;
    private String divisionName;
}
