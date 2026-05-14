# module-x-pprb · createpayment-modulex-service

Spring Boot модуль ППРБ.Выписка ЮЛ: принимает запрос на создание платежа из
blockchain-оборота, обогащает реквизиты через `sberIntegration`, собирает
ISO 20022 `pacs.008.001.08` и отправляет в PGW. Затем асинхронно получает
квитанцию исполнения и передаёт финальный результат в REST инициатора.

```
[Caller] ─── JSON-RPC execute ──▶ [module-x] ─── transferUpd ──▶ [PGW] ──▶ Исполнение
                                       │
                                       ▼
                                 turn_docdata
                                 status_WalletTurn(PPRB_PROCESSING)

[PGW] ─── upd/response/execute ──▶ [module-x] ─── POST ExecutionResult ──▶ [Caller's REST]
                                       │
                                       ▼
                                 status_WalletTurn(PPRB_EXECUTED|PPRB_FAILED)
```

## Содержание

- [Архитектура](#архитектура)
- [Поток обработки](#поток-обработки)
- [API](#api)
- [Модель данных](#модель-данных)
- [Конфигурация](#конфигурация)
- [Структура проекта](#структура-проекта)
- [Сборка и запуск](#сборка-и-запуск)
- [Тесты](#тесты)
- [Roadmap](#roadmap)

---

## Архитектура

Модуль — multi-module Maven проект:

```
module-x-pprb/                         ← parent pom (inherit dataspace-bom)
└── createpayment-modulex-service/     ← Spring Boot модуль
    └── src/main/
        ├── java/ru/sbrf/pprb/stmnt/modulex/
        │   ├── config/        — AppConfig + properties
        │   ├── api/           — JSON-RPC интерфейсы и DTO
        │   ├── rpc/           — реализации (@AutoJsonRpcServiceImpl, @RestController)
        │   ├── lib/           — бизнес-логика, репозитории, генераторы
        │   ├── integration/sber/      — клиент sberIntegration (HTTP JSON-RPC)
        │   ├── integration/pgw/       — клиент PGW transferUpd (HTTP REST)
        │   ├── integration/callback/  — outbound REST на инициатора
        │   └── validator/     — простые проверки полей
        └── resources/
            ├── application.yml
            └── model/modulex.xml      ← полная DataSpace модель в исходном стиле
```

Все интеграции вынесены в отдельные пакеты `integration/*`. Они дёргаются
из общего `CreatePaymentLibrary`. Репозитории DataSpace представлены через
интерфейсы; in-memory моки активируются автоматически, пока нет реальной
имплементации (`@ConditionalOnMissingBean`).

---

## Поток обработки

### 1. Sync приём (JSON-RPC `/api/createPayment`)

```
1. Валидация (ccBchOperationId непустой, walletTurns не null)
2. WalletTurnRepository.findByBchOperationId(ccBchOperationId)
3. Sber-обогащение по DT и KT сторонам:
   registerId → FSKK (accNum, BIC, corrAcc, ucpId, divisionId)
              → EPK (по ucpId) — orgName, INN, KPP
              → SFS (по divisionId) — codeOSB / codeTB
4. Один раз на батч — bicDirectory (NSI.participant), кладётся в Map<BIC, ...>
5. Сборка TurnDocdataDraft (50 полей) + applyBicDirectory + applyContraFromKt
6. Pacs008Builder.build(draft) → XML pacs.008.001.08
7. PgwClient.transferUpd(requestId=ccRqUId, UPDDTO{...,originalMessage=XML})
8. TurnDocdataRepository.save(draft)
9. StatusWalletTurnRepository.upsert(ccStatus=PPRB_PROCESSING)
10. Return executionResult.resultStatus = PPRB_PROCESSING (синхронно)
```

### 2. Async квитанция (REST `/upd/response/execute`)

```
1. CcStatusMapper(resultStatus, statusInfo.code):
   SUCCESS + 300/301/315  → PPRB_EXECUTED
   SUCCESS + 202..299     → PPRB_PROCESSING
   ERROR   + 100..199     → PPRB_FAILED
2. TurnDocdataRepository.findByOperationId(updUID) — забираем bchOpId, txId, contractId
3. StatusWalletTurnRepository.upsert(ccStatus=<mapped>)
4. Если статус финальный (PPRB_EXECUTED | PPRB_FAILED) —
   ResultCallbackClient.send(ExecutionResult)
5. Sync вернуть PGW: ApiResult.status=SUCCESS
```

### Гарантии устойчивости

- **PGW transferUpd**: до `pgw.max-attempts` попыток с задержкой `pgw.retry-delay-ms` (≥30 сек по спеке).
- **ResultCallback**: до `result-callback.max-attempts` попыток. При отключённом (`enabled=false`) — просто логируется.
- **Sber-сбой** на одной из сторон: соответствующие поля `ccDT*`/`ccKT*` остаются `null`, остальные секции выпускаются.
- **PGW падает sync** — walletTurn в результате получает `PPRB_FAILED` с описанием в `statusDescription`.

---

## API

### Эндпоинт 1: создание платежа

`POST /api/createPayment`
Content-Type: `application/json`

**Запрос**

```json
{
  "jsonrpc": "2.0",
  "method": "execute",
  "id": "7159439494928465923",
  "params": {
    "createPayment": {
      "rqTm": "2026-05-12T08:17:13",
      "rqUID": "REG-6926799811555555555",
      "version": "2.0",
      "walletTurns": [
        { "ccBchOperationId": "BCH-OP-1", "ccContractId": "CONTRACT-1" }
      ]
    }
  }
}
```

Поддерживается также позиционный вариант `"params": [ { ... } ]`.

**Ответ (синхронный)**

```json
{
  "jsonrpc": "2.0",
  "id": "7159439494928465923",
  "result": {
    "rqTm": "2026-05-12T08:17:14",
    "rqUID": "REG-6926799811555555555",
    "version": "2.0",
    "executionResults": [
      {
        "transactionId": "<UUID>",
        "operationId": "<32-hex>",
        "bchOperationId": "BCH-OP-1",
        "contractId": "CONTRACT-1",
        "resultStatus": "PPRB_PROCESSING",
        "statusDescription": null
      }
    ]
  }
}
```

`resultStatus` принимает три значения:

| Значение | Когда |
|---|---|
| `PPRB_PROCESSING` | sync принято, документ отправлен в PGW, ждём квитанцию |
| `PPRB_FAILED` | ошибка на этапе lookup / обогащения / pacs.008 / PGW-send |
| `PPRB_EXECUTED` | sync не выдаёт; приходит **только в outbound callback** после квитанции |

### Эндпоинт 2: приём квитанции от PGW

`POST /upd/response/execute?correlationId=...&idempotencyKey=...`
Content-Type: `application/json`

```json
{
  "updUID": "1f4d95079648463383d152bacd6646ce",
  "resultStatus": "SUCCESS",
  "statusInfo": {
    "code": "300",
    "message": "Документ успешно обработан"
  },
  "operationDto": "...",
  "msgAttributes": { "receiverModuleId": "crediting-payment" }
}
```

Возвращает `200 OK` с `ApiResult` синхронно. Любая внутренняя ошибка
не пробрасывается наружу (PGW нужен только факт получения).

В корпе путь полный: `/stmnt-module-x-payment-server/upd/response/execute` —
префикс добавляет ingress.

### Outbound callback инициатору

После финальной квитанции отправляется (если `result-callback.enabled=true`)
на `result-callback.url`:

```http
POST {result-callback.url}
Content-Type: application/json

{
  "transactionId": "<UUID>",
  "operationId": "<32-hex>",
  "bchOperationId": "BCH-OP-1",
  "contractId": "CONTRACT-1",
  "resultStatus": "PPRB_EXECUTED",
  "statusDescription": null
}
```

При `PPRB_FAILED` поле `statusDescription` содержит текст ошибки от PGW.

---

## Модель данных

Полная схема DataSpace — [model/modulex.xml](createpayment-modulex-service/src/main/resources/model/modulex.xml).
Сохранена в исходном стиле с маркерами `isDeprecated="true"` для устаревших полей.

Ключевые сущности:

| Класс | Назначение | Уникальный индекс |
|---|---|---|
| `RegisterWallet` | регистр кошелька (счёт) | `ccRegisterId` |
| `WalletTurn` | оборот кошелька из blockchain | `ccBchOperationId`, `ccTxId` |
| `TurnDocdata` | банковский документ оборота | `ccTransactionId` |
| `StatusWalletTurn` | статусы платежа | **`(ccWalletTurnObjectId, ccStatus)`** |
| `BlockOffset` / `Block` | техтаблицы обработки блоков | `ccBlockId` |
| `DataFeed` / `DataFeedValue` | фиды blockchain | `ccFeedId` |
| `TokenType` / `BchToken` | типы и сами токены | `ccTokenTypeId` / `ccTokenId` |

В `StatusWalletTurn` главная колонка — `ccWalletTurnObjectId` (раньше
называлась `ccBchOperationId` — переименована на уровне БД).

---

## Конфигурация

### `application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: createpayment-modulex-service
  jackson:
    serialization:
      write-dates-as-timestamps: false
    date-format: "yyyy-MM-dd'T'HH:mm:ss"

# Исходящий вызов sberIntegration
sber-integration:
  url: http://stmnt-http.apps.bcivthq2.k8s.delta.sbrf.ru/sberintegration-statement-server/execute
  method: getSberIntegration
  version: "1.0"
  connect-timeout-ms: 5000
  read-timeout-ms: 15000
  bic-directory: false

# Исходящий вызов PGW
pgw:
  enabled: true
  url: https://ingress-pgw-4g-ift.https.dev-sh5.ocp-geo.delta.sbrf.ru
  transfer-path: /upd/transfer
  connect-timeout-ms: 5000
  read-timeout-ms: 30000
  max-attempts: 3
  retry-delay-ms: 30000   # минимум 30 сек по спеке гарант-доставки

# Outbound callback инициатору после финальной квитанции
result-callback:
  enabled: false          # включи после согласования URL
  url: ""
  connect-timeout-ms: 5000
  read-timeout-ms: 15000
  max-attempts: 3
  retry-delay-ms: 5000

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics

logging:
  level:
    ru.sbrf.pprb.stmnt.modulex: DEBUG
```

### Что включить, чтобы пайплайн заработал на dev-стенде

1. `pgw.url` — указывает на конкретный ИФТ-стенд PGW.
2. `result-callback.enabled: true` + `result-callback.url` — URL инициатора, который должен получать финальные ExecutionResult'ы.
3. `sber-integration.url` — sberIntegration ИФТ.

При первом запуске без DataSpace-имплементации репозитории работают
in-memory — поведение видно в логах (`InMemoryWalletTurnRepository.put`,
`turn_docdata save`, `status_WalletTurn upsert`).

---

## Структура проекта

```
createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/
├── api/
│   ├── CreatePaymentService.java                    @JsonRpcService("/api/createPayment")
│   └── dto/
│       ├── CreatePayment.java                       вход — массив walletTurns
│       ├── WalletTurnInput.java                     { ccBchOperationId, ccContractId }
│       ├── CreatePaymentResponse.java               { executionResults: [...] }
│       ├── ExecutionResult.java                     результат по одной walletTurn
│       ├── ExecutionStatus.java                     PROCESSING / EXECUTED / FAILED
│       ├── WalletTurn.java                          полная walletTurn-сущность
│       └── TurnDocdataDraft.java                    проекция turn_docdata (50+ полей)
│
├── config/
│   ├── AppConfig.java                               бины, REST templates
│   ├── SberIntegrationProperties.java
│   ├── PgwProperties.java
│   └── ResultCallbackProperties.java
│
├── rpc/
│   ├── CreatePaymentServiceImpl.java                JSON-RPC реализация
│   └── ExecuteResponseController.java               POST /upd/response/execute
│
├── lib/
│   ├── CreatePaymentLibrary.java                    основная бизнес-логика
│   ├── Pacs008Builder.java                          сборка ISO 20022 XML
│   ├── TurnDocdataIdGenerator.java                  UUID, 32-hex, 6-digit docNum
│   ├── TurnDocdataDefaults.java                     константы (DT_DEBIT=1, и т.д.)
│   ├── CcStatusMapper.java                          PGW status → ccStatus
│   ├── ExecuteResponseHandler.java                  обработчик квитанции PGW
│   ├── WalletTurnRepository.java                    интерфейсы DataSpace
│   ├── TurnDocdataRepository.java
│   ├── StatusWalletTurnRepository.java
│   ├── InMemoryWalletTurnRepository.java            in-memory моки DataSpace
│   ├── InMemoryTurnDocdataRepository.java           (@ConditionalOnMissingBean)
│   ├── InMemoryStatusWalletTurnRepository.java
│   └── StatusWalletTurnUpdate.java
│
├── integration/
│   ├── sber/
│   │   ├── SberIntegrationClient.java               4 операции: register / ucp / division / bicDir
│   │   ├── SberIntegrationClientImpl.java           RestTemplate, JSON-RPC envelope
│   │   └── dto/ ...
│   ├── pgw/
│   │   ├── PgwClient.java                           transferUpd с гарант-доставкой
│   │   ├── PgwClientImpl.java
│   │   └── dto/UPDDTO.java, ApiResult.java, ResponseTicketRequest.java, ...
│   └── callback/
│       ├── ResultCallbackClient.java                outbound POST инициатору
│       └── ResultCallbackClientImpl.java
│
└── validator/SimpleValidator.java
```

---

## Сборка и запуск

### В корп. контуре (с доступом к Nexus Сбера)

```bash
mvn clean install
mvn -pl createpayment-modulex-service spring-boot:run
```

или готовый jar:

```bash
java -jar createpayment-modulex-service/target/createpayment-modulex-service-1.0-SNAPSHOT.jar
```

ApplicationLauncher идёт из зависимости `simpleservicemodulex`.

### Локально (без Nexus, для отладки)

Корневой `pom.xml` наследует `dataspace-bom` из закрытого Nexus, его
снаружи не достать. Для локального запуска есть изолированная сборка:

```bash
mvn -f _local-run/pom.xml clean package
java -jar _local-run/target/createpayment-local-run-0.0.1-SNAPSHOT.jar
```

Она:
- использует `spring-boot-starter-parent` с Maven Central
- стартует на порту 8080
- подменяет `SberIntegrationClient` и `PgwClient` стабами (без сети)
- использует in-memory DataSpace-репозитории
- даёт быстрый smoke-тест пайплайна (`curl localhost:8080/api/createPayment`)

### Health / метрики

```
GET /actuator/health
GET /actuator/prometheus
GET /actuator/metrics
```

---

## Тесты

**46/46 проходят** (`mvn test` через `_test-runner/pom.xml` — наследует
`spring-boot-starter-parent` из Maven Central, чтобы запускаться без
корп. Nexus).

| Класс | Что покрывает | Тестов |
|---|---|---|
| [Pacs008BuilderTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/Pacs008BuilderTest.java) | сборка XML по спеке (TaxRmt, BrnchId, CreDtTm с offset, и т.д.) | 16 |
| [CcStatusMapperTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/CcStatusMapperTest.java) | маппинг PGW status + code → ccStatus | 7 |
| [CreatePaymentLibraryTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/CreatePaymentLibraryTest.java) | основной поток с in-mem репо | 6 |
| [ExecuteResponseHandlerTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/ExecuteResponseHandlerTest.java) | обработка квитанции + ResultCallback | 5 |
| [TurnDocdataIdGeneratorTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/TurnDocdataIdGeneratorTest.java) | формат и уникальность UUID / docNum | 5 |
| [SberRequestSerializationTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/integration/sber/dto/SberRequestSerializationTest.java) | сериализация запросов к sberIntegration (rqTm как ISO-строка) | 4 |
| [CreatePaymentEndToEndTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/CreatePaymentEndToEndTest.java) | **сквозной** — sync execute → PGW callback → ResultCallback | 3 |

### Запуск тестов

Внутри корп. контура (с доступом к Nexus):

```bash
mvn test
```

Снаружи (без Nexus, через изолированный runner):

```bash
mvn -f _test-runner/pom.xml test
```

### Структура мок-окружения

Чтобы не дёргать реальные интеграции, в тестах используется:

- `FakeSberIntegrationClient` — fluent fixture: `.putRegister(...)`, `.putUcp(...)`, `.putDivision(...)`, `.putBic(...)`. Не Mockito.
- `InMemoryWalletTurnRepository / TurnDocdataRepository / StatusWalletTurnRepository` — те же бины, что и в проде, только с in-memory `Map`. Реальная DataSpace-имплементация заменит их через `@ConditionalOnMissingBean`.
- `CapturingPgwClient` / `CapturingResultCallback` — простые in-memory сборщики обращений (см. [CreatePaymentEndToEndTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/CreatePaymentEndToEndTest.java)).

### Сквозной сценарий (`CreatePaymentEndToEndTest`)

Проверяет ровно тот flow, что описан выше:

1. JSON-RPC execute — sync вернул `PPRB_PROCESSING`.
2. Проверено: `pacs.008` в UPDDTO содержит обогащённые имена клиентов, БИКи, кодов филиалов.
3. Проверено: turn_docdata записан, `status_WalletTurn` в `PPRB_PROCESSING`.
4. Симулируем квитанцию PGW с кодом 300 (финальный).
5. Проверено: появилась запись `status_WalletTurn` в `PPRB_EXECUTED`.
6. Проверено: `ResultCallbackClient.send(...)` дёрнут с правильным `ExecutionResult`.

---

## Roadmap

Что готово ✅:

- JSON-RPC `/api/createPayment` с упрощённым входом (`ccBchOperationId`, `ccContractId`).
- Sber-обогащение: 4 разных вызова (registerId, ucpId, divisionId, bicDirectory).
- Сборка pacs.008.001.08 с BrnchId и mandatory TaxRmt-заглушкой.
- Отправка в PGW с гарант-доставкой.
- Приём квитанции `/upd/response/execute` с маппингом PGW status → ccStatus.
- Outbound REST callback с финальным `ExecutionResult` инициатору.
- In-memory DataSpace моки (3 репозитория), сквозной тест.

Что осталось 🔜:

- **Реальная DataSpace-имплементация** трёх репозиториев — через `DataSpaceApi` (`searchRegisterWallet`, `execute(Packet)` и т.д.). Подключается одним `@Component` на каждый репозиторий; in-memory моки автоматически отступят.
- **Конвертация валют**: сейчас `ccValuta*` всегда `810`. Подключить к `FSKK.accCurrency`.
- **Курсы ЦБ**: `ccRateDT/KT` сейчас `1`. Нужен сервис курсов.
- **`ccStartSum / ccStartSumNAT`**: считать для операций типа `ccTypeOper=50`.
- **OCC/Sanctions/Compliance**: согласовать в `msgAttributes` к PGW.
- **Подписи pacs.008**: с 1Q2026 для документов в ЦБ обязательны (`SplmtryData/Signature`).
- **Реальный outbound URL** в `result-callback`: пока требует ручной конфигурации, можно расширить до per-payment URL в `msgAttributes`.
