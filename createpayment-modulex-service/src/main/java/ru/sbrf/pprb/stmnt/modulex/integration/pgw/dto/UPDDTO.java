package ru.sbrf.pprb.stmnt.modulex.integration.pgw.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Тело запроса PGW {@code POST /upd/transfer}.
 * Контейнер УРД: атрибуты, msgAttributes и сам pacs.008 как строка в originalMessage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UPDDTO {

    /** Уникальный идентификатор УРД (DocGUID). 32 hex без тире, lowercase. */
    private String updUID;
    /** Тип документа ISO 20022, e.g. "pacs.008.001.08". */
    private String updType;
    /** id фабрики-отправителя, e.g. "stmnt-giganetwork". */
    private String sendModuleId;
    /** Map ключ-значение для атрибутов сообщения (ParentID, registerId, execute_on_debit, compress…). */
    private Map<String, String> msgAttributes;
    /** XML-сообщение по ISO 20022 как строка (pacs.008.001.08). */
    private String originalMessage;
    /** Бизнес-Сервис системы инициатора (UUID 36). */
    private String sendServiceId;
    /** Код бизнес-сценария. */
    private String bizSvc;
    /** DTO о проведённом учёте в AE. */
    private String operationDto;
    /** Контейнер подписи. */
    private Object signature;
    /** Версия УРД, опционально. */
    private String updVersion;
    /** id фабрики-получателя, e.g. "in-house-execution-payment". */
    private String rcvModuleId;
}
