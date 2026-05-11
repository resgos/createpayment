package ru.sbrf.pprb.stmnt.modulex.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "sber-integration")
public class SberIntegrationProperties {

    private String url = "http://stmnt-http.apps.bcivthq2.k8s.delta.sbrf.ru/sberintegration-statement-server/execute";
    private String method = "getSberIntegration";
    private String version = "1.0";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;
    private boolean bicDirectory = false;
}
