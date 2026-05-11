package ru.sbrf.pprb.createpayment.rpc;

import com.googlecode.jsonrpc4j.spring.AutoJsonRpcServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import ru.sbrf.pprb.createpayment.api.CreatePaymentService;
import ru.sbrf.pprb.createpayment.api.dto.CreatePayment;
import ru.sbrf.pprb.createpayment.api.dto.CreatePaymentResponse;
import ru.sbrf.pprb.createpayment.config.IgniteThinClientProperties;
import ru.sbrf.pprb.createpayment.lib.CreatePaymentLibrary;
import ru.sbrf.pprb.createpayment.lib.CreatePaymentLibraryIgnite;

@Slf4j
@Service
@AutoJsonRpcServiceImpl
public class CreatePaymentServiceImpl implements CreatePaymentService {

    private final CreatePaymentLibrary createPaymentLibrary;
    @Nullable
    private final CreatePaymentLibraryIgnite createPaymentIgniteLibrary;
    private final IgniteThinClientProperties ignProps;

    public CreatePaymentServiceImpl(
            CreatePaymentLibrary createPaymentLibrary,
            @Autowired(required = false) @Nullable
            CreatePaymentLibraryIgnite createPaymentIgniteLibrary,
            IgniteThinClientProperties ignProps) {
        this.createPaymentLibrary = createPaymentLibrary;
        this.createPaymentIgniteLibrary = createPaymentIgniteLibrary;
        this.ignProps = ignProps;
    }

    @Override
    public CreatePaymentResponse execute(CreatePayment request) {
        if (ignProps.isEnabled() && createPaymentIgniteLibrary != null) {
            log.debug("CreatePayment: using Ignite library");
            return createPaymentIgniteLibrary.execute(request);
        }
        log.debug("CreatePayment: using default library");
        return createPaymentLibrary.execute(request);
    }
}
