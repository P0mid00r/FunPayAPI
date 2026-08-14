package ru.pomidorka.funpay

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunnerTest {
    private suspend fun runnerAccount() = accountWith { request ->
        when (request.url.encodedPath) {
            "/runner/" -> json(CHATS_JSON)
            "/chat/history" -> json(CHAT_HISTORY_JSON)
            "/orders/trade" -> html(SELLS_HTML)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private suspend fun collectUntilInitial(channel: ReceiveChannel<BaseEvent>): List<BaseEvent> {
        val collected = mutableListOf<BaseEvent>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            for (event in channel) {
                collected += event
                if (collected.count { it is InitialChatEvent } >= 2 && collected.count { it is InitialOrderEvent } >= 3) {
                    channel.cancel()
                    break
                }
            }
        }
        withTimeout(5_000) { job.join() }
        return collected
    }

    @Test
    fun runnerEmitsInitialChatAndOrderEvents() = runBlocking {
        val runner = Runner(runnerAccount())
        val collected = collectUntilInitial(runner.listen(requestsDelay = 0))
        runner.close()

        assertEquals(2, collected.count { it is InitialChatEvent })
        assertEquals(3, collected.count { it is InitialOrderEvent })

        assertTrue(collected.any { it is InitialChatEvent && it.chat.id == 5 && it.chat.name == "Alice" })
        assertTrue(collected.any { it is InitialChatEvent && it.chat.id == 6 && it.chat.name == "Bob" })
        assertTrue(collected.any { it is InitialOrderEvent && it.order.id == "100" && it.order.currency == Currency.RUB })
        assertTrue(collected.any { it is InitialOrderEvent && it.order.id == "101" && it.order.currency == Currency.USD })
    }

    @Test
    fun runnerCanBeClosed() = runBlocking {
        val runner = Runner(runnerAccount())
        runner.close()
    }

    @Test
    fun listenRejectsNegativeDelay() = runBlocking {
        val runner = Runner(runnerAccount())
        val e = kotlin.test.assertFailsWith<IllegalArgumentException> { runner.listen(requestsDelay = -1) }
        assertTrue(e.message!!.contains("non-negative"))
        runner.close()
    }
}
