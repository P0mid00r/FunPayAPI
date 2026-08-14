package ru.pomidorka.funpay.example

import kotlinx.coroutines.runBlocking
import ru.pomidorka.funpay.Account
import ru.pomidorka.funpay.InitialChatEvent
import ru.pomidorka.funpay.NewMessageEvent
import ru.pomidorka.funpay.NewOrderEvent
import ru.pomidorka.funpay.Runner

private const val TOKEN = "YOUR_GOLDEN_KEY"

fun main() = runBlocking {
    check(TOKEN != "YOUR_GOLDEN_KEY") {
        "Set your FunPay golden_key"
    }

    val account = Account(TOKEN).get()
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
