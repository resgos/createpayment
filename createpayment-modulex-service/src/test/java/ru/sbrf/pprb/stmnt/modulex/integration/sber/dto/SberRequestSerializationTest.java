package ru.sbrf.pprb.stmnt.modulex.integration.sber.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sber отвергает rqTm в формате массива timestamp [year, month, ...].
 * Защищаемся @JsonFormat на поле + явной конфигурацией ObjectMapper.
 */
class SberRequestSerializationTest {

    @Test
    void rqTmIsSerializedAsIsoStringWithJsonFormatAnnotationAlone() throws Exception {
        // Даже без отключения WRITE_DATES_AS_TIMESTAMPS, аннотация на поле должна победить.
        ObjectMapper om = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        String json = om.writeValueAsString(buildSampleParams());

        assertThat(json).contains("\"rqTm\":\"2026-05-12T10:00:00\"");
        assertThat(json).doesNotContain("[2026,5,12");
    }

    @Test
    void rqTmIsSerializedAsIsoStringWithGlobalConfigOnly() throws Exception {
        ObjectMapper om = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        String json = om.writeValueAsString(buildSampleParams());

        assertThat(json).contains("\"rqTm\":\"2026-05-12T10:00:00\"");
    }

    @Test
    void envelopeShapeMatchesSberSchema() throws Exception {
        ObjectMapper om = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        GetSberIntegrationParams params = buildSampleParams();
        String json = om.writeValueAsString(params);

        assertThat(json).contains("\"getSberIntegration\":{");
        assertThat(json).contains("\"FSKK\":{\"registerId\":\"7574027350321135617\"}");
        assertThat(json).contains("\"NSI\":{\"bicDirectory\":false}");
        assertThat(json).contains("\"version\":\"1.0\"");
    }

    @Test
    void epkBodyShape() throws Exception {
        ObjectMapper om = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        GetSberIntegrationParams params = GetSberIntegrationParams.builder()
                .getSberIntegration(GetSberIntegrationParams.GetSberIntegrationBody.builder()
                        .rqTm(LocalDateTime.of(2026, 5, 12, 10, 0, 0))
                        .rqUID("REQ-1")
                        .version("1.0")
                        .epk(List.of(GetSberIntegrationParams.Epk.builder().ucpId("U-1").build()))
                        .nsi(GetSberIntegrationParams.Nsi.builder().bicDirectory(false).build())
                        .build())
                .build();

        String json = om.writeValueAsString(params);
        assertThat(json).contains("\"EPK\":[{\"ucpId\":\"U-1\"}]");
        assertThat(json).doesNotContain("\"FSKK\"");
        assertThat(json).doesNotContain("\"SFS\"");
    }

    private GetSberIntegrationParams buildSampleParams() {
        return GetSberIntegrationParams.builder()
                .getSberIntegration(GetSberIntegrationParams.GetSberIntegrationBody.builder()
                        .rqTm(LocalDateTime.of(2026, 5, 12, 10, 0, 0))
                        .rqUID("REQ-1")
                        .version("1.0")
                        .fskk(GetSberIntegrationParams.Fskk.builder()
                                .registerId("7574027350321135617")
                                .build())
                        .nsi(GetSberIntegrationParams.Nsi.builder().bicDirectory(false).build())
                        .build())
                .build();
    }
}
