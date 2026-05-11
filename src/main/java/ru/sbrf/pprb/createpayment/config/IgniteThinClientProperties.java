package ru.sbrf.pprb.createpayment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "ignite.thin-client")
public class IgniteThinClientProperties {

    private boolean enabled = false;
    private List<String> addresses = List.of("127.0.0.1:10800");
    private int connectTimeoutMs = 5000;
    private String cacheName = "payments";
}
