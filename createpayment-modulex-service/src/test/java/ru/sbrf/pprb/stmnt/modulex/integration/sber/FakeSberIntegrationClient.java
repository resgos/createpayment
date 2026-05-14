package ru.sbrf.pprb.stmnt.modulex.integration.sber;

import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult.Epk;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult.Fskk;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult.Nsi;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult.Participant;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult.Sfs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Полноценный fake sberIntegration с предзаполняемыми таблицами:
 * <ul>
 *   <li>{@link #putRegister(String, String, String, String, String, String)} — связка registerId → FSKK</li>
 *   <li>{@link #putUcp(String, String, String, String)} — UCP → EPK</li>
 *   <li>{@link #putDivision(String, String, String, String)} — divisionId → SFS</li>
 *   <li>{@link #putBic(String, String, String)} — BIC → справочник банков</li>
 * </ul>
 *
 * <p>Каждый getByXxx чисто читает из карты. Не настроено → возвращается пустой ответ.</p>
 */
public class FakeSberIntegrationClient implements SberIntegrationClient {

    private final Map<String, Fskk> registers = new HashMap<>();
    private final Map<String, Epk> ucps = new HashMap<>();
    private final Map<String, Sfs> divisions = new HashMap<>();
    private final List<Participant> bicDirectory = new ArrayList<>();

    public FakeSberIntegrationClient putRegister(String registerId, String accNum, String accBic,
                                                 String corrAcc, String ucpId, String divisionId) {
        Fskk f = new Fskk();
        f.setAccNum(accNum);
        f.setAccBic(accBic);
        f.setAccBankCorrAcc(corrAcc);
        f.setUcpId(ucpId);
        f.setDivisionId(divisionId);
        f.setAccCurrency("RUR");
        f.setStatusCode(0);
        registers.put(registerId, f);
        return this;
    }

    public FakeSberIntegrationClient putUcp(String ucpId, String orgName, String orgINN, String orgKPP) {
        Epk e = new Epk();
        e.setUcpId(ucpId);
        e.setOrgName(orgName);
        e.setOrgINN(orgINN);
        e.setOrgKPP(orgKPP);
        e.setStatusCode(0);
        ucps.put(ucpId, e);
        return this;
    }

    public FakeSberIntegrationClient putDivision(String divisionId, String codeOSB, String codeTB, String fullName) {
        Sfs s = new Sfs();
        s.setDivisionId(divisionId);
        s.setCodeOSB(codeOSB);
        s.setCodeTB(codeTB);
        s.setFullName(fullName);
        s.setStatusCode(0);
        divisions.put(divisionId, s);
        return this;
    }

    public FakeSberIntegrationClient putBic(String bic, String name, String correspondentAcc) {
        Participant p = new Participant();
        p.setBic(bic);
        p.setName(name);
        p.setCorrespondentAcc(correspondentAcc);
        bicDirectory.add(p);
        return this;
    }

    @Override
    public GetSberIntegrationResult getByRegisterId(String registerId, String rqUID) {
        GetSberIntegrationResult res = new GetSberIntegrationResult();
        res.setStatusCode(0);
        res.setFskk(registers.get(registerId));
        return res;
    }

    @Override
    public GetSberIntegrationResult getByUcpId(String ucpId, String rqUID) {
        GetSberIntegrationResult res = new GetSberIntegrationResult();
        res.setStatusCode(0);
        Epk epk = ucps.get(ucpId);
        res.setEpk(epk != null ? List.of(epk) : List.of());
        return res;
    }

    @Override
    public GetSberIntegrationResult getByDivisionId(String divisionId, String rqUID) {
        GetSberIntegrationResult res = new GetSberIntegrationResult();
        res.setStatusCode(0);
        Sfs s = divisions.get(divisionId);
        res.setSfs(s != null ? List.of(s) : List.of());
        return res;
    }

    @Override
    public GetSberIntegrationResult getBicDirectory(String rqUID) {
        GetSberIntegrationResult res = new GetSberIntegrationResult();
        Nsi nsi = new Nsi();
        nsi.setBicDirectory(true);
        nsi.setParticipant(new ArrayList<>(bicDirectory));
        res.setNsi(nsi);
        res.setStatusCode(0);
        return res;
    }
}
