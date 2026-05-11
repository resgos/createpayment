package ru.sbrf.pprb.stmnt.modulex.rpc;

import com.googlecode.jsonrpc4j.spring.AutoJsonRpcServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.sbrf.pprb.stmnt.modulex.api.CreatePaymentService;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePayment;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePaymentResponse;
import ru.sbrf.pprb.stmnt.modulex.lib.CreatePaymentLibrary;

@Slf4j
@Service
@AutoJsonRpcServiceImpl
public class CreatePaymentServiceImpl implements CreatePaymentService {

    private final CreatePaymentLibrary createPaymentLibrary;

    public CreatePaymentServiceImpl(CreatePaymentLibrary createPaymentLibrary) {
        this.createPaymentLibrary = createPaymentLibrary;
    }

    @Override
    public CreatePaymentResponse execute(CreatePayment request) {
        log.debug("CreatePayment: rqUID={}, walletTurns={}",
                request != null ? request.getRqUID() : null,
                request != null && request.getWalletTurns() != null ? request.getWalletTurns().size() : 0);
        return createPaymentLibrary.execute(request);
    }
}
