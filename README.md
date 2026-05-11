# module-x-pprb

Multi-module Maven проект. Содержит модуль `createpayment-modulex-service` —
Spring Boot JSON-RPC сервис создания платежей. Каркас по образцу
`stmnt`-сервисов: `AppConfig` + `*Properties` + RPC-имплементация +
библиотечный класс с бизнес-логикой и опциональной Ignite-веткой.

Точка входа — `ru.sbrf.pprb.stmnt.services.simpleservicemodulex.ApplicationLauncher`
из зависимости `simpleservicemodulex`.

## Структура

```
module-x-pprb/                              (parent pom)
└── createpayment-modulex-service/          (Spring Boot модуль)
    └── src/main/java/ru/sbrf/pprb/stmnt/modulex/
        ├── config/   — AppConfig, *Properties
        ├── api/      — JSON-RPC интерфейс и DTO
        ├── rpc/      — @AutoJsonRpcServiceImpl, выбор Ignite/default
        ├── lib/      — CreatePaymentLibrary(+Ignite)
        └── validator/— SimpleValidator
```

## Сборка

```
mvn clean install
```

## Запуск

```
mvn -pl createpayment-modulex-service spring-boot:run
```
