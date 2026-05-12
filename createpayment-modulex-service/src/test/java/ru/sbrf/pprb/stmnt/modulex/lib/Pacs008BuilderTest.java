package ru.sbrf.pprb.stmnt.modulex.lib;

import org.junit.jupiter.api.Test;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class Pacs008BuilderTest {

    private final Pacs008Builder builder = new Pacs008Builder();

    @Test
    void buildsFullDocumentWithAllSections() {
        TurnDocdataDraft d = sampleDraft();

        String xml = builder.build(d);

        assertThat(xml).startsWith("<?xml");
        assertThat(xml).contains("xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\"");
        assertThat(xml).contains("<MsgId>op-001</MsgId>");
        assertThat(xml).contains("<NbOfTxs>1</NbOfTxs>");
        assertThat(xml).contains("<SttlmMtd>INDA</SttlmMtd>");
        assertThat(xml).contains("<EndToEndId>0</EndToEndId>");
        assertThat(xml).contains("<ClrChanl>MPNS</ClrChanl>");
        assertThat(xml).contains("<Prtry>01</Prtry>");      // ccTypeDoc
        assertThat(xml).contains("<Prtry>5</Prtry>");        // ccPriority
        assertThat(xml).contains("<IntrBkSttlmAmt Ccy=\"RUB\">1500.00</IntrBkSttlmAmt>");
        assertThat(xml).contains("<IntrBkSttlmDt>2026-05-11</IntrBkSttlmDt>");
        assertThat(xml).contains("<ChrgBr>SLEV</ChrgBr>");
    }

    @Test
    void debtorSectionFromCcDtFields() {
        TurnDocdataDraft d = sampleDraft();

        String xml = builder.build(d);

        assertThat(xml).contains("<Dbtr>");
        assertThat(xml).contains("<Nm>ООО Плательщик</Nm>");
        assertThat(xml).contains("<Id>7707083893</Id>");        // ccDTINN
        assertThat(xml).contains("<Cd>TXID</Cd>");
        assertThat(xml).contains("<DbtrAcct>");
        assertThat(xml).contains("<Id>40702810700010000001</Id>"); // ccDTAcc
        assertThat(xml).contains("<Cd>BBAN</Cd>");
        assertThat(xml).contains("<MmbId>044525225</MmbId>");       // ccDTBIC
        assertThat(xml).contains("<Nm>ПАО Сбербанк</Nm>");          // ccDTNameBank
        assertThat(xml).contains("<Cd>RUCBC</Cd>");
        assertThat(xml).contains("<Id>30101810400000000225</Id>"); // ccDTBankCorrAcc
    }

    @Test
    void creditorSectionFromCcKtFields() {
        TurnDocdataDraft d = sampleDraft();

        String xml = builder.build(d);

        assertThat(xml).contains("<Cdtr>");
        assertThat(xml).contains("<Nm>ООО Получатель</Nm>");        // ccKTName
        assertThat(xml).contains("<Id>7800123456</Id>");             // ccKTINN
        assertThat(xml).contains("<CdtrAcct>");
        assertThat(xml).contains("<Id>40702810800010000002</Id>");   // ccKTAcc
        assertThat(xml).contains("<MmbId>044030702</MmbId>");        // ccKTBIC
        assertThat(xml).contains("<Nm>АО Банк ПОЛУЧАТЕЛЬ</Nm>");      // ccKTNameBank
        assertThat(xml).contains("<Id>30101810500000000653</Id>");   // ccKTBankCorrAcc
    }

    @Test
    void rmtInfHasPurposeAndDocReference() {
        TurnDocdataDraft d = sampleDraft();

        String xml = builder.build(d);

        assertThat(xml).contains("<Ustrd>Оплата по договору №1</Ustrd>");
        assertThat(xml).contains("<Prtry>POD</Prtry>");
        assertThat(xml).contains("<Nb>123456</Nb>");                 // ccNum
        assertThat(xml).contains("<RltdDt>2026-05-10</RltdDt>");     // ccDateDoc.toLocalDate()
    }

    @Test
    void supplementaryParamsAttached() {
        TurnDocdataDraft d = sampleDraft();

        String xml = builder.build(d);

        assertThat(xml).contains("<SplmtryData>");
        assertThat(xml).contains("<Name>sourceIdModuleList</Name><Value>stmnt-giganetwork</Value>");
        assertThat(xml).contains("<Name>channel</Name><Value>PPRB_PAYMENT</Value>");
        assertThat(xml).contains("<Name>sendServiceId</Name><Value>tx-001</Value>");
    }

    @Test
    void nameLongerThan140CharsIsTruncatedAndTailMovedToCtctDtls() {
        TurnDocdataDraft d = sampleDraft();
        String longName = "X".repeat(160);
        d.setCcDTName(longName);

        String xml = builder.build(d);

        assertThat(xml).contains("<Nm>" + "X".repeat(140) + "</Nm>");
        assertThat(xml).contains("<CtctDtls>");
        assertThat(xml).contains("<Nm>" + longName.substring(longName.length() - 20) + "</Nm>");
    }

    @Test
    void taxRmtAlwaysPresentWithMvpDefaults() {
        // MVP: TaxRmt отправляется всегда. Бюджетные поля = "0",
        // КПП плательщика/получателя — реальные значения.
        TurnDocdataDraft d = sampleDraft();

        String xml = builder.build(d);

        assertThat(xml).contains("<TaxRmt>");
        assertThat(xml).contains("<Cdtr>");
        assertThat(xml).contains("<RegnId>0</RegnId>");
        assertThat(xml).contains("<TaxTp>780001001</TaxTp>");  // ccKTKPP
        assertThat(xml).contains("<Dbtr>");
        assertThat(xml).contains("<TaxTp>773601001</TaxTp>");  // ccDTKPP
        assertThat(xml).contains("<AdmstnZone>0</AdmstnZone>");
        assertThat(xml).contains("<RefNb>0</RefNb>");
        assertThat(xml).contains("<Mtd>0</Mtd>");
    }

    @Test
    void taxRmtUsesRealValuesWhenPresent() {
        TurnDocdataDraft d = sampleDraft();
        d.setCcKbk("18210301000011000110");
        d.setCcOktmo("45382000");
        d.setCcDocNumber108("ТР41797");
        d.setCcDrawerStatus101("01");
        d.setCcTaxPeriod107("2026");

        String xml = builder.build(d);

        assertThat(xml).contains("<RegnId>2026</RegnId>");
        assertThat(xml).contains("<AdmstnZone>45382000</AdmstnZone>");
        assertThat(xml).contains("<RefNb>ТР41797</RefNb>");
        assertThat(xml).contains("<CtgyDtls>18210301000011000110</CtgyDtls>");
        assertThat(xml).contains("<DbtrSts>01</DbtrSts>");
        assertThat(xml).contains("<Yr>2026-01-01</Yr>");
    }

    @Test
    void dtUsedWhenDocDate109LooksLikeIsoDate() {
        TurnDocdataDraft d = sampleDraft();
        d.setCcDocDate109("2017-01-31");

        String xml = builder.build(d);

        assertThat(xml).contains("<Mtd>0</Mtd>");
        assertThat(xml).contains("<Dt>2017-01-31</Dt>");
    }

    @Test
    void mtdUsedWhenDocDate109IsNotIsoDate() {
        TurnDocdataDraft d = sampleDraft();
        d.setCcDocDate109("00");

        String xml = builder.build(d);

        assertThat(xml).contains("<Mtd>00</Mtd>");
        assertThat(xml).doesNotContain("<Dt>");
    }

    @Test
    void prdTpUsedForPeriodCode() {
        TurnDocdataDraft d = sampleDraft();
        d.setCcTaxPeriod107("QTR2");
        d.setCcKbk("kbk-x");

        String xml = builder.build(d);

        assertThat(xml).contains("<Prd>");
        assertThat(xml).contains("<Tp>QTR2</Tp>");
    }

    @Test
    void prdYrUsedForFullDate() {
        TurnDocdataDraft d = sampleDraft();
        d.setCcTaxPeriod107("2026-04-01");
        d.setCcKbk("kbk-x");

        String xml = builder.build(d);

        assertThat(xml).contains("<Yr>2026-04-01</Yr>");
    }

    @Test
    void creDtTmIncludesGmtPlus3Offset() {
        TurnDocdataDraft d = sampleDraft();

        String xml = builder.build(d);

        // 2026-05-12T12:34:56+03:00 — фиксированный offset GMT+3
        assertThat(xml).containsPattern("<CreDtTm>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+03:00</CreDtTm>");
    }

    @Test
    void emptyFieldsProduceMinimalDocument() {
        TurnDocdataDraft d = TurnDocdataDraft.builder()
                .ccOperationId("op-min")
                .ccTransactionId("tx-min")
                .ccSum(new BigDecimal("10.00"))
                .ccDate(LocalDateTime.of(2026, 1, 1, 0, 0))
                .ccTypeDoc("01")
                .ccPriority(new BigDecimal("5"))
                .ccSystemId("stmnt-giganetwork")
                .build();

        String xml = builder.build(d);

        assertThat(xml).contains("<MsgId>op-min</MsgId>");
        assertThat(xml).doesNotContain("<DbtrAcct>");
        assertThat(xml).doesNotContain("<CdtrAcct>");
        // RmtInf всегда есть из-за обязательного TaxRmt на MVP
        assertThat(xml).contains("<RmtInf>");
        assertThat(xml).contains("<TaxRmt>");
        assertThat(xml).contains("<RegnId>0</RegnId>");
    }

    private TurnDocdataDraft sampleDraft() {
        return TurnDocdataDraft.builder()
                .ccOperationId("op-001")
                .ccTransactionId("tx-001")
                .ccRqUId("rq-001")
                .ccPayStatus("DRAFT")
                .ccDT("1")
                .ccTypeOper(BigDecimal.ZERO)
                .ccDate(LocalDateTime.of(2026, 5, 11, 10, 0))
                .ccDateDoc(LocalDateTime.of(2026, 5, 10, 0, 0))
                .ccSum(new BigDecimal("1500.00"))
                .ccTypeDoc("01")
                .ccNum("123456")
                .ccPurpose("Оплата по договору №1")
                .ccPriority(new BigDecimal("5"))
                .ccSystemId("stmnt-giganetwork")
                .ccDTName("ООО Плательщик")
                .ccDTINN("7707083893")
                .ccDTKPP("773601001")
                .ccDTAcc("40702810700010000001")
                .ccDTBIC("044525225")
                .ccDTNameBank("ПАО Сбербанк")
                .ccDTBankCorrAcc("30101810400000000225")
                .ccDTRegisterId("REG-DT-1")
                .ccKTName("ООО Получатель")
                .ccKTINN("7800123456")
                .ccKTKPP("780001001")
                .ccKTAcc("40702810800010000002")
                .ccKTBIC("044030702")
                .ccKTNameBank("АО Банк ПОЛУЧАТЕЛЬ")
                .ccKTBankCorrAcc("30101810500000000653")
                .ccKTRegisterId("REG-KT-1")
                .build();
    }
}
