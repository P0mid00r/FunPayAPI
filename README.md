# FunPayAPI Kotlin

Coroutine-first JVM port of the FunPay API client. It uses **Ktorfit** for typed HTTP declarations, **Ktor** for the client and multipart uploads, and **Ksoup** for FunPay HTML parsing.

## Documentation

Полная документация на русском языке: [**DOCUMENTATION.md**](DOCUMENTATION.md)

## Setup

Опубликованная версия в Maven Central:

```kotlin
dependencies {
    implementation("io.github.p0mid00r:funpayapi:1.0.0")
}
```

## Publishing

Релизы публикуются автоматически через GitHub Actions при пуше тега вида `vX.Y.Z` (версия в Maven Central — `X.Y.Z`):

```shell
git tag v1.0.0
git push origin v1.0.0
```

Рабочий процесс: [`.github/workflows/publish.yml`](.github/workflows/publish.yml). Локальная проверка публикации:

```shell
./gradlew nmcpPublishAggregationToMavenLocal -Pversion=1.0.0
```

## Example

Set `TOKEN` in [example/src/main/kotlin/ru/pomidorka/funpay/example/Main.kt](example/src/main/kotlin/ru/pomidorka/funpay/example/Main.kt), then run:

```shell
./gradlew :example:run
```

```kotlin
import kotlinx.coroutines.runBlocking
import ru.pomidorka.funpay.Account
import ru.pomidorka.funpay.InitialChatEvent
import ru.pomidorka.funpay.NewMessageEvent
import ru.pomidorka.funpay.NewOrderEvent
import ru.pomidorka.funpay.Runner

fun main() = runBlocking {
    val account = Account("YOUR_GOLDEN_KEY").get()
    println("Logged in as ${account.username} (ID: ${account.id})")

    val runner = Runner(account)
    val channel = runner.listen(requestsDelay = 3)

    for (event in channel) {
        when (event) {
            is NewMessageEvent -> {
                val msg = event.message
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

`get()` is mandatory before CSRF-protected calls. The client sends `golden_key`, retains `PHPSESSID` from the main-page response, and throws `UnauthorizedException`, `RequestFailedException`, or `FunPayApiException` for failures.

Implemented account operations include main-page/category parsing, balances, public lots, users, full chats and orders, sales, lot editing/saving/raising, text/image messages, reviews, refunds, withdrawals, and `Runner.listen()` for a polling `ReceiveChannel<BaseEvent>`. All network functions are `suspend`; no blocking network calls are made by the library.

FunPay has no official stable public API. Its markup and internal endpoints may change; application code should handle `FunPayApiException` and avoid logging the golden key.
