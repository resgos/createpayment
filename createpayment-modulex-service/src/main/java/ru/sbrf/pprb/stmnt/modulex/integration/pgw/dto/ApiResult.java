package ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ответ PGW {@code POST /upd/transfer}.
 * Поля заполняются по тому, что вернёт сервер — некоторые могут быть null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiResult {

    /** Транспортный correlationId — обычно равен requestId, который мы отправили. */
    private String correlationId;
    /** Ключ идемпотентности — приходит в асинхронной квитанции. */
    private String idempotencyKey;
    private String status;
    private String code;
    private String message;
}
