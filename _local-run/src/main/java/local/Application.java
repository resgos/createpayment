package local;

import com.googlecode.jsonrpc4j.spring.AutoJsonRpcServiceImplExporter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurn;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.PgwClient;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.UPDDTO;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.SberIntegrationClient;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult;
import ru.sbrf.pprb.stmnt.modulex.lib.WalletTurnRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import java.util.List;

@SpringBootApplication
@ComponentScan(basePackages = {"local", "ru.sbrf.pprb.stmnt"})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /** Регистрирует HTTP-эндпоинты для @AutoJsonRpcServiceImpl. */
    @Bean
    public static AutoJsonRpcServiceImplExporter autoJsonRpcServiceImplExporter() {
        return new AutoJsonRpcServiceImplExporter();
    }

    /** Заглушка sber вместо реального HTTP-вызова — чтобы можно было крутить локально без VPN. */
    @Bean
    @Primary
    public SberIntegrationClient stubSberClient() {
        return new SberIntegrationClient() {
            @Override
            public GetSberIntegrationResult getByRegisterId(String registerId, String rqUID) {
                GetSberIntegrationResult.Fskk fskk = new GetSberIntegrationResult.Fskk();
                fskk.setAccNum("40802810" + registerId.substring(Math.max(0, registerId.length() - 12)));
                fskk.setAccBic("044525225");
                fskk.setAccBankCorrAcc("30101810400000000225");
                fskk.setAccCurrency("RUR");
                fskk.setUcpId("UCP-" + registerId);
                fskk.setDivisionId("38903801697");
                fskk.setBeginDate("2025-11-18T00:00:00.000");
                fskk.setStatusCode(0);
                fskk.setStatusDesc("Stub OK");

                GetSberIntegrationResult result = new GetSberIntegrationResult();
                result.setRqUID(rqUID);
                result.setStatusCode(0);
                result.setFskk(fskk);
                return result;
            }

            @Override
            public GetSberIntegrationResult getByUcpId(String ucpId, String rqUID) {
                GetSberIntegrationResult.Epk epk = new GetSberIntegrationResult.Epk();
                epk.setUcpId(ucpId);
                epk.setOrgName("ИП Тестовый Клиент (" + ucpId + ")");
                epk.setOrgINN("253401125465");
                epk.setOrgKPP("0");
                epk.setStatusCode(0);
                epk.setStatusDesc("Stub OK");

                GetSberIntegrationResult result = new GetSberIntegrationResult();
                result.setRqUID(rqUID);
                result.setStatusCode(0);
                result.setEpk(List.of(epk));
                return result;
            }

            @Override
            public GetSberIntegrationResult getByDivisionId(String divisionId, String rqUID) {
                GetSberIntegrationResult.RequisitesDivision rd = new GetSberIntegrationResult.RequisitesDivision();
                rd.setRegistrationNum("1481/1948");
                rd.setDivBIC("044525225");
                rd.setCorrespondentAcc("30101810400000000225");

                GetSberIntegrationResult.Sfs sfs = new GetSberIntegrationResult.Sfs();
                sfs.setDivisionId(divisionId);
                sfs.setCodeTB("38");
                sfs.setCodeOSB("9038");
                sfs.setFullName("Доп.офис №9038/01697");
                sfs.setRequisitesDivision(rd);
                sfs.setStatusCode(0);

                GetSberIntegrationResult result = new GetSberIntegrationResult();
                result.setRqUID(rqUID);
                result.setStatusCode(0);
                result.setSfs(List.of(sfs));
                return result;
            }
        };
    }

    /**
     * Локальная замена DataSpace: вместо реальной выборки всегда отдаёт
     * синтетический WalletTurn — чтобы пайплайн end-to-end проходил без БД.
     */
    @Bean
    @Primary
    public WalletTurnRepository stubWalletTurnRepository() {
        return ccBchOperationId -> Optional.of(WalletTurn.builder()
                .ccBchOperationId(ccBchOperationId)
                .ccContractId("CONTRACT-" + ccBchOperationId)
                .ccOwnerDt("OWNER-DT-STUB")
                .ccRegisterDt("REG-DT-STUB")
                .ccOwnerKt("OWNER-KT-STUB")
                .ccRegisterKt("REG-KT-STUB")
                .ccDate(LocalDateTime.now())
                .ccDateDoc(LocalDateTime.now())
                .ccSum(new BigDecimal("1500.00"))
                .ccPurpose("Local stub payment")
                .build());
    }

    /** Заглушка PGW — не делает реальный HTTP, просто возвращает correlationId = requestId. */
    @Bean
    @Primary
    public PgwClient stubPgwClient() {
        return new PgwClient() {
            @Override
            public ApiResult transferUpd(String requestId, UPDDTO updDTO) {
                return ApiResult.builder()
                        .correlationId(requestId)
                        .status("STUB_OK")
                        .message("Local stub — no real PGW call")
                        .build();
            }
        };
    }
}
