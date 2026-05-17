package ru.sbrf.pprb.stmnt.modulex.lib;

import org.junit.jupiter.api.Test;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResultStatus;

import static org.assertj.core.api.Assertions.assertThat;

class CcStatusMapperTest {

    @Test
    void successWithFinalCodesMapsToExecuted() {
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "300")).isEqualTo("PPRB_EXECUTED");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "301")).isEqualTo("PPRB_EXECUTED");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "315")).isEqualTo("PPRB_EXECUTED");
    }

    @Test
    void successWithProcessingRangeMapsToProcessing() {
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "202")).isEqualTo("PPRB_PROCESSING");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "250")).isEqualTo("PPRB_PROCESSING");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "299")).isEqualTo("PPRB_PROCESSING");
    }

    @Test
    void errorInRangeMapsToFailed() {
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "100")).isEqualTo("PPRB_FAILED");
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "150")).isEqualTo("PPRB_FAILED");
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "199")).isEqualTo("PPRB_FAILED");
    }

    @Test
    void successWithoutCodeFallsBackToProcessing() {
        // Без явного кода 300/301/315 — НЕ финал. Ждём следующую квитанцию.
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, null)).isEqualTo("PPRB_PROCESSING");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "")).isEqualTo("PPRB_PROCESSING");
    }

    @Test
    void errorWithoutCodeFallsBackToFailed() {
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, null)).isEqualTo("PPRB_FAILED");
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "")).isEqualTo("PPRB_FAILED");
    }

    @Test
    void unrecognizedCombinationMapsToProcessingOrUnknown() {
        // SUCCESS с любым числовым кодом, не равным 300/301/315 — PROCESSING.
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "500")).isEqualTo("PPRB_PROCESSING");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "199")).isEqualTo("PPRB_PROCESSING");
        // ERROR с кодом вне 100..199 — UNKNOWN.
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "300")).isEqualTo("UNKNOWN");
        assertThat(CcStatusMapper.map(null, "300")).isEqualTo("UNKNOWN");
    }

    @Test
    void nonNumericCodeIsHandledGracefully() {
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "abc")).isEqualTo("PPRB_PROCESSING");
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "abc")).isEqualTo("PPRB_FAILED");
    }
}
