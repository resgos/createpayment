package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePayment;
import ru.sbrf.pprb.stmnt.modulex.api.dto.CreatePaymentResponse;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionResult;
import ru.sbrf.pprb.stmnt.modulex.api.dto.ExecutionStatus;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft.TurnDocdataDraftBuilder;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurn;
import ru.sbrf.pprb.stmnt.modulex.api.dto.WalletTurnInput;
import ru.sbrf.pprb.stmnt.modulex.config.AppConfig;
import ru.sbrf.pprb.stmnt.modulex.config.PgwProperties;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.PgwClient;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.ApiResult;
import ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto.UPDDTO;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.SberIntegrationClient;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult.Participant;
import ru.sbrf.pprb.stmnt.modulex.integration.sber.dto.GetSberIntegrationResult.Sfs;
import ru.sbrf.pprb.stmnt.modulex.validator.SimpleValidator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class CreatePaymentLibrary {

    private static final String PACS_008_TYPE = "pacs.008.001.08";
    private static final String RCV_MODULE_ID = "in-house-execution-payment";

    // msgAttributes — только обязательные.
    private static final String MSG_ATTR_PARENT_ID = "ParentID";
    private static final String MSG_ATTR_REGISTER_ID = "registerId";
    private static final String MSG_ATTR_EXECUTE_ON_DEBIT = "execute_on_debit";
    private static final String MSG_ATTR_COMPRESS = "compress";

    private final SimpleValidator simpleValidator;
    private final SberIntegrationClient sberClient;
    private final TurnDocdataIdGenerator idGenerator;
    private final Pacs008Builder pacs008Builder;
    private final PgwClient pgwClient;
    private final WalletTurnRepository walletTurnRepository;
    private final TurnDocdataRepository turnDocdataRepository;
    private final StatusWalletTurnRepository statusWalletTurnRepository;
    private final PgwProperties pgwProperties;

    public CreatePaymentLibrary(SimpleValidator simpleValidator,
                                SberIntegrationClient sberClient,
                                TurnDocdataIdGenerator idGenerator,
                                Pacs008Builder pacs008Builder,
                                PgwClient pgwClient,
                                WalletTurnRepository walletTurnRepository,
                                TurnDocdataRepository turnDocdataRepository,
                                StatusWalletTurnRepository statusWalletTurnRepository,
                                PgwProperties pgwProperties) {
        this.simpleValidator = simpleValidator;
        this.sberClient = sberClient;
        this.idGenerator = idGenerator;
        this.pacs008Builder = pacs008Builder;
        this.pgwClient = pgwClient;
        this.walletTurnRepository = walletTurnRepository;
        this.turnDocdataRepository = turnDocdataRepository;
        this.statusWalletTurnRepository = statusWalletTurnRepository;
        this.pgwProperties = pgwProperties;
    }

    public CreatePaymentResponse execute(CreatePayment request) {
        simpleValidator.requireNonNull(request, "request");
        simpleValidator.requireNonNull(request.getWalletTurns(), "walletTurns");

        String rqUID = request.getRqUID();
        LocalDateTime rqTm = request.getRqTm() != null ? request.getRqTm() : LocalDateTime.now(AppConfig.ZONE_ID);

        Map<String, Participant> bicDirectory = loadBicDirectory(rqUID);

        List<ExecutionResult> results = new ArrayList<>(request.getWalletTurns().size());
        for (WalletTurnInput ref : request.getWalletTurns()) {
            results.add(processOne(ref, rqUID, rqTm, bicDirectory));
        }

        return CreatePaymentResponse.builder()
                .rqUID(rqUID)
                .rqTm(LocalDateTime.now(AppConfig.ZONE_ID))
                .version(request.getVersion())
                .executionResults(results)
                .build();
    }

    /**
     * Один шаг пайплайна. Жизненный цикл status_WalletTurn в синхронной части:
     * <ol>
     *   <li>{@code PPRB_GET}     — валидация прошла, сгенерированы ccOperationId / ccTransactionId.</li>
     *   <li>enrichment + pacs.008 + send to PGW.</li>
     *   <li>{@code PPRB_STARTED} — PGW принял (transferUpd вернул SUCCESS).</li>
     * </ol>
     *
     * <p>{@code turn_docdata} в этой фазе НЕ сохраняем — он будет создан в
     * {@link ExecuteResponseHandler} после прихода квитанции от PGW,
     * с данными уже из callback-payload.</p>
     *
     * <p>Возвращаем PPRB_PROCESSING — clients остаются в курсе, что финал
     * придёт квитанцией. При любой ошибке до PGW — PPRB_FAILED.</p>
     */
    private ExecutionResult processOne(WalletTurnInput ref, String rqUID, LocalDateTime rqTm,
                                       Map<String, Participant> bicDirectory) {
        String bchOpId = ref != null ? ref.getCcBchOperationId() : null;
        String contractId = ref != null ? ref.getCcContractId() : null;

        // ID-ы генерим до try — чтобы catch-fallback мог записать PPRB_FAILED
        // со всеми тремя mandatory полями в DataSpace.
        String txId = idGenerator.transactionId();
        String operationId = idGenerator.operationId();

        try {
            simpleValidator.requireNonNull(ref, "walletTurn");
            simpleValidator.requireNonBlank(bchOpId, "ccBchOperationId");

            WalletTurn wt = walletTurnRepository.findByBchOperationId(bchOpId)
                    .orElseThrow(() -> new IllegalStateException(
                            "WalletTurn not found for ccBchOperationId=" + bchOpId));

            // 1. PPRB_GET — приняли запрос, ID-ы есть.
            persistStatus(bchOpId, operationId, txId,
                    ExecutionStatus.PPRB_GET.name(), null, null);

            TurnDocdataDraft draft = assembleDraft(wt, contractId, rqTm, txId, operationId, rqUID);
            Map<String, Sfs> sfsCache = new HashMap<>();
            enrichDt(draft, wt.getCcRegisterDt(), rqUID, sfsCache);
            enrichKt(draft, wt.getCcRegisterKt(), rqUID, sfsCache);
            applyBicDirectory(draft, bicDirectory);
            applyContraFromKt(draft);

            String xml = pacs008Builder.build(draft);
            draft.setPacs008Xml(xml);

            // 2. turn_docdata — двойная запись (DT + KT). Сохраняем ДО PGW,
            //    как только данные обогащения готовы. Документ существует
            //    независимо от итога доставки PGW (статус трекается отдельно
            //    в status_WalletTurn).
            saveTurnDocdataPair(draft, wt, txId);

            // 3. Отправка в PGW. Метод теперь НЕ бросает — возвращает ApiResult
            //    со status = SUCCESS / QUEUED / ERROR / SKIPPED.
            ApiResult pgwResult = sendToPgw(draft, xml);
            String pgwStatus = pgwResult.getStatus();

            // 4. Статус по итогу PGW-вызова.
            if ("QUEUED".equals(pgwStatus)) {
                // PGW не дошёл sync — УРД в outbox, ждём worker. НЕ FAILED.
                persistStatus(bchOpId, operationId, txId,
                        ExecutionStatus.PPRB_QUEUED.name(), pgwStatus, pgwResult.getMessage());
            } else if ("ERROR".equals(pgwStatus)) {
                // Outbox сам упал — терминальная ошибка доставки.
                persistStatus(bchOpId, operationId, txId,
                        ExecutionStatus.PPRB_FAILED.name(), pgwStatus, pgwResult.getMessage());
                return ExecutionResult.builder()
                        .transactionId(txId)
                        .operationId(operationId)
                        .bchOperationId(bchOpId)
                        .contractId(contractId)
                        .resultStatus(ExecutionStatus.PPRB_FAILED)
                        .statusDescription(pgwResult.getMessage())
                        .build();
            } else {
                // SUCCESS или SKIPPED (PGW disabled) — считаем что доставлено.
                persistStatus(bchOpId, operationId, txId,
                        ExecutionStatus.PPRB_STARTED.name(), null, null);
            }

            return ExecutionResult.builder()
                    .transactionId(txId)
                    .operationId(operationId)
                    .bchOperationId(bchOpId)
                    .contractId(contractId)
                    .resultStatus(ExecutionStatus.PPRB_PROCESSING)
                    .statusDescription(null)
                    .build();
        } catch (Exception e) {
            log.warn("processOne failed for ccBchOperationId={}: {}", bchOpId, e.getMessage());
            if (bchOpId != null) {
                try {
                    persistStatus(bchOpId, operationId, txId,
                            ExecutionStatus.PPRB_FAILED.name(), null, e.getMessage());
                } catch (Exception inner) {
                    log.warn("Failed to persist FAILED status: {}", inner.getMessage());
                }
            }
            return ExecutionResult.builder()
                    .transactionId(txId)
                    .operationId(operationId)
                    .bchOperationId(bchOpId)
                    .contractId(contractId)
                    .resultStatus(ExecutionStatus.PPRB_FAILED)
                    .statusDescription(e.getMessage())
                    .build();
        }
    }

    /**
     * Двойная запись в turn_docdata по принципу банковской дебет/кредит:
     * <ul>
     *   <li><b>DT-строка</b>: {@code ccDT="1"}, {@code ccRegisterId=DT_register},
     *       {@code ccWalletId=DT_walletId}, {@code ccTransactionId=originalTxId}.</li>
     *   <li><b>KT-строка</b>: {@code ccDT="0"}, {@code ccRegisterId=KT_register},
     *       {@code ccWalletId=KT_walletId}, {@code ccTransactionId=originalTxId + "-KT"}.</li>
     * </ul>
     *
     * <p>Обе строки делят {@code ccOperationId} (DOCGUID), {@code ccBchOperationId},
     * полный набор Dbtr/Cdtr-реквизитов. Отличаются только перспективой
     * (чей счёт первичный) и txId — чтобы уникальный индекс по
     * {@code ccTransactionId} не нарушался.</p>
     *
     * <p>Если одна из сторон не удалась — продолжаем (вторая может пройти),
     * статус всё равно в status_WalletTurn.</p>
     */
    private void saveTurnDocdataPair(TurnDocdataDraft baseDraft, WalletTurn wt, String txId) {
        // DT-строка — base draft уже настроен под DT (ccDT=DT_DEBIT, ccRegisterId=ccDTRegisterId)
        try {
            turnDocdataRepository.save(baseDraft);
            log.debug("turn_docdata DT row saved: ccTransactionId={}, ccRegisterId={}",
                    baseDraft.getCcTransactionId(), baseDraft.getCcRegisterId());
        } catch (Exception e) {
            log.error("turn_docdata DT row save failed: ccBchOperationId={}, error={}",
                    baseDraft.getCcBchOperationId(), e.getMessage(), e);
        }
        // KT-строка — клонируем base, флипаем перспективу
        try {
            TurnDocdataDraft ktDraft = baseDraft.toBuilder()
                    .ccTransactionId(txId + "-KT")
                    .ccRegisterId(wt.getCcRegisterKt())
                    .ccWalletId(wt.getCcOwnerKt())
                    .ccDT("0")  // credit-side
                    .build();
            turnDocdataRepository.save(ktDraft);
            log.debug("turn_docdata KT row saved: ccTransactionId={}, ccRegisterId={}",
                    ktDraft.getCcTransactionId(), ktDraft.getCcRegisterId());
        } catch (Exception e) {
            log.error("turn_docdata KT row save failed: ccBchOperationId={}, error={}",
                    baseDraft.getCcBchOperationId(), e.getMessage(), e);
        }
    }

    private void persistStatus(String walletTurnObjectId, String operationId, String transactionId,
                               String ccStatus, String code, String desc) {
        statusWalletTurnRepository.upsertStatus(StatusWalletTurnUpdate.builder()
                .ccWalletTurnObjectId(walletTurnObjectId)
                .ccOperationId(operationId)
                .ccTransactionId(transactionId)
                .ccStatus(ccStatus)
                .ccStatusCode(code)
                .ccStatusDesc(desc)
                .sysLastChangeDate(LocalDateTime.now(AppConfig.ZONE_ID))
                .build());
    }

    private TurnDocdataDraft assembleDraft(WalletTurn wt, String contractIdOverride,
                                           LocalDateTime rqTm, String txId, String operationId,
                                           String rqUID) {
        LocalDateTime ccDate = wt.getCcDate() != null ? wt.getCcDate() : LocalDateTime.now(AppConfig.ZONE_ID);
        LocalDateTime now = LocalDateTime.now(AppConfig.ZONE_ID);
        String contractId = contractIdOverride != null ? contractIdOverride : wt.getCcContractId();

        TurnDocdataDraftBuilder b = TurnDocdataDraft.builder()
                .ccRegisterId(wt.getCcRegisterDt())
                .ccWalletId(wt.getCcOwnerDt())
                .ccOperationId(operationId)
                .ccBchOperationId(wt.getCcBchOperationId())
                .ccTransactionId(txId)
                .ccContractId(contractId)
                .ccRqTm(rqTm)
                .ccRqUId(idGenerator.rqUId())
                .ccPayStatus(TurnDocdataDefaults.PAY_STATUS_DRAFT)
                .ccDT(TurnDocdataDefaults.DT_DEBIT)
                .ccTypeOper(TurnDocdataDefaults.TYPE_OPER_CURRENT_DAY)
                .ccDate(ccDate)
                .ccOperationDay(ccDate.toLocalDate())
                .ccDateDoc(wt.getCcDateDoc())
                // Дата поступления распоряжения в банк плательщика — момент приёма execute.
                // Исполнение может позже перезаписать своим значением.
                .ccReceiptDate(now)
                .ccSum(wt.getCcSum())
                .ccSumNAT(wt.getCcSum())
                .ccSumPO(wt.getCcSum())
                .ccSumPL(wt.getCcSum())
                .ccTypeDoc(TurnDocdataDefaults.TYPE_DOC_PP)
                .ccNum(idGenerator.docNum())
                .ccPurpose(wt.getCcPurpose())
                .ccDTRegisterId(wt.getCcRegisterDt())
                .ccKTRegisterId(wt.getCcRegisterKt())
                .ccRateDT(TurnDocdataDefaults.RATE_DEFAULT)
                .ccRateKT(TurnDocdataDefaults.RATE_DEFAULT)
                .ccValutaDT(TurnDocdataDefaults.CURRENCY_RUB)
                .ccValutaKT(TurnDocdataDefaults.CURRENCY_RUB)
                .ccValutaTrans(TurnDocdataDefaults.CURRENCY_RUB)
                .ccPriority(TurnDocdataDefaults.PRIORITY_DEFAULT)
                .ccSystemId(TurnDocdataDefaults.SYSTEM_ID)
                .sysLastChangeDate(now);

        return b.build();
    }

    private Map<String, Participant> loadBicDirectory(String rqUID) {
        try {
            GetSberIntegrationResult res = sberClient.getBicDirectory(rqUID);
            if (res == null || res.getNsi() == null || res.getNsi().getParticipant() == null) {
                return Map.of();
            }
            Map<String, Participant> map = new HashMap<>();
            for (Participant p : res.getNsi().getParticipant()) {
                if (p.getBic() != null) {
                    map.put(p.getBic(), p);
                }
            }
            log.debug("Loaded BIC directory: {} participants", map.size());
            return map;
        } catch (Exception e) {
            log.warn("Failed to load BIC directory: {}", e.getMessage());
            return Map.of();
        }
    }

    private void enrichDt(TurnDocdataDraft d, String registerDt, String rqUID,
                          Map<String, Sfs> sfsCache) {
        if (registerDt == null || registerDt.isBlank()) {
            log.warn("DT: registerDt is empty, skipping ccDT* enrichment");
            return;
        }
        GetSberIntegrationResult byRegister = sberClient.getByRegisterId(registerDt, rqUID);
        GetSberIntegrationResult.Fskk fskk = byRegister != null ? byRegister.getFskk() : null;
        if (fskk == null) {
            log.warn("DT: FSKK is null for registerDt={}", registerDt);
            return;
        }
        d.setCcDTAcc(fskk.getAccNum());
        d.setCcDTBIC(fskk.getAccBic());
        d.setCcDTBankCorrAcc(fskk.getAccBankCorrAcc());
        d.setCcDivisionId(fskk.getDivisionId());

        if (fskk.getUcpId() != null && !fskk.getUcpId().isBlank()) {
            GetSberIntegrationResult byUcp = sberClient.getByUcpId(fskk.getUcpId(), rqUID);
            if (byUcp != null && byUcp.getEpk() != null && !byUcp.getEpk().isEmpty()) {
                GetSberIntegrationResult.Epk epk = byUcp.getEpk().get(0);
                d.setCcDTName(epk.getOrgName());
                d.setCcDTINN(epk.getOrgINN());
                d.setCcDTKPP(epk.getOrgKPP());
            }
        }

        Sfs sfs = resolveSfs(fskk.getDivisionId(), rqUID, sfsCache);
        if (sfs != null) {
            d.setDtBranchCode(branchCode(sfs));
        }
    }

    private void enrichKt(TurnDocdataDraft d, String registerKt, String rqUID,
                          Map<String, Sfs> sfsCache) {
        if (registerKt == null || registerKt.isBlank()) {
            log.warn("KT: registerKt is empty, skipping ccKT* enrichment");
            return;
        }
        GetSberIntegrationResult byRegister = sberClient.getByRegisterId(registerKt, rqUID);
        GetSberIntegrationResult.Fskk fskk = byRegister != null ? byRegister.getFskk() : null;
        if (fskk == null) {
            log.warn("KT: FSKK is null for registerKt={}", registerKt);
            return;
        }
        d.setCcKTAcc(fskk.getAccNum());
        d.setCcKTBIC(fskk.getAccBic());
        d.setCcKTBankCorrAcc(fskk.getAccBankCorrAcc());
        d.setCcKTUcpId(fskk.getUcpId());

        if (fskk.getUcpId() != null && !fskk.getUcpId().isBlank()) {
            GetSberIntegrationResult byUcp = sberClient.getByUcpId(fskk.getUcpId(), rqUID);
            if (byUcp != null && byUcp.getEpk() != null && !byUcp.getEpk().isEmpty()) {
                GetSberIntegrationResult.Epk epk = byUcp.getEpk().get(0);
                d.setCcKTName(epk.getOrgName());
                d.setCcKTINN(epk.getOrgINN());
                d.setCcKTKPP(epk.getOrgKPP());
            }
        }

        Sfs sfs = resolveSfs(fskk.getDivisionId(), rqUID, sfsCache);
        if (sfs != null) {
            d.setKtBranchCode(branchCode(sfs));
        }
    }

    private Sfs resolveSfs(String divisionId, String rqUID, Map<String, Sfs> cache) {
        if (divisionId == null || divisionId.isBlank()) return null;
        if (cache.containsKey(divisionId)) return cache.get(divisionId);
        try {
            GetSberIntegrationResult res = sberClient.getByDivisionId(divisionId, rqUID);
            Sfs match = null;
            if (res != null && res.getSfs() != null) {
                for (Sfs s : res.getSfs()) {
                    if (divisionId.equals(s.getDivisionId())) {
                        match = s;
                        break;
                    }
                }
                if (match == null) {
                    for (Sfs s : res.getSfs()) {
                        if (s.getCodeOSB() != null) { match = s; break; }
                    }
                }
                if (match == null && !res.getSfs().isEmpty()) {
                    match = res.getSfs().get(res.getSfs().size() - 1);
                }
            }
            cache.put(divisionId, match);
            return match;
        } catch (Exception e) {
            log.warn("SFS lookup failed for divisionId={}: {}", divisionId, e.getMessage());
            cache.put(divisionId, null);
            return null;
        }
    }

    /**
     * Код тербанка (codeTB) — для msgAttributes.TB по контракту PGW
     * ({@code String(2)}, пример {@code "13"}). Если codeTB пуст, fallback
     * на codeOSB (доп.офис), хотя он длиннее 2 символов — лучше что-то,
     * чем ничего.
     *
     * <p>Раньше использовалось приоритет codeOSB для pacs.008 BrnchId,
     * но BrnchId из XML мы убрали — теперь поле идёт только в TB-атрибут.</p>
     */
    private String branchCode(Sfs sfs) {
        return sfs.getCodeTB() != null ? sfs.getCodeTB() : sfs.getCodeOSB();
    }

    private void applyBicDirectory(TurnDocdataDraft d, Map<String, Participant> bicDirectory) {
        if (bicDirectory == null || bicDirectory.isEmpty()) return;
        Participant dtBank = d.getCcDTBIC() != null ? bicDirectory.get(d.getCcDTBIC()) : null;
        if (dtBank != null) {
            d.setCcDTNameBank(dtBank.getName());
            if (d.getCcDTBankCorrAcc() == null) {
                d.setCcDTBankCorrAcc(dtBank.getCorrespondentAcc());
            }
        }
        Participant ktBank = d.getCcKTBIC() != null ? bicDirectory.get(d.getCcKTBIC()) : null;
        if (ktBank != null) {
            d.setCcKTNameBank(ktBank.getName());
            if (d.getCcKTBankCorrAcc() == null) {
                d.setCcKTBankCorrAcc(ktBank.getCorrespondentAcc());
            }
        }
    }

    private void applyContraFromKt(TurnDocdataDraft d) {
        d.setCcContrName(d.getCcKTName());
        d.setCcContrINN(d.getCcKTINN());
        d.setCcContrKPP(d.getCcKTKPP());
        d.setCcContrAcc(d.getCcKTAcc());
        d.setCcContrBIC(d.getCcKTBIC());
        d.setCcContrNameBank(d.getCcKTNameBank());
        d.setCcContrBankCorrAcc(d.getCcKTBankCorrAcc());
        d.setCcContrRegisterId(d.getCcKTRegisterId());
    }

    private ApiResult sendToPgw(TurnDocdataDraft draft, String pacs008Xml) {
        UPDDTO updDTO = buildUpdDto(draft, pacs008Xml);
        ApiResult result = pgwClient.transferUpd(draft.getCcRqUId(), updDTO);
        draft.setPgwCorrelationId(result.getCorrelationId());
        if (result.getIdempotencyKey() != null) {
            draft.setCcIdempotencyKey(result.getIdempotencyKey());
        }
        log.debug("PGW response for ccOperationId={}: correlationId={}, status={}",
                draft.getCcOperationId(), result.getCorrelationId(), result.getStatus());
        return result;
    }

    private UPDDTO buildUpdDto(TurnDocdataDraft d, String pacs008Xml) {
        Map<String, String> attrs = new HashMap<>();

        // Только обязательные msgAttributes. Остальные параметры
        // (sourceIdModuleList, channel, sendServiceId, executionDeadline и т.д.)
        // уходят либо через SplmtryData в pacs.008, либо через root UPDDTO.
        attrs.put(MSG_ATTR_PARENT_ID, d.getCcOperationId());
        attrs.put(MSG_ATTR_EXECUTE_ON_DEBIT, "0");
        attrs.put(MSG_ATTR_COMPRESS, "0");
        if (d.getCcRegisterId() != null) {
            attrs.put(MSG_ATTR_REGISTER_ID, d.getCcRegisterId());
        }

        return UPDDTO.builder()
                .updUID(d.getCcOperationId())
                .updType(PACS_008_TYPE)
                .sendModuleId(d.getCcSystemId())
                .sendServiceId(d.getCcTransactionId())
                .rcvModuleId(RCV_MODULE_ID)
                .msgAttributes(attrs)
                .originalMessage(pacs008Xml)
                .build();
    }
}
