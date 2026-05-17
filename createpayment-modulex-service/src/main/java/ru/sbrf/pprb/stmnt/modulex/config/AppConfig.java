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
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import ru.sbrf.pprb.stmnt.modulex.logging.HttpLoggingInterceptor;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.PgwClient;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.SberIntegrationClient;
import ru.sbrf.pprb.stmnt.modulex.lib.CreatePaymentLibrary;
import ru.sbrf.pprb.stmnt.modulex.lib.IdempotencyStore;
import ru.sbrf.pprb.stmnt.modulex.lib.InMemoryIdempotencyStore;
import ru.sbrf.pprb.stmnt.modulex.lib.InMemoryStatusWalletTurnRepository;
import ru.sbrf.pprb.stmnt.modulex.lib.InMemoryTurnDocdataRepository;
import ru.sbrf.pprb.stmnt.modulex.lib.InMemoryWalletTurnRepository;
import ru.sbrf.pprb.stmnt.modulex.lib.Pacs008Builder;
import ru.sbrf.pprb.stmnt.modulex.lib.StatusWalletTurnRepository;
import ru.sbrf.pprb.stmnt.modulex.lib.TurnDocdataIdGenerator;
import ru.sbrf.pprb.stmnt.modulex.lib.TurnDocdataRepository;
import ru.sbrf.pprb.stmnt.modulex.lib.WalletTurnRepository;
import ru.sbrf.pprb.stmnt.modulex.validator.SimpleValidator;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Configuration
@EnableAutoConfiguration
@ComponentScan({"ru.sbrf.pprb.stmnt"})
@EnableConfigurationProperties({
        SberIntegrationProperties.class,
        PgwProperties.class,
        ResultCallbackProperties.class
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
                sberObjectMapper, "sber");
    }

    @Bean
    public RestTemplate pgwRestTemplate(RestTemplateBuilder builder,
                                        PgwProperties pgwProps,
                                        ObjectMapper sberObjectMapper) {
        return jsonRestTemplate(builder,
                pgwProps.getConnectTimeoutMs(), pgwProps.getReadTimeoutMs(),
                sberObjectMapper, "pgw");
    }

    // RestTemplate для result-callback клиент собирает себе сам в
    // ResultCallbackClientImpl — это упрощает wiring и устраняет проблему
    // с отсутствующим именованным бином при stale-сборке.

    private RestTemplate jsonRestTemplate(RestTemplateBuilder builder,
                                          int connectTimeoutMs, int readTimeoutMs,
                                          ObjectMapper om, String loggerName) {
        RestTemplate rt = builder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
        // Буферизация: чтобы интерцептор мог прочитать body ответа и не сожрать его.
        rt.setRequestFactory(new BufferingClientHttpRequestFactory(rt.getRequestFactory()));
        rt.getInterceptors().add(new HttpLoggingInterceptor(loggerName));

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

    /**
     * In-memory fallback-репозитории. Используются, если нет DataSpace-имплементации
     * (например, в _test-runner / _local-run, где пакет {@code lib/dataspace/}
     * исключён из компиляции).
     *
     * <p>В корп. сборке к классам в {@code lib/dataspace/} применён {@code @Primary @Component} —
     * они вытесняют эти бины автоматически. Здесь {@code @Primary} НЕ ставим, иначе
     * получим два {@code @Primary} кандидата.</p>
     */
    @Bean
    public WalletTurnRepository walletTurnRepository() {
        return new InMemoryWalletTurnRepository();
    }

    @Bean
    public TurnDocdataRepository turnDocdataRepository() {
        return new InMemoryTurnDocdataRepository();
    }

    @Bean
    public StatusWalletTurnRepository statusWalletTurnRepository() {
        return new InMemoryStatusWalletTurnRepository();
    }

    @Bean
    public IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    public CreatePaymentLibrary createPaymentLibrary(SimpleValidator simpleValidator,
                                                     SberIntegrationClient sberIntegrationClient,
                                                     TurnDocdataIdGenerator idGenerator,
                                                     Pacs008Builder pacs008Builder,
                                                     PgwClient pgwClient,
                                                     WalletTurnRepository walletTurnRepository,
                                                     TurnDocdataRepository turnDocdataRepository,
                                                     StatusWalletTurnRepository statusWalletTurnRepository) {
        return new CreatePaymentLibrary(simpleValidator, sberIntegrationClient, idGenerator,
                pacs008Builder, pgwClient, walletTurnRepository,
                turnDocdataRepository, statusWalletTurnRepository);
    }
}
