package ru.sbrf.pprb.stmnt.modulex.lib;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class TurnDocdataIdGenerator {

    /** 32 hex символа, без тире, lowercase. */
    public String operationId() {
        return UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ENGLISH);
    }

    /** UUID, 36 символов. */
    public String transactionId() {
        return UUID.randomUUID().toString();
    }

    /** UUID, 36 символов. */
    public String rqUId() {
        return UUID.randomUUID().toString();
    }

    /** Номер документа: до 6 знаков, не ноль. */
    public String docNum() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(1, 1_000_000));
    }
}
