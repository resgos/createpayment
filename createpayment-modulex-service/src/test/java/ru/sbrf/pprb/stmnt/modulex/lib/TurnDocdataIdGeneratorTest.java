package ru.sbrf.pprb.stmnt.modulex.lib;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class TurnDocdataIdGeneratorTest {

    private static final Pattern HEX_32 = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern DOC_NUM = Pattern.compile("[1-9][0-9]{0,5}");

    private final TurnDocdataIdGenerator generator = new TurnDocdataIdGenerator();

    @Test
    void operationIdIs32HexLowercase() {
        for (int i = 0; i < 50; i++) {
            String id = generator.operationId();
            assertThat(id)
                    .as("operationId iteration %d", i)
                    .hasSize(32)
                    .matches(HEX_32);
        }
    }

    @Test
    void transactionIdIsUuid() {
        String id = generator.transactionId();
        assertThat(id).hasSize(36);
        assertThatNoException().isThrownBy(() -> UUID.fromString(id));
    }

    @Test
    void rqUIdIsUuid() {
        String id = generator.rqUId();
        assertThatNoException().isThrownBy(() -> UUID.fromString(id));
    }

    @Test
    void docNumIsDigitsUpTo6() {
        for (int i = 0; i < 200; i++) {
            String num = generator.docNum();
            assertThat(num)
                    .as("docNum iteration %d", i)
                    .matches(DOC_NUM);
            assertThat(Integer.parseInt(num)).isBetween(1, 999_999);
        }
    }

    @Test
    void identifiersAreUniqueAcrossCalls() {
        assertThat(generator.operationId()).isNotEqualTo(generator.operationId());
        assertThat(generator.transactionId()).isNotEqualTo(generator.transactionId());
        assertThat(generator.rqUId()).isNotEqualTo(generator.rqUId());
    }
}
