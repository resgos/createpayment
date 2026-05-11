package ru.sbrf.pprb.stmnt.modulex.integration.sber.dto;

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
public class JsonRpcResponse<T> {

    private String jsonrpc;
    private long id;
    private T result;
    private JsonRpcError error;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JsonRpcError {
        private int code;
        private String message;
    }
}
