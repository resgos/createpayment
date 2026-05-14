package ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StatusInfo {
    /** Код ответа PGW: 100-199 ошибки, 202-299 обработка, 300/301/315 — успешный финал. */
    private String code;
    private String message;
}
