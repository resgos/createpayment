package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePayment;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePaymentResponse;
import ru.sbrf.pprb.stmnt.modulex.config.IgniteThinClientProperties;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "ignite.thin-client", name = "enabled", havingValue = "true")
public class CreatePaymentLibraryIgnite {

    private final CreatePaymentLibrary delegate;
    private final IgniteThinClientProperties igniteProps;

    public CreatePaymentLibraryIgnite(CreatePaymentLibrary delegate, IgniteThinClientProperties igniteProps) {
        this.delegate = delegate;
        this.igniteProps = igniteProps;
    }

    public CreatePaymentResponse execute(CreatePayment request) {
        log.debug("CreatePayment via Ignite cache='{}'", igniteProps.getCacheName());
        // TODO: cache lookups of register/ucp/division resolutions through Ignite thin client
        return delegate.execute(request);
    }
}
