package ru.sbrf.pprb.stmnt.modulex.integration.sber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GetSberIntegrationResult {

    private String rqUID;
    private String rqTm;
    private String version;
    private int[] actualDate;
    private int statusCode;
    private String statusDesc;

    @JsonProperty("FSKK")
    private Fskk fskk;
    @JsonProperty("EPK")
    private List<Epk> epk;
    @JsonProperty("SFS")
    private List<Sfs> sfs;
    @JsonProperty("NSI")
    private Nsi nsi;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Fskk {
        private String accNum;
        private String registerId;
        private String ucpId;
        private String beginDate;
        private String closeDate;
        private String divisionId;
        private String accBic;
        private String accCurrency;
        private String accBankCorrAcc;
        private Map<String, Object> peculiarity;
        private int statusCode;
        private String statusDesc;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Epk {
        private String ucpId;
        private String orgName;
        private String orgINN;
        private String orgKPP;
        private int statusCode;
        private String statusDesc;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sfs {
        private String divisionId;
        private String codeTB;
        private String codeOSB;
        private String fullName;
        private RequisitesDivision requisitesDivision;
        private int statusCode;
        private String statusDesc;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequisitesDivision {
        private String registrationNum;
        private String divBIC;
        private String correspondentAcc;
        private String divINN;
        private String divKPP;
        private String divOKPO;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Nsi {
        private boolean bicDirectory;
        private List<Participant> participant;
        private int statusCode;
        private String statusDesc;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Participant {
        @JsonProperty("BIC")
        private String bic;
        private String name;
        private String correspondentAcc;
        private String originatorUID;
    }
}
