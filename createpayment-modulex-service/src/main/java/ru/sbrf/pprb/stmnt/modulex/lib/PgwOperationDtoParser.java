package ru.sbrf.pprb.stmnt.modulex.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import ru.sbrf.pprb.stmnt.modulex.api.dto.TurnDocdataDraft;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Парсит поле {@code operationDto} (JSON-строка) из PGW-callback в
 * {@link TurnDocdataDraft}. PGW отдаёт большую часть полей в
 * {@code performedOperations[0].documentReason}.
 *
 * <p>Маппинг — по факту приходящего payload (см. реальный лог в IFT).
 * Формат дат PGW: {@code dd.MM.yyyy HH:mm:ss}.</p>
 *
 * <p>Что НЕ приходит и оставляется null:
 * {@code ccContractId}, {@code ccKTRegisterId}, {@code ccRqUID}, {@code ccRqTm}.
 * При необходимости — можно стащить из ccStatusDesc PPRB_STARTED-строки.</p>
 */
@Slf4j
@Component
public class PgwOperationDtoParser {

    private static final DateTimeFormatter PGW_DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    /** Тип документа — мы всегда шлём ПП. */
    private static final String DEFAULT_TYPE_DOC = "01";
    /** Признак списания. */
    private static final String DT_DEBIT = "1";
    /** Валюта по умолчанию — если operationCurrency пустая. */
    private static final String DEFAULT_CURRENCY = "810";

    private final ObjectMapper objectMapper;

    public PgwOperationDtoParser(@Qualifier("sberObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Парсит JSON-строку operationDto в draft.
     *
     * @param operationDtoJson сырое тело из {@code ResponseTicketRequest.operationDto}
     * @return draft или null, если payload пустой/невалидный
     */
    public TurnDocdataDraft parse(String operationDtoJson) {
        if (operationDtoJson == null || operationDtoJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(operationDtoJson);
            JsonNode ops = root.path("performedOperations");
            if (!ops.isArray() || ops.size() == 0) {
                log.warn("operationDto.performedOperations is empty or not array");
                return null;
            }
            JsonNode op = ops.get(0);
            JsonNode doc = op.path("documentReason");
            JsonNode register = op.path("register");
            JsonNode party = op.path("party");
            JsonNode extAttrs = op.path("externalAttributes");

            String currency = text(op, "operationCurrency");
            if (currency == null || currency.isBlank()) currency = DEFAULT_CURRENCY;

            BigDecimal sum = decimal(op, "operationSum");
            if (sum == null) sum = decimal(doc, "paymentAmount");

            LocalDateTime ccDate = dateTime(op, "accountingDate");
            LocalDate ccOperationDay = ccDate != null ? ccDate.toLocalDate() : null;

            return TurnDocdataDraft.builder()
                    // identity / ссылки
                    .ccTransactionId(text(extAttrs, "processNumber"))
                    .ccRegisterId(text(register, "objectId"))
                    .ccDTRegisterId(text(register, "objectId"))
                    .ccWalletId(text(party, "clientId"))
                    // даты
                    .ccDate(ccDate)
                    .ccOperationDay(ccOperationDay)
                    .ccReceiptDate(dateTime(doc, "inDate"))
                    .ccDateDoc(dateTime(doc, "orderDate"))
                    // суммы / валюта
                    .ccSum(sum)
                    .ccSumNAT(sum)
                    .ccSumPO(sum)
                    .ccSumPL(sum)
                    .ccValutaDT(currency)
                    .ccValutaKT(currency)
                    .ccValutaTrans(currency)
                    // документ
                    .ccTypeDoc(DEFAULT_TYPE_DOC)
                    .ccDT(DT_DEBIT)
                    .ccNum(text(doc, "orderNumber"))
                    .ccPurpose(text(doc, "remittanceInformation"))
                    .ccPriority(decimal(doc, "paymentPriority"))
                    .ccDivisionId(text(doc, "subdivision"))
                    // плательщик
                    .ccDTName(text(doc, "payerName"))
                    .ccDTINN(text(doc, "payerInn"))
                    .ccDTKPP(text(doc, "payerKpp"))
                    .ccDTAcc(text(doc, "payerAccount"))
                    .ccDTBIC(text(doc, "payerBic"))
                    .ccDTNameBank(text(doc, "payerBankName"))
                    .ccDTBankCorrAcc(text(doc, "payerCorrAccount"))
                    // получатель
                    .ccKTName(text(doc, "receiverName"))
                    .ccKTINN(text(doc, "receiverInn"))
                    .ccKTKPP(text(doc, "receiverKpp"))
                    .ccKTAcc(text(doc, "receiverAccount"))
                    .ccKTBIC(text(doc, "receiverBic"))
                    .ccKTNameBank(text(doc, "receiverBankName"))
                    .ccKTBankCorrAcc(text(doc, "receiverCorrAccount"))
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse operationDto: {}", e.getMessage(), e);
            return null;
        }
    }

    private static String text(JsonNode parent, String field) {
        if (parent == null) return null;
        JsonNode n = parent.get(field);
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        String v = n.asText(null);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static BigDecimal decimal(JsonNode parent, String field) {
        if (parent == null) return null;
        JsonNode n = parent.get(field);
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isNumber()) return n.decimalValue();
        try {
            return new BigDecimal(n.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime dateTime(JsonNode parent, String field) {
        String raw = text(parent, field);
        if (raw == null) return null;
        try {
            return LocalDateTime.parse(raw, PGW_DATE_TIME);
        } catch (Exception e) {
            log.warn("Failed to parse PGW date '{}' for field '{}'", raw, field);
            return null;
        }
    }
}
