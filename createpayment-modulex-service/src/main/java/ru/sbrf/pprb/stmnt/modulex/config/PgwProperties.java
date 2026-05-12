package ru.sbrf.pprb.stmnt.modulex.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "pgw")
public class PgwProperties {

    private boolean enabled = true;
    private String url = "https://ingress-pgw-4g-ift.https.dev-sh5.ocp-geo.delta.sbrf.ru";
    private String transferPath = "/upd/transfer";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 30000;
    /** Гарант-доставка: количество попыток. */
    private int maxAttempts = 3;
    /** Гарант-доставка: задержка между попытками, мс (по спеке ≥30 сек). */
    private long retryDelayMs = 30_000;
}
