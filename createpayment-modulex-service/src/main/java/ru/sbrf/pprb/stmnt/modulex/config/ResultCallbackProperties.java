package ru.sbrf.pprb.stmnt.modulex.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Куда отправлять финальный {@code ExecutionResult} после получения квитанции от PGW.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "result-callback")
public class ResultCallbackProperties {

    /** Если false — callback не делается, только логируется. */
    private boolean enabled = false;
    /** Полный URL внешнего REST-эндпоинта инициатора. */
    private String url = "";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;
    private int maxAttempts = 3;
    private long retryDelayMs = 5_000;
}
