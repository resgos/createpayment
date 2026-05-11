package ru.sbrf.pprb.createpayment.api;

import com.googlecode.jsonrpc4j.JsonRpcService;
import ru.sbrf.pprb.createpayment.api.dto.CreatePayment;
import ru.sbrf.pprb.createpayment.api.dto.CreatePaymentResponse;

@JsonRpcService("/api/createPayment")
public interface CreatePaymentService {

    CreatePaymentResponse execute(CreatePayment request);
}
