package ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Тело запроса от PGW к нам через {@code POST /upd/response/execute}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponseTicketRequest {

    /** Идентификатор УРД, 32. По нему найдём запись в status_WalletTurn (= ccOperationId). */
    private String updUID;

    /** DTO о проведённом учёте в AE — не сохраняем. */
    private String operationDto;

    /** Обязательно: SUCCESS или ERROR. */
    private ResultStatus resultStatus;

    /** Код + описание PGW (для маппинга в ccStatus / ccStatusCode / ccStatusDesc). */
    private StatusInfo statusInfo;

    /** Доп. атрибуты — не сохраняем. */
    private Map<String, String> msgAttributes;
}
