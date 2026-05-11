# createpayment

Spring Boot JSON-RPC сервис для создания платежей. Каркас по образцу `stmnt`-сервисов: `AppConfig` + `*Properties` + RPC-имплементация + библиотечный класс с бизнес-логикой и опциональной Ignite-веткой.

## Структура

- `config/AppConfig.java` — Spring-конфигурация, бины, общие константы
- `config/CreatePaymentProperties.java` — настройки домена (лимиты, валюта)
- `config/IgniteThinClientProperties.java` — настройки Ignite Thin Client
- `api/CreatePaymentService.java` — JSON-RPC интерфейс
- `api/dto/CreatePayment.java`, `CreatePaymentResponse.java` — DTO
- `rpc/CreatePaymentServiceImpl.java` — RPC-имплементация с переключением Ignite/default
- `lib/CreatePaymentLibrary.java` — основная бизнес-логика
- `lib/CreatePaymentLibraryIgnite.java` — вариант поверх Ignite
- `validator/SimpleValidator.java` — простой валидатор полей

## Сборка

```
mvn clean package
```

## Запуск

```
mvn spring-boot:run
```
