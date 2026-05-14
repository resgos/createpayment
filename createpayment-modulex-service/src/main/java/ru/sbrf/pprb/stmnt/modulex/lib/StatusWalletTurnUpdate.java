package ru.sbrf.pprb.stmnt.modulex.lib;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * То, что мы хотим upsert-ить в status_WalletTurn после квитанции от PGW.
 * Поля соответствуют модели данных {@code StatusWalletTurn}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusWalletTurnUpdate {
    /** Идентификатор оборота — для нас это updUID из квитанции = ccOperationId в нашей системе. */
    private String ccOperationId;
    /** transactionId (если ничего нет — пусто, заполнится при первичной вставке). */
    private String ccTransactionId;
    /** walletTurnId — внешний. */
    private String ccWalletTurnId;
    /** Транспортный correlationId (= ccRqUId исходного запроса в PGW). */
    private String ccRqUId;
    /** Транспортный ключ идемпотентности (приходит от PGW). */
    private String ccIdempotencyKey;
    /** Маппинг ccStatus: PPRB_EXECUTED / PPRB_PROCESSING / PPRB_FAILED / UNKNOWN. */
    private String ccStatus;
    /** statusInfo.code. */
    private String ccStatusCode;
    /** statusInfo.message. */
    private String ccStatusDesc;
    /** Время фиксации квитанции. */
    private LocalDateTime ccRqTm;
}
