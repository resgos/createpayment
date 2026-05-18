package ru.sbrf.pprb.stmnt.modulex.lib;

import org.junit.jupiter.api.Test;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ResultStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Простая логика маппинга: SUCCESS → EXECUTED, ERROR → FAILED.
 * Числовые коды в statusInfo.code не влияют — только resultStatus.
 */
class CcStatusMapperTest {

    @Test
    void anySuccessMapsToExecuted() {
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "300")).isEqualTo("PPRB_EXECUTED");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "301")).isEqualTo("PPRB_EXECUTED");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "315")).isEqualTo("PPRB_EXECUTED");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "202")).isEqualTo("PPRB_EXECUTED");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "500")).isEqualTo("PPRB_EXECUTED");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, null)).isEqualTo("PPRB_EXECUTED");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "")).isEqualTo("PPRB_EXECUTED");
        assertThat(CcStatusMapper.map(ResultStatus.SUCCESS, "abc")).isEqualTo("PPRB_EXECUTED");
    }

    @Test
    void anyErrorMapsToFailed() {
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "100")).isEqualTo("PPRB_FAILED");
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "150")).isEqualTo("PPRB_FAILED");
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "199")).isEqualTo("PPRB_FAILED");
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "999")).isEqualTo("PPRB_FAILED");
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, null)).isEqualTo("PPRB_FAILED");
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "")).isEqualTo("PPRB_FAILED");
        assertThat(CcStatusMapper.map(ResultStatus.ERROR, "abc")).isEqualTo("PPRB_FAILED");
    }

    @Test
    void nullStatusMapsToUnknown() {
        assertThat(CcStatusMapper.map(null, "300")).isEqualTo("UNKNOWN");
        assertThat(CcStatusMapper.map(null, null)).isEqualTo("UNKNOWN");
    }
}
