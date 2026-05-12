package ru.sbrf.pprb.stmnt.modulex.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.PgwClient;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.SberIntegrationClient;
import ru.sbrf.pprb.stmnt.modulex.lib.CreatePaymentLibrary;
import ru.sbrf.pprb.stmnt.modulex.lib.Pacs008Builder;
import ru.sbrf.pprb.stmnt.modulex.lib.TurnDocdataIdGenerator;
import ru.sbrf.pprb.stmnt.modulex.validator.SimpleValidator;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Configuration
@EnableAutoConfiguration
@ComponentScan({"ru.sbrf.pprb.stmnt"})
@EnableConfigurationProperties({
        SberIntegrationProperties.class,
        PgwProperties.class
})
public class AppConfig {

    public static final ZoneId ZONE_ID = ZoneId.of("GMT+3");
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Отдельный ObjectMapper для общения с sberIntegration: LocalDateTime → ISO-строка,
     * а не массив timestamp. Не зависит от корп. настроек глобального маппера.
     */
    @Bean
    public ObjectMapper sberObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @Bean
    public RestTemplate sberRestTemplate(RestTemplateBuilder builder,
                                         SberIntegrationProperties sberProps,
                                         ObjectMapper sberObjectMapper) {
        return jsonRestTemplate(builder,
                sberProps.getConnectTimeoutMs(), sberProps.getReadTimeoutMs(),
                sberObjectMapper);
    }

    @Bean
    public RestTemplate pgwRestTemplate(RestTemplateBuilder builder,
                                        PgwProperties pgwProps,
                                        ObjectMapper sberObjectMapper) {
        return jsonRestTemplate(builder,
                pgwProps.getConnectTimeoutMs(), pgwProps.getReadTimeoutMs(),
                sberObjectMapper);
    }

    private RestTemplate jsonRestTemplate(RestTemplateBuilder builder,
                                          int connectTimeoutMs, int readTimeoutMs,
                                          ObjectMapper om) {
        RestTemplate rt = builder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter(om);
        for (int i = 0; i < rt.getMessageConverters().size(); i++) {
            HttpMessageConverter<?> conv = rt.getMessageConverters().get(i);
            if (conv instanceof MappingJackson2HttpMessageConverter) {
                rt.getMessageConverters().set(i, jsonConverter);
                return rt;
            }
        }
        rt.getMessageConverters().add(0, jsonConverter);
        return rt;
    }

    @Bean
    public CreatePaymentLibrary createPaymentLibrary(SimpleValidator simpleValidator,
                                                     SberIntegrationClient sberIntegrationClient,
                                                     TurnDocdataIdGenerator idGenerator,
                                                     Pacs008Builder pacs008Builder,
                                                     PgwClient pgwClient) {
        return new CreatePaymentLibrary(simpleValidator, sberIntegrationClient, idGenerator,
                pacs008Builder, pgwClient);
    }
}
