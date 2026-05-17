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
- [Подключение реального DataSpace](#подключение-реального-dataspace)
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
| `WalletTurn` | оборот кошелька из blockchain | `(ccBchOperationId, ccContractId)` |
| `TurnDocdata` | банковский документ оборота | `ccTransactionId` |
| `StatusWalletTurn` | статусы платежа | **`(ccWalletTurnObjectId, ccStatus)`** |
| `BlockOffset` / `Block` | техтаблицы обработки блоков | `ccBlockId` |
| `DataFeed` / `DataFeedValue` | фиды blockchain | `ccFeedId` |
| `TokenType` / `BchToken` | типы и сами токены | `ccTokenTypeId` / `ccTokenId` |

В `StatusWalletTurn` главная колонка — `ccWalletTurnObjectId` (раньше
называлась `ccBchOperationId` — переименована на уровне БД).

---

## Конфигурация

### Конфиги и профили

| Файл / профиль | Назначение |
|---|---|
| [`application.yml`](createpayment-modulex-service/src/main/resources/application.yml) | Дефолты, ориентированы на IFT-стенд. Все URL допускают перебивку через env-переменную (`${DATASPACE_URL:default}` и т.д.) |
| [`application-prod.yml`](createpayment-modulex-service/src/main/resources/application-prod.yml) | Production-профиль. Все URL **обязательны** из env (fail-fast), таймауты приподняты, `health-details: never`, лог `INFO/WARN` |
| [`.env.prod.example`](createpayment-modulex-service/.env.prod.example) | Шаблон env-переменных для прода: каждый URL отдельно, плюс тюны таймаутов |

### Включение prod-профиля

```bash
# через переменную окружения
SPRING_PROFILES_ACTIVE=prod \
DATASPACE_URL=http://dataspace.prod/... \
PGW_URL=https://pgw.prod/... \
RESULT_CALLBACK_URL=https://caller.prod/callback \
SBER_INTEGRATION_URL=http://sber-integration.prod/... \
java -jar createpayment-modulex-service.jar

# либо JVM-флагом
java -jar app.jar --spring.profiles.active=prod
```

### Ключевые URL — env-переменные

| Свойство (yml) | Env-переменная | Назначение |
|---|---|---|
| `dataspace.url` | `DATASPACE_URL` | URL DataSpace для `DataSpaceApi` |
| `pgw.url` | `PGW_URL` | Базовый URL PGW для `transferUpd` |
| `result-callback.url` | `RESULT_CALLBACK_URL` | Куда POST'ить финальный `ExecutionResult` |
| `sber-integration.url` | `SBER_INTEGRATION_URL` | URL sberIntegration JSON-RPC |
| `result-callback.enabled` | `RESULT_CALLBACK_ENABLED` | `true` — реально слать; `false` — только лог |
| `pgw.enabled` | `PGW_ENABLED` | `false` — отключить отправку в PGW (для dev) |

### Что включить, чтобы пайплайн заработал на dev-стенде

1. `DATASPACE_URL` — DataSpace стенда (если будет реальная DataSpace-имплементация).
2. `PGW_URL` — ИФТ-стенд PGW.
3. `RESULT_CALLBACK_ENABLED=true` + `RESULT_CALLBACK_URL` — URL инициатора, который должен получать финальные ExecutionResult'ы.
4. `SBER_INTEGRATION_URL` — sberIntegration ИФТ.

Без DataSpace-имплементации репозитории работают in-memory — поведение
видно в логах (`turn_docdata save`, `status_WalletTurn upsert`).

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

#### Доменные метрики

| Метрика | Тип | Тэги | Описание |
|---|---|---|---|
| `idempotency_cache_hits` | counter | `level=l1\|l2` | Попадание в кэш идемпотентности |
| `idempotency_cache_misses` | counter | `level=l1` | Промах в L1 (потом идём в L2) |
| `idempotency_cache_saves` | counter | — | Сохранения SUCCESS-результатов |
| `idempotency_cache_skipped_non_success` | counter | — | Не сохранили ERROR-ApiResult |
| `idempotency_cache_l1_size` | gauge | — | Текущий размер L1 |
| `pgw_transfer_upd_success` | counter | — | Успешные sync-attempt в PGW |
| `pgw_transfer_upd_failure` | counter | — | Провальные sync-attempt |
| `pgw_transfer_upd_outbox` | counter | — | Попадание УРД в outbox после провала |
| `pgw_transfer_upd_duration` | timer | — | Длительность sync-attempt |
| `outbox_size` | gauge | `status=PENDING\|SENT\|GIVEUP` | Размер очереди гарант-доставки |
| `outbox_processed` | counter | — | Успешно отправлено воркером |
| `outbox_retry` | counter | — | Попыток воркера (любых) |
| `outbox_giveup` | counter | — | Переходов в GIVEUP |

---

## Тесты

**46/46 проходят** (`mvn test` через `_test-runner/pom.xml` — наследует
`spring-boot-starter-parent` из Maven Central, чтобы запускаться без
корп. Nexus).

| Класс | Что покрывает | Тестов |
|---|---|---|
| [Pacs008BuilderTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/Pacs008BuilderTest.java) | сборка XML по эталону (TaxRmt только КПП, без BrnchId/SplmtryData/«0»-defaults) | 15 |
| [CcStatusMapperTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/CcStatusMapperTest.java) | маппинг PGW status + code → ccStatus | 7 |
| [CreatePaymentLibraryTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/CreatePaymentLibraryTest.java) | основной поток с in-mem репо (вкл. ccDivisionId и ccReceiptDate) | 7 |
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

## Подключение реального DataSpace

Реальные имплементации лежат в [`lib/dataspace/`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/):

| Класс | Что делает |
|---|---|
| [DataSpaceWalletTurnRepository](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/DataSpaceWalletTurnRepository.java) | `findByBchOperationId` → `dsApi.searchWalletTurn` |
| [DataSpaceTurnDocdataRepository](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/DataSpaceTurnDocdataRepository.java) | `save` → `Packet.turnDocdata.create + dsApi.execute(packet)`; `findByOperationId` → `searchTurnDocdata` |
| [DataSpaceStatusWalletTurnRepository](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/DataSpaceStatusWalletTurnRepository.java) | `upsertStatus` → search по `(ccWalletTurnObjectId, ccStatus)`, затем `create` либо `update` |

**Активны** — `@Primary @Component`, инжектят `DataSpaceApi` из corp `simpleservicemodulex`.
В DI они вытесняют in-memory `@Bean`-ы из [`AppConfig`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/config/AppConfig.java).

В локальной сборке (`_test-runner` / `_local-run`, без corp SDK) пакет
`lib/dataspace/` **исключён** из компиляции через
`maven-compiler-plugin <excludes>` — там работают только in-memory `@Bean`.

| Класс | Что делает |
|---|---|
| [`DataSpaceWalletTurnRepository`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/DataSpaceWalletTurnRepository.java) | `searchWalletTurn(g -> g.setWhere(w -> w.ccBchOperationIdEq(...))....withCcXxx())` + маппинг в DTO |
| [`DataSpaceTurnDocdataRepository`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/DataSpaceTurnDocdataRepository.java) | `save` через `Packet.turnDocdata.create(CreateTurnDocdataParam...)`; `findByOperationId` через `searchTurnDocdata` |
| [`DataSpaceStatusWalletTurnRepository`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/DataSpaceStatusWalletTurnRepository.java) | upsert по `(ccWalletTurnObjectId, ccStatus)`: search → если есть `update(StatusWalletTurnRef.of(id), ...)`, иначе `create(...)` |

Зависимость — `DataSpaceApi` из corp `simpleservicemodulex` (бин уже
предоставлен этим артефактом, ничего отдельно конфигурить не нужно
кроме [`dataspace.url`](#-ключевые-url--env-переменные)).

### Локальная сборка

В `_test-runner` и `_local-run` (Maven Central) пакет `lib/dataspace/`
**исключён из компиляции** через `maven-compiler-plugin <excludes>` —
эти классы требуют corp stmnt-model-sdk и DataSpaceApi, которых
в Maven Central нет. На корп. сборке (через основной `pom.xml`)
всё компилируется нормально.

### Если нужно править — пример типового класса

Шаблон оставлен только для справки. Реальные файлы — в `lib/dataspace/`.

```java
@Primary
@Component
public class DataSpaceWalletTurnRepository implements WalletTurnRepository {

    private final DataSpaceApi dsApi;

    public DataSpaceWalletTurnRepository(DataSpaceApi dsApi) {
        this.dsApi = dsApi;
    }

    @Override
    public Optional<WalletTurn> findByBchOperationId(String ccBchOperationId) {
        try {
            GraphCollection<WalletTurnGet> coll = dsApi.searchWalletTurn(g -> g
                    .ccBchOperationId().equal(ccBchOperationId)
                    .with().ccDate().ccBchOperationId().ccTxId().ccBlockNumber()
                            .ccContractId().ccOwnerDt().ccRegisterDt().ccOwnerKt().ccRegisterKt()
                            .ccSum().ccDateDoc().ccPurpose().ccOperationId().ccTransactionId()
                            .ccRqTm().ccRqUId().ccSignature().sysLastChangeDate());
            return coll.stream().findFirst().map(this::map);
        } catch (SdkJsonRpcClientException e) {
            throw new IllegalStateException("WalletTurn lookup failed", e);
        }
    }

    private WalletTurn map(WalletTurnGet g) {
        return WalletTurn.builder()
                .ccDate(g.getCcDate())
                .ccBchOperationId(g.getCcBchOperationId())
                .ccTxId(g.getCcTxId())
                .ccBlockNumber(g.getCcBlockNumber())
                .ccContractId(g.getCcContractId())
                .ccOwnerDt(g.getCcOwnerDt())
                .ccRegisterDt(g.getCcRegisterDt())
                .ccOwnerKt(g.getCcOwnerKt())
                .ccRegisterKt(g.getCcRegisterKt())
                .ccSum(g.getCcSum())
                .ccDateDoc(g.getCcDateDoc())
                .ccPurpose(g.getCcPurpose())
                .ccOperationId(g.getCcOperationId())
                .ccTransactionId(g.getCcTransactionId())
                .ccRqTm(g.getCcRqTm())
                .ccRqUId(g.getCcRqUId())
                .ccSignature(g.getCcSignature())
                .sysLastChangeDate(g.getSysLastChangeDate())
                .build();
    }
}
```

Аналогично:

```java
@Primary
@Component
public class DataSpaceTurnDocdataRepository implements TurnDocdataRepository {

    private final DataSpaceApi dsApi;
    public DataSpaceTurnDocdataRepository(DataSpaceApi dsApi) { this.dsApi = dsApi; }

    @Override
    public void save(TurnDocdataDraft draft) {
        Packet packet = new Packet();
        packet.turnDocdata.create(CreateTurnDocdataParam.create()
                .setCcRegisterId(draft.getCcRegisterId())
                .setCcOperationId(draft.getCcOperationId())
                .setCcTransactionId(draft.getCcTransactionId())
                // … остальные поля по аналогии
        );
        try { dsApi.execute(packet); }
        catch (SdkJsonRpcClientException e) { throw new IllegalStateException(e); }
    }

    @Override
    public Optional<TurnDocdataDraft> findByOperationId(String ccOperationId) {
        // searchTurnDocdata + маппинг → TurnDocdataDraft
        // …
    }
}

@Primary
@Component
public class DataSpaceStatusWalletTurnRepository implements StatusWalletTurnRepository {

    private final DataSpaceApi dsApi;
    public DataSpaceStatusWalletTurnRepository(DataSpaceApi dsApi) { this.dsApi = dsApi; }

    @Override
    public void upsertStatus(StatusWalletTurnUpdate u) {
        // upsert по (ccWalletTurnObjectId, ccStatus) через searchStatusWalletTurn + update/create
    }
}
```

### Бин `DataSpaceApi`

Корп. `simpleservicemodulex` уже создаёт `DataSpaceApi`-бин из конфига вида:

```yaml
dataspace:
  url: http://dataspace-...
  connection-timeout: 5000
  read-timeout: 30000
```

(точные имена ключей — у вашего `simpleservicemodulex`).
Просто заинжекти `DataSpaceApi` в свои `@Component`-имплементации, бин уже есть.

### Почему `@Primary`, а не `@ConditionalOnMissingBean`

`@ConditionalOnMissingBean` на `@Component`-классе ненадёжно работает в
Spring Boot 3.5 — может не зарегистрировать бин даже когда других нет.
`@Primary` явно говорит «при наличии нескольких — используй меня», что
гарантированно вытесняет in-memory fallback.

После добавления реальных репозиториев in-memory можно удалить совсем
(или оставить как тестовые стабы).

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
- `ccDivisionId` резолвится из `FSKK_DT.divisionId`; `ccReceiptDate` ставится `now()` на приёме `execute`.
- Индексы `WalletTurn` в [model/modulex.xml](createpayment-modulex-service/src/main/resources/model/modulex.xml) синхронизированы с актуальным DDL: уникальность по `(ccBchOperationId, ccContractId)`, обычный индекс по `ccTxId`.
- `ResultCallbackClientImpl` self-contained: собирает собственный `RestTemplate` через `RestTemplateBuilder` — устранена ошибка wiring при stale-сборке.
- In-memory репозитории зарегистрированы явными `@Bean` методами в [`AppConfig`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/config/AppConfig.java) — bulletproof в любой корп. ComponentScan конфигурации. Сами классы остались plain POJO без Spring-аннотаций.
- Шаблоны DataSpace-имплементаций в [`lib/dataspace/`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/) — пока неактивны (нет `@Primary`, тело `UnsupportedOperationException`). Активируются после регенерации `modulex-model-sdk` из текущего [`modulex.xml`](createpayment-modulex-service/src/main/resources/model/modulex.xml).
- DSL Platform V DataSpace SDK (для будущей разкомментировки): фильтр `.setWhere(w -> w.ccXxxEq(...))`, проекция `.withCcXxx()`, обновление `entity.update(EntityRef.of(id), updateParam)`, конвертация `LocalDateTime → java.util.Date` для `ccRqTm`.
- `Pacs008Builder` приведён к минимальному эталону: убраны `BrnchId`, `SplmtryData`, MVP-«0»-заглушки `RegnId/AdmstnZone/RefNb/Mtd` в `TaxRmt`. Секции выпускаются только при наличии реальных значений.
- HTTP-логирование запросов и ответов всех интеграций: outbound (`HttpLoggingInterceptor` на sber/pgw/result-callback `RestTemplate`'ах через `BufferingClientHttpRequestFactory`) + inbound (`HttpRequestLoggingFilter` через `ContentCachingRequest/ResponseWrapper`). Уровень `DEBUG` — пишем тело целиком (до 16 КБ), маскируем `Authorization`/cookie. Логгер `ru.sbrf.pprb.stmnt.modulex.logging`: на dev `DEBUG`, на prod `WARN` (включаем через `LOG_LEVEL_HTTP=DEBUG` при разборах).
- Конфиг переведён на env-var pattern (`${VAR:default}`) для всех URL. Добавлен профиль [`application-prod.yml`](createpayment-modulex-service/src/main/resources/application-prod.yml) (fail-fast при отсутствии prod-URL) и шаблон [`.env.prod.example`](createpayment-modulex-service/.env.prod.example).

Что осталось 🔜:

- **Прогнать реальные DataSpace-имплементации** (уже написаны в [`lib/dataspace/`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/)) против corp DataSpace и при необходимости довести точную сигнатуру graph-DSL до соответствия `stmnt-model-sdk` твоей версии.
- **Конвертация валют**: сейчас `ccValuta*` всегда `810`. Подключить к `FSKK.accCurrency`.
- **Курсы ЦБ**: `ccRateDT/KT` сейчас `1`. Нужен сервис курсов.
- **`ccStartSum / ccStartSumNAT`**: считать для операций типа `ccTypeOper=50`.
- **OCC/Sanctions/Compliance**: согласовать в `msgAttributes` к PGW.
- **Подписи pacs.008**: с 1Q2026 для документов в ЦБ обязательны (`SplmtryData/Signature`).
- **Реальный outbound URL** в `result-callback`: пока требует ручной конфигурации, можно расширить до per-payment URL в `msgAttributes`.
