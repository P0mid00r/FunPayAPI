package ru.pomidorka.funpay

import kotlinx.coroutines.runBlocking

class CompatibilityApiTest {
    @Suppress("unused")
    fun documentedPythonStyleApiCompiles() = runBlocking {
        val token = "YOUR_GOLDEN_KEY"
        val account = Account(token).get()
        println("Logged in as ${account.username} (ID: ${account.id})")

        val runner = Runner(account)
        val channel = runner.listen(requestsDelay = 3)

        // The following is intentionally not consumed: it verifies the exact public API shape.
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
                else -> Unit
            }
            channel.cancel()
        }
        account.close()
    }
}
