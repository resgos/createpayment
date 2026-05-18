package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;
import ru.sbrf.pprb.stmnt.modulex.config.AppConfig;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * Сборка FI To FI Customer Credit Transfer V08 (pacs.008.001.08) из TurnDocdataDraft.
 * Маппинг — согласно методологии (CSV спека).
 */
@Slf4j
@Component
public class Pacs008Builder {

    private static final String NS = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
    /** Namespace для UPDExtension/DynExt/Param — отдельный от pacs.008. */
    private static final String NS_UPDEXT = "urn:iso:std:iso:20022:tech:xsd:updext";
    /** XML-namespace для xmlns-атрибута (для setAttributeNS). */
    private static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";
    private static final int NAME_MAX = 140;
    private static final int CONTACT_TAIL = 20;
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern PERIOD_TYPE_PATTERN = Pattern.compile("MM\\d{2}|QTR[1-4]|HLF[12]");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{4}");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public String build(TurnDocdataDraft d) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document doc = dbf.newDocumentBuilder().newDocument();
            doc.setXmlStandalone(true);

            Element document = doc.createElementNS(NS, "Document");
            doc.appendChild(document);

            Element fitofi = elem(doc, document, "FIToFICstmrCdtTrf");
            appendGrpHdr(doc, fitofi, d);
            appendCdtTrfTxInf(doc, fitofi, d);
            appendSplmtryData(doc, fitofi, d);

