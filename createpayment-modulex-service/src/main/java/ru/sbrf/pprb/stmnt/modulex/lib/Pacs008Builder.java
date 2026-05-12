package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;

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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Сборка FI To FI Customer Credit Transfer V08 (pacs.008.001.08) из TurnDocdataDraft.
 * Маппинг — согласно методологии (см. документ).
 */
@Slf4j
@Component
public class Pacs008Builder {

    private static final String NS = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
    private static final int NAME_MAX = 140;
    private static final int CONTACT_TAIL = 20;
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

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
        text(doc, grpHdr, "CreDtTm", LocalDateTime.now().format(ISO_DATE_TIME));
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

    private void appendAgent(Document doc, Element parent, String role, String bic, String bankName) {
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
        boolean hasTax = anyBudgetField(d);
        if (!hasPurpose && !hasDoc && !hasTax) return;

        Element rmtInf = elem(doc, parent, "RmtInf");
        if (hasPurpose) {
            text(doc, rmtInf, "Ustrd", d.getCcPurpose());
        }
        if (hasDoc || hasTax) {
            Element strd = elem(doc, rmtInf, "Strd");
            if (hasDoc) {
                Element rfrd = elem(doc, strd, "RfrdDocInf");
                Element tp = elem(doc, rfrd, "Tp");
                Element cdOrPrtry = elem(doc, tp, "CdOrPrtry");
                text(doc, cdOrPrtry, "Prtry", "POD");
                text(doc, rfrd, "Nb", d.getCcNum());
                if (d.getCcDateDoc() != null) {
                    text(doc, rfrd, "RltdDt", d.getCcDateDoc().toLocalDate().format(ISO_DATE));
                }
            }
            if (hasTax) {
                appendTaxRmt(doc, strd, d);
            }
        }
    }

    private boolean anyBudgetField(TurnDocdataDraft d) {
        return d.getCcKbk() != null || d.getCcOktmo() != null
                || d.getCcDrawerStatus101() != null || d.getCcReasonCode106() != null
                || d.getCcTaxPeriod107() != null || d.getCcDocNumber108() != null
                || d.getCcDocDate109() != null || d.getCcPaymentKind110() != null;
    }

    private void appendTaxRmt(Document doc, Element parent, TurnDocdataDraft d) {
        Element taxRmt = elem(doc, parent, "TaxRmt");

        Element cdtr = elem(doc, taxRmt, "Cdtr");
        text(doc, cdtr, "RegnId", d.getCcTaxPeriod107());
        text(doc, cdtr, "TaxTp", d.getCcKTKPP());

        Element dbtr = elem(doc, taxRmt, "Dbtr");
        text(doc, dbtr, "TaxTp", d.getCcDTKPP());

        text(doc, taxRmt, "AdmstnZone", d.getCcOktmo());
        text(doc, taxRmt, "RefNb", d.getCcDocNumber108());
        text(doc, taxRmt, "Mtd", d.getCcDocDate109());

        if (d.getCcKbk() != null || d.getCcReasonCode106() != null
                || d.getCcDrawerStatus101() != null || d.getCcPaymentKind110() != null) {
            Element rcrd = elem(doc, taxRmt, "Rcrd");
            text(doc, rcrd, "Tp", d.getCcPaymentKind110());
            text(doc, rcrd, "Ctgy", d.getCcReasonCode106());
            text(doc, rcrd, "CtgyDtls", d.getCcKbk());
            text(doc, rcrd, "DbtrSts", d.getCcDrawerStatus101());
        }
    }

    private void appendSplmtryData(Document doc, Element parent, TurnDocdataDraft d) {
        Element splmtry = elem(doc, parent, "SplmtryData");
        Element envlp = elem(doc, splmtry, "Envlp");
        Element updExt = elem(doc, envlp, "UPDExtension");
        Element dynExt = elem(doc, updExt, "DynExt");

        appendParam(doc, dynExt, "sourceIdModuleList", nz(d.getCcSystemId(), "stmnt-giganetwork"));
        appendParam(doc, dynExt, "channel", "PPRB_PAYMENT");
        appendParam(doc, dynExt, "sendServiceId", d.getCcTransactionId());
    }

    private void appendParam(Document doc, Element parent, String name, String value) {
        if (value == null) return;
        Element param = elem(doc, parent, "Param");
        text(doc, param, "Name", name);
        text(doc, param, "Value", value);
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
        return value != null ? value : fallback;
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
