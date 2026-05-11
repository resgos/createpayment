package ru.sbrf.pprb.stmnt.modulex.config;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.SberIntegrationClient;
import ru.sbrf.pprb.stmnt.modulex.lib.CreatePaymentLibrary;
import ru.sbrf.pprb.stmnt.modulex.validator.SimpleValidator;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Configuration
@EnableAutoConfiguration
@ComponentScan({"ru.sbrf.pprb.stmnt"})
@EnableConfigurationProperties({
        CreatePaymentProperties.class,
        IgniteThinClientProperties.class,
        SberIntegrationProperties.class
})
public class AppConfig {

    public static final ZoneId ZONE_ID = ZoneId.of("GMT+3");
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final String PAYMENT_ID_PREFIX = "PMT-";

    @Bean
    public RestTemplate sberRestTemplate(RestTemplateBuilder builder, SberIntegrationProperties sberProps) {
        return builder
                .setConnectTimeout(Duration.ofMillis(sberProps.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(sberProps.getReadTimeoutMs()))
                .build();
    }

    @Bean
    public CreatePaymentLibrary createPaymentLibrary(
            SimpleValidator simpleValidator,
            CreatePaymentProperties createPaymentProperties,
            SberIntegrationClient sberIntegrationClient) {
        return new CreatePaymentLibrary(simpleValidator, createPaymentProperties, sberIntegrationClient);
    }
}
