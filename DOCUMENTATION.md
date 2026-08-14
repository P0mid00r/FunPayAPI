# FunPayAPI — документация (рус.)

Coroutine-first JVM-клиент для неофициального API FunPay. Порт [Python-библиотеки](https://github.com/LIMBODS/FunPayAPI/).

Библиотека использует **Ktorfit** для типизированных HTTP-запросов, **Ktor** как HTTP-клиент (включая multipart-загрузку изображений) и **Ksoup** для разбора HTML FunPay.

---

## Содержание

- [Установка](#установка)
- [Быстрый старт](#быстрый-старт)
- [Авторизация](#авторизация)
- [Работа с чатами и сообщениями](#работа-с-чатами-и-сообщениями)
- [Работа с заказами](#работа-с-заказами)
- [Лоты и категории](#лоты-и-категории)
- [Отзывы, возвраты и вывод средств](#отзывы-возвраты-и-вывод-средств)
- [Пользователи и баланс](#пользователи-и-баланс)
- [События Runner](#события-runner)
- [Модели данных](#модели-данных)
- [Обработка ошибок](#обработка-ошибок)
- [Замечания](#замечания)

---

## Установка

Добавьте зависимость в `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.p0mid00r:funpayapi:1.0.0")
}
```

Требуется JVM 21+ и `kotlinx.coroutines`.

---

## Быстрый старт

```kotlin
import kotlinx.coroutines.runBlocking
import ru.pomidorka.funpay.Account
import ru.pomidorka.funpay.InitialChatEvent
import ru.pomidorka.funpay.NewMessageEvent
import ru.pomidorka.funpay.NewOrderEvent
import ru.pomidorka.funpay.Runner

fun main() = runBlocking {
    val token = "YOUR_GOLDEN_KEY"
    val account = Account(token).get()
    println("Logged in as ${account.username} (ID: ${account.id})")

    val runner = Runner(account)
    val channel = runner.listen(requestsDelay = 3)

    for (event in channel) {
        when (event) {
            is NewMessageEvent -> {
                val msg = event.message
                println("New message from ${msg.authorName}: ${msg.text}")
                if (msg.text.lowercase() == "привет" && msg.authorId != account.id) {
                    account.sendMessage(msg.chatId, "Привет, я бот!")
                }
            }
            is InitialChatEvent -> {
                println("Initial chat: ${event.chat.name} (${event.chat.id})")
            }
            is NewOrderEvent -> {
                println("New order #${event.order.id} from ${event.order.buyerUsername} for ${event.order.price} ${event.order.currency}")
            }
            else -> { /* другие события */ }
        }
    }
}
```

Все сетевые методы — `suspend` и должны вызываться из корутины (например, `runBlocking`).

---

## Авторизация

Клиент создаётся с `golden_key`:

```kotlin
val account = Account("YOUR_GOLDEN_KEY")
```

`Account` — это `typealias` для `FunPayAccount`. Конструктор принимает:

| Параметр | Тип | По умолчанию | Описание |
| --- | --- | --- | --- |
| `goldenKey` | `String` | — | Золотой ключ (токен) FunPay |
| `userAgent` | `String?` | `null` | Пользовательский User-Agent |
| `timeoutMillis` | `Long` | `10_000` | Таймаут HTTP-запросов |
| `client` | `HttpClient` | Ktor CIO | Собственный HTTP-клиент |

### Инициализация

Перед операциями, которым нужны метаданные аккаунта или CSRF-защита, обязательно вызовите `get()` (или `refresh()`):

```kotlin
val account = Account(token).get()          // то же самое, что refresh()
val account = Account(token).refresh()      // полный вариант
```

`get()` выполняет запрос главной страницы, сохраняет `PHPSESSID` из ответа и заполняет метаданные аккаунта.

### Свойства аккаунта

После `get()` доступны (доступны только для чтения, `private set`):

| Свойство | Тип | Описание |
| --- | --- | --- |
| `username` | `String?` | Имя пользователя |
| `id` | `Int?` | ID пользователя |
| `html` | `String?` | HTML главной страницы |
| `appData` | `JsonObject?` | Данные `data-app-data` главной страницы |
| `activeSales` | `Int` | Кол-во активных продаж (бейдж) |
| `activePurchases` | `Int` | Кол-во активных покупок (бейдж) |
| `csrfToken` | `String?` | CSRF-токен |
| `phpsessid` | `String?` | Сессионная кука |
| `isInitiated` | `Boolean` | Была ли выполнена инициализация |
| `categories` | `MutableList<Category>` | Список категорий |
| `subcategories` | `MutableList<SubCategory>` | Список подкатегорий |

Golden key хранится только в экземпляре и никогда не логируется.

---

## Работа с чатами и сообщениями

### Список чатов

```kotlin
val chats = account.requestChats()                  // свежий список (закладки чатов)
val chatsMap = account.getChats(update = false)     // кэшированные чаты, Map<Int, ChatShortcut>
val chat = account.getChatByName("Имя")             // поиск по имени
val chat = account.getChatById(123)                 // поиск по ID
```

`ChatShortcut` содержит `id`, `name`, текст последнего сообщения, флаг `unread` и HTML.

### История сообщений

```kotlin
val messages = account.getChatHistory(
    chatId = "123",
    lastMessageId = Long.MAX_VALUE,
    interlocutorUsername = "никнейм",
    fromId = 0,
)
```

Для нескольких чатов сразу:

```kotlin
val histories = account.getChatsHistories(mapOf("123" to "ник1", "456" to "ник2"))
// Map<String, List<Message>>
```

### Отправка сообщений

```kotlin
val msg = account.sendMessage(chatId = "123", text = "Привет!")
```

Свои сообщения библиотека помечает специальным невидимым маркером (`BOT_MARKER`), поэтому при парсинге они распознаются: у `Message` выставляется флаг `byBot = true`, а маркер из текста убирается. Это позволяет отличать свои сообщения от чужих.

### Отправка изображений

```kotlin
val bytes = java.io.File("photo.png").readBytes()
val message = account.sendImage(chatId = "123", bytes = bytes)
// или с именем файла:
account.sendImage(chatId = "123", bytes = bytes, filename = "photo.png")
```

### Полный чат

```kotlin
val chat = account.getChat(chatId = 123)   // Chat с историей сообщений внутри
```

---

## Работа с заказами

### Продажи (OrderShortcut)

```kotlin
val (nextCursor, orders) = account.getSells(
    includePaid = true,
    includeClosed = true,
    includeRefunded = true,
    filters = mapOf(),
)

// Пагинация: если nextCursor != null, можно получить следующую страницу
val (next, moreOrders) = account.getSells(startFrom = nextCursor)
```

`OrderShortcut` содержит `id`, `description`, `price`, `currency` (`USD`/`RUB`/`EUR`), `buyerUsername`, `buyerId`, `status`, `subcategoryName`, а также вычисляемое `amount`.

### Детали заказа

```kotlin
val order = account.getOrder(orderId = "123")   // Order с полным описанием
```

`Order` содержит `status`, `subcategory`, `shortDescription`, `fullDescription`, `sum`, `currency`, покупателя и продавца, HTML страницы.

---

## Лоты и категории

### Категории и подкатегории

Категории заполняются автоматически при `get()`. Доступ:

```kotlin
val category = account.getCategory(categoryId = 123)
val sub = account.getSubcategory(SubCategoryType.COMMON, subcategoryId = 456)
```

`SubCategoryType` — sealed-класс с вариантами `COMMON` (обычные лоты) и `CURRENCY` (чипсы/валюта).

### Публичные лоты подкатегории

```kotlin
val lots = account.getSubcategoryPublicLots(SubCategoryType.COMMON, subcategoryId = 456)
```

### Редактирование и сохранение лота

```kotlin
val fields = account.getLotFields(lotId = 123)
fields.titleRu = "Новый заголовок"
fields.price = 99.5
fields.active = true
account.saveLot(fields)
```

`LotFields` предоставляет свойства: `titleRu`, `titleEn`, `descriptionRu`, `descriptionEn`, `price`, `amount`, `active`, `deactivateAfterSale`, а также методы `fields()` и `edit(map)`.

### Поднятие лотов

```kotlin
val modal = account.getRaiseModal(categoryId = 123)   // данные модального окна
val ok = account.raiseLots(
    categoryId = 123,
    subcategoryIds = listOf(456, 789),   // если null — все COMMON-подкатегории
    exclude = setOf(1000),               // исключить отдельные
)
```

---

## Отзывы, возвраты и вывод средств

### Отзывы

```kotlin
val content = account.sendReview(orderId = "123", text = "Спасибо!", rating = 5)
account.deleteReview(orderId = "123")
```

`rating` должен быть в диапазоне 1..5.

### Возврат средств

```kotlin
account.refund(orderId = "123")
```

### Вывод средств

```kotlin
val amount = account.withdraw(
    currency = Currency.RUB,
    wallet = Wallet.QIWI,
    amount = 100.0,
    address = "+7 900 000 00 00",
)
```

Доступные валюты: `Currency.USD`, `Currency.RUB`, `Currency.EUR`.

Доступные кошельки (`Wallet`): `QIWI`, `YOUMONEY`, `BINANCE`, `TRC`, `CARD_RUB`, `CARD_USD`, `CARD_EUR`, `WEBMONEY`.

Метод возвращает сумму к зачислению (`amount_ext`).

---

## Пользователи и баланс

### Профиль пользователя

```kotlin
val profile = account.getUser(userId = 123)
// id, username, profilePhoto, online, banned, lots
```

### Баланс

```kotlin
val balance = account.getBalance()
// totalRub, availableRub, totalUsd, availableUsd, totalEur, availableEur
```

---

## События Runner

`Runner` опрашивает FunPay и публикует события в `ReceiveChannel<BaseEvent>`. Это основной способ реагировать на новые сообщения и заказы.

```kotlin
val runner = Runner(account, loadMessages = true, loadOrders = true)
val channel: ReceiveChannel<BaseEvent> = runner.listen(requestsDelay = 3)
```

Параметры:

| Параметр | Тип | По умолчанию | Описание |
| --- | --- | --- | --- |
| `account` | `FunPayAccount` | — | Инициализированный аккаунт |
| `loadMessages` | `Boolean` | `true` | Загружать ли новые сообщения |
| `loadOrders` | `Boolean` | `true` | Следить ли за заказами |

`listen(requestsDelay = 6, ignoreExceptions = true)` — `requestsDelay` в **секундах** между опросами; при `ignoreExceptions = true` ошибки опроса пропускаются, а не роняют канал.

Остановить прослушивание: `channel.cancel()` или `runner.close()` (Runner реализует `AutoCloseable`).

### Типы событий

Все события наследуются от sealed-класса `BaseEvent` (свойства `runnerTag`, `timeMillis`). Диспетчеризация — через `when (event) { is ... }`.

| Событие | Поля | Когда происходит |
| --- | --- | --- |
| `InitialChatEvent` | `chat: ChatShortcut` | Первый опрос: все текущие чаты |
| `ChatsListChangedEvent` | — | Изменился список/состояние чатов |
| `LastChatMessageChangedEvent` | `chat: ChatShortcut` | Изменилось последнее сообщение или флаг `unread` |
| `NewMessageEvent` | `message: Message`, `stackId: String` | Появилось новое сообщение |
| `InitialOrderEvent` | `order: OrderShortcut` | Первый опрос: все текущие заказы |
| `OrdersListChangedEvent` | `purchases: Int`, `sales: Int` | Изменился список заказов |
| `NewOrderEvent` | `order: OrderShortcut` | Появился новый заказ |
| `OrderStatusChangedEvent` | `order: OrderShortcut` | Изменился статус заказа |

```kotlin
for (event in channel) {
    when (event) {
        is NewMessageEvent -> println("Сообщение: ${event.message.text}")
        is OrderStatusChangedEvent -> println("Статус заказа ${event.order.id}: ${event.order.status}")
        else -> Unit
    }
}
```

---

## Модели данных

Все модели — `data class` в пакете `ru.pomidorka.funpay`.

### Sealed-классы (типы)

| Тип | Варианты |
| --- | --- |
| `SubCategoryType` | `COMMON`, `CURRENCY` |
| `OrderStatus` | `PAID`, `CLOSED`, `REFUNDED` |
| `Currency` | `USD`, `RUB`, `EUR` |
| `Wallet` | `QIWI`, `YOUMONEY`, `BINANCE`, `TRC`, `CARD_RUB`, `CARD_USD`, `CARD_EUR`, `WEBMONEY` |
| `MessageType` | `NON_SYSTEM`, `ORDER_PURCHASED`, `ORDER_CONFIRMED`, `NEW_FEEDBACK`, `FEEDBACK_CHANGED`, `FEEDBACK_DELETED`, `NEW_FEEDBACK_ANSWER`, `FEEDBACK_ANSWER_CHANGED`, `FEEDBACK_ANSWER_DELETED`, `ORDER_REOPENED`, `REFUND`, `PARTIAL_REFUND`, `ORDER_CONFIRMED_BY_ADMIN`, `DISCORD` |

### Основные классы

| Класс | Ключевые поля |
| --- | --- |
| `Category` | `id`, `name`, `subcategories`, методы `addSubcategory`, `getSubcategory` |
| `SubCategory` | `id`, `name`, `type`, `category`, ссылки `publicLink`/`privateLink`/`fullname` |
| `LotShortcut` | `id`, `server`, `description`, `price`, `subcategory`, `publicLink` |
| `Balance` | `totalRub/availableRub/totalUsd/availableUsd/totalEur/availableEur` |
| `ChatShortcut` | `id`, `name`, `lastMessageText`, `unread`, `lastMessageType` |
| `Chat` | `id`, `name`, `lookingLink`, `lookingText`, `messages` |
| `Message` | `id`, `text`, `chatId`, `chatName`, `author`, `authorName`, `authorId`, `imageLink`, `byBot`, `badge`, `type` |
| `OrderShortcut` | `id`, `description`, `price`, `currency`, `buyerUsername`, `buyerId`, `status`, `subcategoryName`, `amount` |
| `Order` | `id`, `status`, `subcategory`, `shortDescription`, `fullDescription`, `sum`, `currency`, покупатель/продавец, `review` |
| `Review` | `stars`, `text`, `reply`, `anonymous`, `orderId`, `authorUsername`, `authorId` |
| `UserProfile` | `id`, `username`, `profilePhoto`, `online`, `banned`, `lots` |
| `LotFields` | редактирование лота: `titleRu`, `titleEn`, `descriptionRu`, `descriptionEn`, `price`, `amount`, `active`, `deactivateAfterSale` |

### MessageClassifier

Классифицирует системные сообщения FunPay по тексту:

```kotlin
val type = MessageClassifier.classify("Покупатель оплатил заказ #123")
// MessageType.ORDER_PURCHASED
```

Используется в `Message.type` (для сообщений с `authorId == 0`) и в `ChatShortcut.lastMessageType`.

---

## Обработка ошибок

Базовый класс — `FunPayException` (наследник `Exception`). Конкретные исключения:

| Исключение | Когда выбрасывается |
| --- | --- |
| `AccountNotInitiatedException` | Вызвана операция без `get()`/`refresh()` |
| `UnauthorizedException` | HTTP 403 или FunPay не распознал токен (неверный `golden_key`) |
| `RequestFailedException` | HTTP-ответ вне диапазона 2xx (содержит `status`, `url`, тело) |
| `FunPayApiException` | Логическая ошибка FunPay (например, «Message was not delivered») |

Пример:

```kotlin
try {
    account.sendMessage("123", "Привет!")
} catch (e: UnauthorizedException) {
    println("Неверный golden_key")
} catch (e: FunPayException) {
    println("Ошибка FunPay: ${e.message}")
}
```

---

## Замечания

- У FunPay нет официального стабильного публичного API — разметка и внутренние эндпоинты могут меняться. Приложение должно обрабатывать `FunPayApiException`.
- Никогда не логируйте `golden_key`. Он хранится только в экземпляре клиента.
- Все сетевые методы являются `suspend`; библиотека не выполняет блокирующих сетевых вызовов.
- `Runner` работает на `Dispatchers.IO` в собственном `CoroutineScope`; не забудьте закрыть канал или вызвать `runner.close()`.
- Метод `get()` обязателен перед вызовами, требующими CSRF-защиты.
