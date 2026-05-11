package ru.sbrf.pprb.stmnt.modulex.integration.sber.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JsonRpcRequest<T> {

    @Builder.Default
    private String jsonrpc = "2.0";
    private String method;
    private long id;
    private T params;

    @JsonProperty("jsonrpc")
    public String getJsonrpc() {
        return jsonrpc;
    }
}
