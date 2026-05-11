package ru.sbrf.pprb.stmnt.modulex.integration.sber;

import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult;

public interface SberIntegrationClient {

    GetSberIntegrationResult getByRegisterId(String registerId, String rqUID);

    GetSberIntegrationResult getByUcpId(String ucpId, String rqUID);

    GetSberIntegrationResult getByDivisionId(String divisionId, String rqUID);
}
