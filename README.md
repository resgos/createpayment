# module-x-pprb

Multi-module Maven проект. Содержит модуль `createpayment-modulex-service` —
Spring Boot JSON-RPC over HTTP сервис создания платежей. Каркас по образцу
`stmnt`-сервисов: `AppConfig` + `*Properties` + RPC-имплементация +
библиотечный класс с бизнес-логикой и обогащением через внешний JSON-RPC.

Точка входа — `ru.sbrf.pprb.stmnt.services.simpleservicemodulex.ApplicationLauncher`
из зависимости `simpleservicemodulex`.

## Структура

```
module-x-pprb/                              (parent pom)
└── createpayment-modulex-service/          (Spring Boot модуль)
    └── src/main/java/ru/sbrf/pprb/stmnt/modulex/
        ├── config/       — AppConfig, SberIntegrationProperties
        ├── api/          — JSON-RPC интерфейс и DTO
        ├── rpc/          — @AutoJsonRpcServiceImpl
        ├── lib/          — CreatePaymentLibrary, генератор id, дефолты
        ├── integration/  — HTTP-клиент SberIntegration (RestTemplate)
        └── validator/    — SimpleValidator
```

## Сборка

```
mvn clean install
```

## Запуск

```
mvn -pl createpayment-modulex-service spring-boot:run
```

## Тесты

Основной pom использует корпоративный parent `dataspace-bom` —
запускается на корп. CI как обычно: `mvn test`.

Для локального запуска вне корп. контура есть `_test-runner/pom.xml`,
наследующий `spring-boot-starter-parent` из Maven Central:

```
mvn -f _test-runner/pom.xml test
```
