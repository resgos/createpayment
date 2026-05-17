# module-x-pprb · createpayment-modulex-service

Spring Boot модуль ППРБ.Выписка ЮЛ: принимает запрос на создание платежа из
blockchain-оборота, обогащает реквизиты через `sberIntegration`, собирает
ISO 20022 `pacs.008.001.08` и отправляет в PGW. Затем асинхронно получает
квитанцию исполнения и передаёт финальный результат в REST инициатора.

```
[Caller] ── JSON-RPC execute ─▶ [module-x] ── transferUpd ─▶ [PGW] ──▶ Исполнение
                                    │  │
                                    │  └── sync fail → upd_outbox(PENDING) ─▶ PgwOutboxWorker
                                    ▼
                              status_WalletTurn(PPRB_GET → PPRB_STARTED)

[PGW] ── /upd/response/execute ─▶ [module-x] ── POST ExecutionResult ─▶ [Caller's REST]
                                    │  │
              IdempotencyCache ◀────┘  └─▶ turn_docdata (только на final)
              (L1 in-mem + L2 DataSpace)   status_WalletTurn(PROCESSING/EXECUTED/FAILED)
```

## Содержание

- [Архитектура](#архитектура)
- [Поток обработки](#поток-обработки)
- [API](#api)
- [Модель данных](#модель-данных)
- [Идемпотентность и гарант-доставка](#идемпотентность-и-гарант-доставка)
- [Метрики и наблюдаемость](#метрики-и-наблюдаемость)
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

### Жизненный цикл walletTurn в `status_WalletTurn`

5 фаз, по одной записи на каждую (уник-индекс `(ccWalletTurnId, ccStatus)`):

| Фаза | Когда пишется | Где |
|---|---|---|
| `PPRB_GET` | приняли запрос createPayment, провалидировали, сгенерили ID | sync |
| `PPRB_STARTED` | PGW принял УРД (sync `transferUpd` вернул ok) | sync |
| `PPRB_PROCESSING` | PGW квитанция с кодами 202..299 (промежуточный) | async |
| `PPRB_EXECUTED` | PGW квитанция с кодами 300/301/315 (финал-успех) | async |
| `PPRB_FAILED` | сбой пайплайна на нашей стороне ИЛИ PGW коды 100..199 | sync/async |

### 1. Sync приём (JSON-RPC `/api/createPayment`)

```
1. Валидация (ccBchOperationId непустой, walletTurns не null)
2. Генерация ccOperationId (32-hex) и ccTransactionId (UUID) — ДО try
3. WalletTurnRepository.findByBchOperationId(ccBchOperationId)
4. StatusWalletTurnRepository.upsert(PPRB_GET)
5. Sber-обогащение DT и KT: FSKK (registerId) → EPK (ucpId) → SFS (divisionId)
6. Один раз на батч — bicDirectory (NSI.participant), кладётся в Map<BIC, ...>
7. Сборка TurnDocdataDraft + applyBicDirectory + applyContraFromKt
8. Pacs008Builder.build(draft) → XML pacs.008.001.08
9. PgwClient.transferUpd(requestId=ccRqUId, UPDDTO{...,originalMessage=XML})
10. StatusWalletTurnRepository.upsert(PPRB_STARTED)
11. Return executionResult.resultStatus = PPRB_PROCESSING (синхронный stub)
```

**turn_docdata в синке НЕ сохраняется** — он создаётся только после прихода
квитанции от PGW (со всеми реальными данными исполнения).

При любой ошибке до PGW-send → `PPRB_FAILED` с описанием в `ccStatusDesc` +
`PPRB_FAILED` в синхронном ответе клиенту.

### 2. Async квитанция (REST `/upd/response/execute`)

```
0. IdempotencyCache.find(idempotencyKey) → HIT? вернуть кеш, ничего не делать
1. CcStatusMapper(resultStatus, statusInfo.code) → ccStatus:
   SUCCESS + 300/301/315  → PPRB_EXECUTED
   SUCCESS + 202..299     → PPRB_PROCESSING
   ERROR   + 100..199     → PPRB_FAILED
2. StatusWalletTurnRepository.findFirstByOperationId(updUID)
   - получаем ccWalletTurnObjectId / ccTransactionId из PPRB_STARTED-строки
   - если строки нет → return ERROR ApiResult → PGW повторит (гарант-доставка)
3. Если ccStatus финальный (EXECUTED/FAILED):
   - PgwOperationDtoParser.parse(operationDto) → TurnDocdataDraft
   - TurnDocdataRepository.save(draft) — идемпотентно (skip if exists)
4. StatusWalletTurnRepository.upsert(новый ccStatus)
5. Если финальный → ResultCallbackClient.send(ExecutionResult) инициатору
6. IdempotencyCache.save(SUCCESS только) → L1 in-memory + L2 DataSpace
7. Sync вернуть PGW: ApiResult.status=SUCCESS
```

### Что бывает на нестандартных сценариях

| Сценарий | Поведение |
|---|---|
| PGW квитанция пришла РАНЬШЕ записи PPRB_STARTED | Возвращаем `ERROR` ApiResult → PGW повторит позже (механизм гар-доставки) |
| Дубль PGW callback с тем же `idempotencyKey` | L1/L2 cache hit → возврат кеша без переобработки бизнес-логики |
| Sync `transferUpd` упал (сеть/timeout) | УРД в `upd_outbox` со status=PENDING, `PgwOutboxWorker` ретраит в фоне с тем же `requestId` |
| Sber-сбой на одной из сторон | Поля `ccDT*`/`ccKT*` остаются `null`, остальные секции выпускаются |
| `outbox.maxAttempts` исчерпан | Запись переходит в `GIVEUP`, метрика `outbox_giveup` инкрементится |

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

`resultStatus` в sync-ответе принимает два значения, в outbound callback — все:

| Значение | Где встречается | Когда |
|---|---|---|
| `PPRB_PROCESSING` | sync + outbound | sync принято, отправлено в PGW (или промежуточный код от PGW 202..299) |
| `PPRB_FAILED` | sync + outbound | sync: ошибка lookup / обогащения / pacs.008 / PGW-send. outbound: PGW коды 100..199 |
| `PPRB_EXECUTED` | только outbound | PGW коды 300/301/315 |

В `status_WalletTurn` дополнительно пишутся `PPRB_GET` и `PPRB_STARTED` —
внутренние промежуточные состояния для трассировки, наружу не отдаются.

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
| `StatusWalletTurn` | статусы платежа (PPRB_GET/STARTED/PROCESSING/EXECUTED/FAILED) | **`(ccWalletTurnObjectId, ccStatus)`** |
| `ResponseTicketIdempotency` | L2-кэш приёма PGW callback (защита от дублей) | `ccIdempotencyKey` |
| `UpdOutbox` | гарант-доставка УРД в PGW (background retry) | `ccRequestId` |
| `BlockOffset` / `Block` | техтаблицы обработки блоков | `ccBlockId` |
| `DataFeed` / `DataFeedValue` | фиды blockchain | `ccFeedId` |
| `TokenType` / `BchToken` | типы и сами токены | `ccTokenTypeId` / `ccTokenId` |

В `StatusWalletTurn` главная колонка — `ccWalletTurnObjectId` (раньше
называлась `ccBchOperationId` — переименована на уровне БД). В коде работаем
**только по натуральным ключам** (`ccWalletTurnId`, `ccOperationId`,
`ccTransactionId`, `ccStatus`) — никакого синтетического `objectId`.

---

## Идемпотентность и гарант-доставка

### IdempotencyCache (приём ResponseTicket)

Контракт PGW: на дубль ResponseTicket с тем же `idempotencyKey` ПФ-инициатор
обязан вернуть **статус первого** полученного документа, не обрабатывая
бизнес-логику повторно.

Двухуровневый кэш в [`IdempotencyCache`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/IdempotencyCache.java):

```
ResponseTicket → find(idempotencyKey)
                       │
                       ├── L1 (in-memory ConcurrentHashMap, TTL 1ч) ── HIT? return
                       │
                       └── L2 (DataSpace responseTicket_idempotency) ── HIT? warm L1, return
                                                                    └── MISS → обработка
```

Сохраняем **только SUCCESS-ApiResult** — ERROR не кэшируем, чтобы PGW мог
повторить и получить уже валидный ответ.

### UpdOutbox + PgwOutboxWorker (отправка УРД)

`PgwClientImpl.transferUpd` делает **одну синхронную попытку**. При успехе —
возвращаем `ApiResult`. При сбое — кладём УРД в [`UpdOutbox`](createpayment-modulex-service/src/main/resources/model/modulex.xml)
со статусом `PENDING`, и [`PgwOutboxWorker`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/outbox/PgwOutboxWorker.java)
(`@Scheduled`, каждые `pgw.outbox.poll-interval-ms`, default 60 сек) пересылает
с тем же `requestId` (PGW дедуплицирует по этому ключу).

```
sendToPgw (sync)
     │
     ├── success → return ApiResult
     │
     └── fail → enqueue UpdOutbox(PENDING, attempts=1, nextRetryAt = now + 3 мин)
                                          │
                            PgwOutboxWorker (каждые 60с)
                                          │
                            transferUpd с тем же requestId
                                          │
                          ┌───────────────┼───────────────┐
                     success           fail + a<max   fail + a≥max
                          │                │                │
                        SENT          nextRetry += 3 мин   GIVEUP
```

Tomcat-тред клиента **не блокируется** ретраями — sync возвращает максимум
через `pgw.read-timeout-ms` (default 30 сек).

### Параметры (env)

| Env | Default (dev) | Default (prod) | Что |
|---|---|---|---|
| `PGW_MAX_ATTEMPTS` | 3 | 5 | Лимит попыток (контракт: 5–10) |
| `PGW_RETRY_DELAY_MS` | 5000 | 180000 | Интервал между попытками (контракт: 3–5 мин) |
| `PGW_OUTBOX_POLL_MS` | 60000 | 60000 | Период тика воркера |
| `PGW_OUTBOX_INITIAL_DELAY_MS` | 30000 | 30000 | Задержка первого тика после старта |

---

## Метрики и наблюдаемость

Все метрики через Micrometer, экспортируются в Prometheus и JMX:

```
GET /actuator/prometheus
GET /actuator/metrics
GET /actuator/health
```

### Доменные метрики

| Метрика | Тип | Тэги | Описание |
|---|---|---|---|
| `idempotency_cache_hits` | counter | `level=l1\|l2` | Попадание в кэш идемпотентности |
| `idempotency_cache_misses` | counter | `level=l1` | Промах L1 (потом идём в L2) |
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

Gauge `outbox_size{status}` обновляется фоном раз в 30 сек (`pgw.outbox.metrics-refresh-ms`),
а не на каждый Prometheus scrape — не дёргаем DataSpace зря.

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
│       ├── ExecutionStatus.java                     GET / STARTED / PROCESSING / EXECUTED / FAILED
│       ├── WalletTurn.java                          полная walletTurn-сущность
│       └── TurnDocdataDraft.java                    проекция turn_docdata
│
├── config/
│   ├── AppConfig.java                               бины, REST templates, @EnableScheduling
│   ├── SberIntegrationProperties.java
│   ├── PgwProperties.java                           + outbox.poll-interval/initial-delay
│   └── ResultCallbackProperties.java
│
├── rpc/
│   ├── CreatePaymentServiceImpl.java                JSON-RPC реализация
│   └── ExecuteResponseController.java               POST /upd/response/execute
│
├── lib/
│   ├── CreatePaymentLibrary.java                    основная бизнес-логика
│   ├── Pacs008Builder.java                          сборка ISO 20022 XML
│   ├── PgwOperationDtoParser.java                   парсер operationDto → TurnDocdataDraft
│   ├── TurnDocdataIdGenerator.java                  UUID, 32-hex, 6-digit docNum
│   ├── TurnDocdataDefaults.java                     константы (DT_DEBIT=1, и т.д.)
│   ├── CcStatusMapper.java                          PGW status → ccStatus
│   ├── ExecuteResponseHandler.java                  обработчик квитанции PGW + idempotency
│   ├── IdempotencyCache.java                        L1 (in-mem) + L2 (DataSpace) facade
│   ├── IdempotencyStore.java                        интерфейс L2-хранилища
│   ├── InMemoryIdempotencyStore.java                in-mem fallback
│   ├── WalletTurnRepository.java                    интерфейсы DataSpace
│   ├── TurnDocdataRepository.java
│   ├── StatusWalletTurnRepository.java              + findFirstByOperationId
│   ├── StatusWalletTurnUpdate.java
│   ├── StatusWalletTurnView.java                    read-only DTO для callback-flow
│   ├── InMemoryWalletTurnRepository.java            in-memory моки DataSpace
│   ├── InMemoryTurnDocdataRepository.java           (через @Bean в AppConfig)
│   ├── InMemoryStatusWalletTurnRepository.java
│   ├── dataspace/
│   │   ├── DataSpaceWalletTurnRepository.java       @Primary @Component
│   │   ├── DataSpaceTurnDocdataRepository.java
│   │   ├── DataSpaceStatusWalletTurnRepository.java idempotent create по натуральным ключам
│   │   ├── DataSpaceIdempotencyStore.java           L2-имплементация
│   │   └── DataSpaceUpdOutboxRepository.java        outbox-сущность
│   └── outbox/
│       ├── UpdOutboxEntry.java                      DTO с requestId, payload, status, attempts
│       ├── UpdOutboxRepository.java                 интерфейс (save, findByRequestId, findPending, countByStatus)
│       ├── InMemoryUpdOutboxRepository.java         in-mem fallback
│       ├── PgwOutboxWorker.java                     @Scheduled retry-воркер
│       └── OutboxMetrics.java                       @Scheduled обновление gauge size{status}
│
├── integration/
│   ├── sber/
│   │   ├── SberIntegrationClient.java               4 операции: register / ucp / division / bicDir
│   │   ├── SberIntegrationClientImpl.java           RestTemplate, JSON-RPC envelope
│   │   └── dto/ ...
│   ├── pgw/
│   │   ├── PgwClient.java                           transferUpd (один sync-attempt + outbox)
│   │   ├── PgwClientImpl.java
│   │   └── dto/UPDDTO.java, ApiResult.java, ResponseTicketRequest.java, ...
│   └── callback/
│       ├── ResultCallbackClient.java                outbound POST инициатору
│       └── ResultCallbackClientImpl.java
│
├── logging/
│   ├── HttpLoggingInterceptor.java                  outbound RestTemplate (с body)
│   └── HttpRequestLoggingFilter.java                inbound через ContentCaching wrapper
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

См. секцию [Метрики и наблюдаемость](#метрики-и-наблюдаемость) выше — таблица
доменных метрик с описанием каждой.

---

## Тесты

**46/46 проходят** (`mvn test` через `_test-runner/pom.xml` — наследует
`spring-boot-starter-parent` из Maven Central, чтобы запускаться без
корп. Nexus).

| Класс | Что покрывает | Тестов |
|---|---|---|
| [Pacs008BuilderTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/Pacs008BuilderTest.java) | сборка XML по эталону (TaxRmt только КПП, без BrnchId/SplmtryData/«0»-defaults) | 15 |
| [CcStatusMapperTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/CcStatusMapperTest.java) | маппинг PGW status + code → ccStatus | 7 |
| [CreatePaymentLibraryTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/CreatePaymentLibraryTest.java) | PPRB_GET/STARTED в синке, PPRB_FAILED fallback, batch | 6 |
| [ExecuteResponseHandlerTest](createpayment-modulex-service/src/test/java/ru/sbrf/pprb/stmnt/modulex/lib/ExecuteResponseHandlerTest.java) | callback flow + idempotency dedup + missing PPRB_STARTED → ERROR | 6 |
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
3. Проверено: turn_docdata в синке **НЕ записан** (это асинхронно), `status_WalletTurn` имеет PPRB_GET и PPRB_STARTED.
4. Симулируем квитанцию PGW с кодом 300 (финальный) + operationDto JSON.
5. Проверено: `turn_docdata` создан из callback payload.
6. Проверено: `status_WalletTurn` в `PPRB_EXECUTED`.
7. Проверено: `ResultCallbackClient.send(...)` дёрнут с правильным `ExecutionResult`.

---

## Подключение реального DataSpace

Реальные имплементации в [`lib/dataspace/`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/) —
все `@Primary @Component`, инжектят `DataSpaceApi` из corp `simpleservicemodulex`:

| Класс | Что делает |
|---|---|
| [`DataSpaceWalletTurnRepository`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/DataSpaceWalletTurnRepository.java) | `findByBchOperationId` → `searchWalletTurn` с проекцией |
| [`DataSpaceTurnDocdataRepository`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/DataSpaceTurnDocdataRepository.java) | `save` (`Packet.turnDocdata.create`), `findByOperationId` (`searchTurnDocdata`). `ccBchOperationId` пишется через deprecated `ccWalletTurnId` для совместимости со старой схемой |
| [`DataSpaceStatusWalletTurnRepository`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/DataSpaceStatusWalletTurnRepository.java) | **Idempotent create** по натуральным ключам — без objectId. На уник-violation `(ccWalletTurnId, ccStatus)` молча skip-аем. `findFirstByOperationId` для async-flow |
| [`DataSpaceIdempotencyStore`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/DataSpaceIdempotencyStore.java) | L2-кэш приёма ResponseTicket. `find` + `save` через сущность `responseTicket_idempotency` |
| [`DataSpaceUpdOutboxRepository`](createpayment-modulex-service/src/main/java/ru/sbrf/pprb/stmnt/modulex/lib/dataspace/DataSpaceUpdOutboxRepository.java) | Очередь outbox: `save`, `findByRequestId`, `findPending`, `countByStatus` |

### Принципы работы с DataSpace SDK

1. **Только натуральные ключи** — `ccBchOperationId`, `ccOperationId`,
   `ccTransactionId`, `ccStatus`, `ccIdempotencyKey`, `ccRequestId`.
   Никакого синтетического `objectId` — он не имеет бизнес-смысла и
   в коде не используется.

2. **Идемпотентная вставка вместо upsert** — для тех таблиц, где есть
   уник-индекс по натуральным полям, делаем чистый `packet.entity.create(...)`.
   На повторе ловим `SdkJsonRpcClientException` с message-маркером
   (`duplicate` / `unique` / `уник` / `дубл`) и тихо skip-аем. Это даёт
   идемпотентность без extra round-trip search.

3. **Поиск по натуральным ключам** — `dsApi.searchEntity(g -> g.setWhere(w -> w.ccXxxEq(...)).withCcYyy()...)`.

4. **Совместимость со старой схемой** — некоторые `isDeprecated`-поля в
   нашей XML всё ещё mandatory в корп-рантайме (например, `ccWalletTurnId`
   в `StatusWalletTurn`). Дублируем значения новых полей в старые в одном
   `create`-вызове.

### Зависимость DataSpaceApi

Корп. `simpleservicemodulex` уже создаёт `DataSpaceApi`-бин из конфига:

```yaml
dataspace:
  url: http://dataspace-...
  connection-timeout: 5000
  read-timeout: 30000
```

Просто заинжекти `DataSpaceApi` в свои `@Component`-имплементации.

### Локальная сборка (без corp SDK)

В `_test-runner` и `_local-run` (Maven Central) пакет `lib/dataspace/`
**исключён из компиляции** через `maven-compiler-plugin <excludes>` —
эти классы требуют corp `stmnt-model-sdk` и `DataSpaceApi`, которых
в Maven Central нет. Работают только in-memory `@Bean`-ы из `AppConfig`.

### Устаревший раздел — старые примеры

Ниже сохранён архивный шаблон ранних имплементаций. Не использовать — реальные
файлы в `lib/dataspace/` уже работают по натуральным ключам + idempotent
create. Оставлено только для исторической справки.

<details>
<summary>Старый пример (не использовать)</summary>

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

</details>

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

### Что готово ✅

**Базовый pipeline**
- JSON-RPC `/api/createPayment` с упрощённым входом (`ccBchOperationId`, `ccContractId`).
- Sber-обогащение: 4 вызова (registerId, ucpId, divisionId, bicDirectory).
- Сборка pacs.008.001.08 по минимальному эталону (без BrnchId/SplmtryData/«0»-заглушек).
- Отправка в PGW через `transferUpd`.
- Приём квитанции `/upd/response/execute` с маппингом `statusInfo.code` → ccStatus.
- Outbound REST callback с финальным `ExecutionResult` инициатору.

**5-фазный жизненный цикл status_WalletTurn**
- `PPRB_GET` → `PPRB_STARTED` → `PPRB_PROCESSING` → `PPRB_EXECUTED`/`PPRB_FAILED`.
- `turn_docdata` сохраняется только async после квитанции PGW.
- `PgwOperationDtoParser` парсит `operationDto.documentReason` → `TurnDocdataDraft`.

**Контракт PGW**
- Идемпотентность приёма ResponseTicket по `idempotencyKey`: L1 in-memory + L2 DataSpace.
- Гарант-доставка УРД через `UpdOutbox` + `PgwOutboxWorker` (`@Scheduled`).
  Tomcat-тред не блокируется ретраями: один sync-attempt, дальше фон.
- Раннее `ERROR` ApiResult на ResponseTicket до записи PPRB_STARTED →
  PGW повторит через гарант-доставку.

**DataSpace**
- Все 5 реальных репо в `lib/dataspace/` активны с `@Primary @Component`.
- Работа **только по натуральным ключам** — `objectId` не используется.
- `status_WalletTurn`: idempotent create — на duplicate-key (`(ccWalletTurnId, ccStatus)`) skip.
- `turn_docdata`, `responseTicket_idempotency`, `upd_outbox` — то же.
- Дублирование deprecated полей (`ccWalletTurnId`) для совместимости со старой схемой.

**Наблюдаемость**
- 13 доменных метрик через Micrometer (Prometheus + JMX).
- HTTP-логирование всех outbound/inbound с body (interceptor + filter), маскирование заголовков.
- Конфиг через env-var pattern, prod-профиль с fail-fast, `.env.prod.example`.

**Конкретные мелочи**
- `ccDivisionId` резолвится из `FSKK_DT.divisionId`.
- `ccReceiptDate = now()` на приёме `execute`.
- Индексы `WalletTurn` синхронизированы с DDL: `(ccBchOperationId, ccContractId)` unique + `ccTxId` regular.
- `ResultCallbackClientImpl` собирает свой `RestTemplate` через `RestTemplateBuilder`.
- In-memory репо зарегистрированы явными `@Bean` в `AppConfig`, plain POJO.

### Что осталось 🔜

**Бизнес-функционал**
- **Конвертация валют**: сейчас `ccValuta*` всегда `810`. Подключить к `FSKK.accCurrency`.
- **Курсы ЦБ**: `ccRateDT/KT` сейчас `1`. Нужен сервис курсов.
- **`ccStartSum / ccStartSumNAT`**: считать для операций типа `ccTypeOper=50`.
- **OCC/Sanctions/Compliance**: согласовать в `msgAttributes` к PGW.
- **Подписи pacs.008**: с 1Q2026 для документов в ЦБ обязательны (`SplmtryData/Signature`).
- **Реальный outbound URL** в `result-callback`: пока требует ручной конфигурации,
  можно расширить до per-payment URL в `msgAttributes`.

**Технические улучшения**
- **Возврат метаданных в turn_docdata** (после callback): `ccContractId`, `ccKTRegisterId`,
  `ccRqUID`, `ccRqTm` теряются — стащить в `ccStatusDesc` PPRB_STARTED как JSON.
- **Healthcheck для outbox**: алерт если `outbox_size{status=PENDING}` > порога долго.
- **Background-cleanup для responseTicket_idempotency**: записи старше N дней удалять
  по `ccProcessedAt` (сейчас растёт без ограничений).
