package ru.sbrf.pprb.stmnt.modulex.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Проекция записи TurnDocdata (одна на walletTurn, сторона списания).
 * Поля, которые на момент DRAFT не заполняются, оставлены null (см. сводку маппинга).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnDocdataDraft {

    private String objectid;

    private String ccRegisterId;
    private String ccWalletId;

    private LocalDateTime ccDate;
    private LocalDate ccOperationDay;
    private LocalDateTime ccReceiptDate;

    private String ccOperationId;
    private String ccBchOperationId;
    private String ccTransactionId;
    private String ccContractId;
    private LocalDateTime ccRqTm;
    private String ccRqUId;
    private String ccIdempotencyKey;

    private String ccPayStatus;
    private String ccDT;
    private BigDecimal ccTypeOper;

    private BigDecimal ccStartSum;
    private BigDecimal ccStartSumNAT;
    private BigDecimal ccSum;
    private BigDecimal ccSumNAT;
    private BigDecimal ccSumPO;
    private BigDecimal ccSumPL;

    private String ccTypeDoc;
    private String ccNum;
    private LocalDateTime ccDateDoc;
    private String ccPurpose;
    private String ccPurposeCode;

    private String ccDTName;
    private String ccDTINN;
    private String ccDTKPP;
    private String ccDTAcc;
    private String ccDTBIC;
    private String ccDTNameBank;
    private String ccDTBankCorrAcc;
    private String ccDTRegisterId;

    private String ccKTName;
    private String ccKTINN;
    private String ccKTKPP;
    private String ccKTAcc;
    private String ccKTBIC;
    private String ccKTNameBank;
    private String ccKTBankCorrAcc;
    private String ccKTRegisterId;

    private String ccContrName;
    private String ccContrINN;
    private String ccContrKPP;
    private String ccContrAcc;
    private String ccContrBIC;
    private String ccContrNameBank;
    private String ccContrBankCorrAcc;
    private String ccContrRegisterId;

    private BigDecimal ccRateDT;
    private BigDecimal ccRateKT;
    private String ccValutaDT;
    private String ccValutaKT;
    private String ccValutaTrans;

    private BigDecimal ccPriority;

    private String ccDeliveryKind;
    private String ccVoCode;
    private String ccPayingCondition;
    private LocalDateTime ccPayingConditionDate;
    private String ccIncomeTypeCode;
    private String ccUip;
    private String ccUPNO;

    private String ccDrawerStatus101;
    private String ccKbk;
    private String ccOktmo;
    private String ccReasonCode106;
    private String ccTaxPeriod107;
    private String ccDocNumber108;
    private String ccDocDate109;
    private String ccPaymentKind110;

    private String ccSystemId;
    private String ccDivisionId;
    private String ccKindDoc;
    private String ccDocTypeCode;

    private LocalDateTime sysLastChangeDate;

    /** Код подразделения банка плательщика (SFS.codeOSB / codeTB). Для BrnchId/Id в pacs.008. */
    private String dtBranchCode;
    /** Код подразделения банка получателя (SFS.codeOSB / codeTB). Для BrnchId/Id в pacs.008. */
    private String ktBranchCode;

    /** Сформированный XML pacs.008.001.08 для отправки в PGW. */
    private String pacs008Xml;

    /** correlationId из синхронного ответа PGW на transferUpd. */
    private String pgwCorrelationId;
}
