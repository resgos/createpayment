package ru.sbrf.pprb.stmnt.modulex.integration.sber;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import ru.sbrf.pprb.stmnt.modulex.config.SberIntegrationProperties;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationParams;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationParams.Epk;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationParams.Fskk;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationParams.GetSberIntegrationBody;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationParams.Nsi;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationParams.Sfs;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.JsonRpcRequest;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.JsonRpcResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class SberIntegrationClientImpl implements SberIntegrationClient {

    private final SberIntegrationProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicLong idSeq = new AtomicLong(System.currentTimeMillis());

    public SberIntegrationClientImpl(SberIntegrationProperties properties,
                                     @Qualifier("sberRestTemplate") RestTemplate sberRestTemplate,
                                     @Qualifier("sberObjectMapper") ObjectMapper sberObjectMapper) {
        this.properties = properties;
        this.restTemplate = sberRestTemplate;
        this.objectMapper = sberObjectMapper;
    }

    @Override
    public GetSberIntegrationResult getByRegisterId(String registerId, String rqUID) {
        if (registerId == null || registerId.isBlank()) {
            return null;
        }
        log.debug("Sber getSberIntegration by registerId={}", registerId);
        GetSberIntegrationBody body = baseBody(rqUID)
                .fskk(Fskk.builder().registerId(registerId).build())
                .build();
        return call(body);
    }

    @Override
    public GetSberIntegrationResult getByUcpId(String ucpId, String rqUID) {
        if (ucpId == null || ucpId.isBlank()) {
            return null;
        }
        log.debug("Sber getSberIntegration by ucpId={}", ucpId);
        GetSberIntegrationBody body = baseBody(rqUID)
                .epk(List.of(Epk.builder().ucpId(ucpId).build()))
                .build();
        return call(body);
    }

    @Override
    public GetSberIntegrationResult getByDivisionId(String divisionId, String rqUID) {
        if (divisionId == null || divisionId.isBlank()) {
            return null;
        }
        log.debug("Sber getSberIntegration by divisionId={}", divisionId);
        GetSberIntegrationBody body = baseBody(rqUID)
                .sfs(List.of(Sfs.builder().divisionId(divisionId).build()))
                .build();
        return call(body);
    }

    private GetSberIntegrationBody.GetSberIntegrationBodyBuilder baseBody(String rqUID) {
        return GetSberIntegrationBody.builder()
                .rqTm(LocalDateTime.now())
                .rqUID(rqUID)
                .version(properties.getVersion())
                .nsi(Nsi.builder().bicDirectory(properties.isBicDirectory()).build());
    }

    private GetSberIntegrationResult call(GetSberIntegrationBody body) {
        JsonRpcRequest<GetSberIntegrationParams> req = JsonRpcRequest.<GetSberIntegrationParams>builder()
                .method(properties.getMethod())
                .id(idSeq.incrementAndGet())
                .params(GetSberIntegrationParams.builder().getSberIntegration(body).build())
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<JsonRpcRequest<GetSberIntegrationParams>> entity = new HttpEntity<>(req, headers);

        ResponseEntity<String> raw = restTemplate.postForEntity(properties.getUrl(), entity, String.class);

        try {
            JsonRpcResponse<GetSberIntegrationResult> resp = objectMapper.readValue(
                    raw.getBody(),
                    new TypeReference<JsonRpcResponse<GetSberIntegrationResult>>() {}
            );
            if (resp.getError() != null) {
                throw new IllegalStateException("Sber RPC error " + resp.getError().getCode()
                        + ": " + resp.getError().getMessage());
            }
            return resp.getResult();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Sber response", e);
        }
    }
}