            return serialize(doc);
        } catch (ParserConfigurationException | TransformerException e) {
            log.error("Failed to build pacs.008 for ccOperationId={}", d.getCcOperationId(), e);
            throw new IllegalStateException("pacs.008 build failed", e);
        }
    }

    private void appendGrpHdr(Document doc, Element parent, TurnDocdataDraft d) {
        Element grpHdr = elem(doc, parent, "GrpHdr");
        text(doc, grpHdr, "MsgId", d.getCcOperationId());
        // CreDtTm c offset, без долей секунды: e.g. 2026-05-12T12:34:56+03:00
        text(doc, grpHdr, "CreDtTm",
                OffsetDateTime.now(AppConfig.ZONE_ID).truncatedTo(ChronoUnit.SECONDS).format(ISO_OFFSET));
        text(doc, grpHdr, "NbOfTxs", "1");
        Element sttlmInf = elem(doc, grpHdr, "SttlmInf");
        text(doc, sttlmInf, "SttlmMtd", "INDA");
    }

    private void appendCdtTrfTxInf(Document doc, Element parent, TurnDocdataDraft d) {
        Element tx = elem(doc, parent, "CdtTrfTxInf");

        Element pmtId = elem(doc, tx, "PmtId");
        text(doc, pmtId, "EndToEndId", "0");

        Element pmtTpInf = elem(doc, tx, "PmtTpInf");
        text(doc, pmtTpInf, "ClrChanl", "MPNS");
        Element lclInstrm = elem(doc, pmtTpInf, "LclInstrm");
        text(doc, lclInstrm, "Prtry", nz(d.getCcTypeDoc(), "01"));
        Element ctgyPurp = elem(doc, pmtTpInf, "CtgyPurp");
        text(doc, ctgyPurp, "Prtry", d.getCcPriority() != null ? d.getCcPriority().toPlainString() : "5");

        Element amt = elem(doc, tx, "IntrBkSttlmAmt");
        amt.setAttribute("Ccy", "RUB");
        if (d.getCcSum() != null) {
            amt.setTextContent(d.getCcSum().setScale(2, RoundingMode.HALF_UP).toPlainString());
        }

        if (d.getCcDate() != null) {
            text(doc, tx, "IntrBkSttlmDt", d.getCcDate().toLocalDate().format(ISO_DATE));
        }

        text(doc, tx, "ChrgBr", "SLEV");

        appendParty(doc, tx, "Dbtr", d.getCcDTName(), d.getCcDTINN());
        appendAcct(doc, tx, "DbtrAcct", d.getCcDTAcc());
        appendAgent(doc, tx, "DbtrAgt", d.getCcDTBIC(), d.getCcDTNameBank());
        appendAcct(doc, tx, "DbtrAgtAcct", d.getCcDTBankCorrAcc());

        appendAgent(doc, tx, "CdtrAgt", d.getCcKTBIC(), d.getCcKTNameBank());
        appendAcct(doc, tx, "CdtrAgtAcct", d.getCcKTBankCorrAcc());
        appendParty(doc, tx, "Cdtr", d.getCcKTName(), d.getCcKTINN());
        appendAcct(doc, tx, "CdtrAcct", d.getCcKTAcc());

        appendRmtInf(doc, tx, d);
        // SplmtryData / BrnchId на текущем эталоне pacs.008 не выпускаются.
    }

    private void appendParty(Document doc, Element parent, String role, String name, String inn) {
        Element party = elem(doc, parent, role);
        if (name != null && !name.isBlank()) {
            text(doc, party, "Nm", name.length() > NAME_MAX ? name.substring(0, NAME_MAX) : name);
        }
        if (inn != null && !inn.isBlank()) {
            Element id = elem(doc, party, "Id");
            Element orgId = elem(doc, id, "OrgId");
            Element othr = elem(doc, orgId, "Othr");
            text(doc, othr, "Id", inn);
            Element schmeNm = elem(doc, othr, "SchmeNm");
            text(doc, schmeNm, "Cd", "TXID");
        }
        if (name != null && name.length() > NAME_MAX) {
            Element ctct = elem(doc, party, "CtctDtls");
            text(doc, ctct, "Nm", name.substring(name.length() - CONTACT_TAIL));
        }
    }

    private void appendAcct(Document doc, Element parent, String role, String accNum) {
        if (accNum == null || accNum.isBlank()) return;
        Element acct = elem(doc, parent, role);
        Element id = elem(doc, acct, "Id");
        Element othr = elem(doc, id, "Othr");
        text(doc, othr, "Id", accNum);
        Element schmeNm = elem(doc, othr, "SchmeNm");
        text(doc, schmeNm, "Cd", "BBAN");
    }

    private void appendAgent(Document doc, Element parent, String role,
                             String bic, String bankName) {
        Element agt = elem(doc, parent, role);
        Element finInst = elem(doc, agt, "FinInstnId");
        if (bic != null && !bic.isBlank()) {
            Element clrSysMmbId = elem(doc, finInst, "ClrSysMmbId");
            Element clrSysId = elem(doc, clrSysMmbId, "ClrSysId");
            text(doc, clrSysId, "Cd", "RUCBC");
            text(doc, clrSysMmbId, "MmbId", bic);
        }
        if (bankName != null && !bankName.isBlank()) {
            text(doc, finInst, "Nm", bankName.length() > NAME_MAX
                    ? bankName.substring(0, NAME_MAX) : bankName);
        }
    }

    private void appendRmtInf(Document doc, Element parent, TurnDocdataDraft d) {
        boolean hasPurpose = d.getCcPurpose() != null && !d.getCcPurpose().isBlank();
        boolean hasDoc = d.getCcNum() != null || d.getCcDateDoc() != null;
        boolean hasTax = hasTaxData(d);
        if (!hasPurpose && !hasDoc && !hasTax) return;

        Element rmtInf = elem(doc, parent, "RmtInf");
        if (hasPurpose) {
            text(doc, rmtInf, "Ustrd", d.getCcPurpose());
        }
        if (hasDoc || hasTax) {
            Element strd = elem(doc, rmtInf, "Strd");
            if (hasDoc) appendRfrdDocInf(doc, strd, d);
            if (hasTax) appendTaxRmt(doc, strd, d);
        }
    }

    private boolean hasTaxData(TurnDocdataDraft d) {
        return d.getCcKTKPP() != null || d.getCcDTKPP() != null
                || d.getCcOktmo() != null || d.getCcDocNumber108() != null
                || (d.getCcDocDate109() != null && !d.getCcDocDate109().isBlank())
                || d.getCcKbk() != null || d.getCcReasonCode106() != null
                || d.getCcDrawerStatus101() != null || d.getCcPaymentKind110() != null
                || d.getCcTaxPeriod107() != null;
    }

    private void appendRfrdDocInf(Document doc, Element parent, TurnDocdataDraft d) {
        boolean hasDoc = d.getCcNum() != null || d.getCcDateDoc() != null;
        if (!hasDoc) return;
        Element rfrd = elem(doc, parent, "RfrdDocInf");
        Element tp = elem(doc, rfrd, "Tp");
        Element cdOrPrtry = elem(doc, tp, "CdOrPrtry");
        text(doc, cdOrPrtry, "Prtry", "POD");
        text(doc, rfrd, "Nb", d.getCcNum());
        if (d.getCcDateDoc() != null) {
            text(doc, rfrd, "RltdDt", d.getCcDateDoc().toLocalDate().format(ISO_DATE));
        }
    }

    private void appendTaxRmt(Document doc, Element parent, TurnDocdataDraft d) {
        Element taxRmt = elem(doc, parent, "TaxRmt");

        // Cdtr — RegnId/TaxTp только при наличии реальных значений.
        if (d.getCcTaxPeriod107() != null || (d.getCcKTKPP() != null && !d.getCcKTKPP().isBlank())) {
            Element cdtr = elem(doc, taxRmt, "Cdtr");
            if (d.getCcTaxPeriod107() != null) {
                text(doc, cdtr, "RegnId", d.getCcTaxPeriod107());
            }
            if (d.getCcKTKPP() != null && !d.getCcKTKPP().isBlank()) {
                text(doc, cdtr, "TaxTp", d.getCcKTKPP());
            }
        }

        if (d.getCcDTKPP() != null && !d.getCcDTKPP().isBlank()) {
            Element dbtr = elem(doc, taxRmt, "Dbtr");
            text(doc, dbtr, "TaxTp", d.getCcDTKPP());
        }

        if (d.getCcOktmo() != null) {
            text(doc, taxRmt, "AdmstnZone", d.getCcOktmo());
        }
        if (d.getCcDocNumber108() != null) {
            text(doc, taxRmt, "RefNb", d.getCcDocNumber108());
        }

        // Mtd / Dt — по формату ccDocDate109. Без MVP-дефолта "0".
        String docDate = d.getCcDocDate109();
        if (docDate != null && !docDate.isBlank()) {
            if (ISO_DATE_PATTERN.matcher(docDate).matches()) {
                text(doc, taxRmt, "Dt", docDate);
            } else {
                text(doc, taxRmt, "Mtd", docDate);
            }
        }

        appendTaxRcrd(doc, taxRmt, d);
    }

    private void appendTaxRcrd(Document doc, Element parent, TurnDocdataDraft d) {
        boolean hasRcrd = d.getCcKbk() != null || d.getCcReasonCode106() != null
                || d.getCcDrawerStatus101() != null || d.getCcPaymentKind110() != null
                || d.getCcTaxPeriod107() != null;
        if (!hasRcrd) return;

        Element rcrd = elem(doc, parent, "Rcrd");
        if (d.getCcPaymentKind110() != null) text(doc, rcrd, "Tp", d.getCcPaymentKind110());
        if (d.getCcReasonCode106() != null) text(doc, rcrd, "Ctgy", d.getCcReasonCode106());
        if (d.getCcKbk() != null) text(doc, rcrd, "CtgyDtls", d.getCcKbk());
        if (d.getCcDrawerStatus101() != null) text(doc, rcrd, "DbtrSts", d.getCcDrawerStatus101());
        appendTaxPrd(doc, rcrd, d.getCcTaxPeriod107());
    }

    /**
     * ccTaxPeriod107 может быть: код периода (MM01..MM12 / QTR1..QTR4 / HLF1..HLF2),
     * год ("2026"), полная дата ("2026-01-01"). Распознаём и кладём в нужное поле.
     */
    private void appendTaxPrd(Document doc, Element parent, String taxPeriod) {
        if (taxPeriod == null || taxPeriod.isBlank()) return;
        Element prd = elem(doc, parent, "Prd");
        if (PERIOD_TYPE_PATTERN.matcher(taxPeriod).matches()) {
            text(doc, prd, "Tp", taxPeriod);
        } else if (ISO_DATE_PATTERN.matcher(taxPeriod).matches()) {
            text(doc, prd, "Yr", taxPeriod);
        } else if (YEAR_PATTERN.matcher(taxPeriod).matches()) {
            text(doc, prd, "Yr", taxPeriod + "-01-01");
        } else {
            text(doc, prd, "Yr", taxPeriod);
        }
    }

    /**
     * Блок {@code SplmtryData/Envlp/UPDExtension/DynExt/Param} — обязательный для PGW
     * по контракту. Содержит 3 параметра:
     * <ul>
     *   <li>{@code sourceIdModuleList} — код модуля-источника (= {@code ccSystemId}).</li>
     *   <li>{@code channel} — канал поступления платежа (default {@code PPRB_PAYMENT}).</li>
     *   <li>{@code sendServiceId} — сервис-id инициатора (= {@code ccTransactionId}, UUID 36).</li>
     * </ul>
     *
     * <p><b>Namespace</b>: {@code SplmtryData} и {@code Envlp} остаются в pacs.008,
     * но {@code UPDExtension} и всё внутри ({@code DynExt}, {@code Param}, {@code Name},
     * {@code Value}) — в отдельном namespace {@code urn:iso:std:iso:20022:tech:xsd:updext}.</p>
     */
    private void appendSplmtryData(Document doc, Element parent, TurnDocdataDraft d) {
        Element splmtry = elem(doc, parent, "SplmtryData");
        Element envlp = elem(doc, splmtry, "Envlp");
        // UPDExtension и потомки — в namespace updext (не pacs.008).
        Element updExt = elemNs(doc, envlp, NS_UPDEXT, "UPDExtension");
        // Явный xmlns на UPDExtension — гарантия, что Transformer выведет declaration.
        updExt.setAttributeNS(XMLNS_NS, "xmlns", NS_UPDEXT);
        Element dynExt = elemNs(doc, updExt, NS_UPDEXT, "DynExt");
        appendParam(doc, dynExt, "sourceIdModuleList",
                nz(d.getCcSystemId(), TurnDocdataDefaults.SYSTEM_ID));
        appendParam(doc, dynExt, "channel",
                nz(d.getCcDocTypeCode(), TurnDocdataDefaults.CHANNEL_DEFAULT));
        appendParam(doc, dynExt, "sendServiceId", d.getCcTransactionId());
    }

    private void appendParam(Document doc, Element parent, String name, String value) {
        if (value == null || value.isBlank()) return;
        Element p = elemNs(doc, parent, NS_UPDEXT, "Param");
        textNs(doc, p, NS_UPDEXT, "Name", name);
        textNs(doc, p, NS_UPDEXT, "Value", value);
    }

    /** Element с явным namespace — для UPDExtension subtree (вне pacs.008 NS). */
    private Element elemNs(Document doc, Element parent, String ns, String name) {
        Element e = doc.createElementNS(ns, name);
        parent.appendChild(e);
        return e;
    }

    /** Text-element с явным namespace — для содержимого UPDExtension. */
    private void textNs(Document doc, Element parent, String ns, String name, String value) {
        if (value == null) return;
        Element e = doc.createElementNS(ns, name);
        e.setTextContent(value);
        parent.appendChild(e);
    }

    private Element elem(Document doc, Element parent, String name) {
        Element e = doc.createElementNS(NS, name);
        parent.appendChild(e);
        return e;
    }

    private void text(Document doc, Element parent, String name, String value) {
        if (value == null) return;
        Element e = doc.createElementNS(NS, name);
        e.setTextContent(value);
        parent.appendChild(e);
    }

    private String nz(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String serialize(Document doc) throws TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter out = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString();
    }
}
