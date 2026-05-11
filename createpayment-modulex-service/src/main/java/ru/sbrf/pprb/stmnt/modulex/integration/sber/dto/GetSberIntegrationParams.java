package ru.sbrf.pprb.stmnt.modulex.integration.sber.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetSberIntegrationParams {

    @JsonProperty("getSberIntegration")
    private GetSberIntegrationBody getSberIntegration;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GetSberIntegrationBody {
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime rqTm;
        private String rqUID;
        private String version;
        @JsonProperty("FSKK")
        private Fskk fskk;
        @JsonProperty("EPK")
        private List<Epk> epk;
        @JsonProperty("SFS")
        private List<Sfs> sfs;
        @JsonProperty("NSI")
        private Nsi nsi;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Fskk {
        private String registerId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Epk {
        private String ucpId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sfs {
        private String divisionId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Nsi {
        private boolean bicDirectory;
    }
}
