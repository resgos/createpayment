package ru.sbrf.pprb.stmnt.modulex.api;

import com.googlecode.jsonrpc4j.JsonRpcParam;
import com.googlecode.jsonrpc4j.JsonRpcService;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePayment;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePaymentResponse;

@JsonRpcService("/api/createPayment")
public interface CreatePaymentService {

    /**
     * Принимает {@code params: { "createPayment": {...} }} (именованный параметр),
     * либо {@code params: [ {...} ]} (позиционный).
     */
    CreatePaymentResponse execute(@JsonRpcParam("createPayment") CreatePayment request);
}
