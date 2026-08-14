package ru.pomidorka.funpay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID

sealed class BaseEvent(open val runnerTag: String, val timeMillis: Long = System.currentTimeMillis())
data class InitialChatEvent(override val runnerTag: String, val chat: ChatShortcut) : BaseEvent(runnerTag)
data class ChatsListChangedEvent(override val runnerTag: String) : BaseEvent(runnerTag)
data class LastChatMessageChangedEvent(override val runnerTag: String, val chat: ChatShortcut) : BaseEvent(runnerTag)
data class NewMessageEvent(override val runnerTag: String, val message: Message, val stackId: String) : BaseEvent(runnerTag)
data class InitialOrderEvent(override val runnerTag: String, val order: OrderShortcut) : BaseEvent(runnerTag)
data class OrdersListChangedEvent(override val runnerTag: String, val purchases: Int, val sales: Int) : BaseEvent(runnerTag)
data class NewOrderEvent(override val runnerTag: String, val order: OrderShortcut) : BaseEvent(runnerTag)
data class OrderStatusChangedEvent(override val runnerTag: String, val order: OrderShortcut) : BaseEvent(runnerTag)

/** Polls FunPay and exposes its events as a Kotlin [ReceiveChannel]. */
class Runner(
    private val account: FunPayAccount,
    private val loadMessages: Boolean = true,
    private val loadOrders: Boolean = true,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val knownChats = mutableMapOf<Int, ChatShortcut>()
    private val knownMessageIds = mutableMapOf<Int, Long>()
    private val knownOrders = mutableMapOf<String, OrderShortcut>()
    private var firstRequest = true

    /**
     * Returns one channel of events. [requestsDelay] is in seconds.
     * Iterate it with `for (event in runner.listen(requestsDelay = 4))`.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun listen(requestsDelay: Long = 6, ignoreExceptions: Boolean = true): ReceiveChannel<BaseEvent> {
        require(requestsDelay >= 0) { "requestsDelay must be non-negative" }
        return scope.produce(capacity = Channel.BUFFERED) {
            while (isActive) {
                try {
                    poll().forEach { send(it) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!ignoreExceptions) throw error
                }
                delay(requestsDelay * 1_000)
            }
        }
    }

    override fun close() = scope.cancel()

    private suspend fun poll(): List<BaseEvent> {
        val events = mutableListOf<BaseEvent>()
        val tag = UUID.randomUUID().toString().replace("-", "").take(8)
        val chats = account.requestChats()
        val changed = chats.filter { knownChats[it.id]?.let { old -> old.lastMessageText != it.lastMessageText || old.unread != it.unread } ?: true }
        if (firstRequest) {
            changed.forEach { events += InitialChatEvent(tag, it) }
            // Establish a message-ID baseline without replaying old messages as new events.
            if (loadMessages) chats.forEach { chat ->
                account.getChatHistory(chat.id.toString(), interlocutorUsername = chat.name)
                    .maxOfOrNull { it.id }
                    ?.let { knownMessageIds[chat.id] = it }
            }
        }
        else if (changed.isNotEmpty()) {
            events += ChatsListChangedEvent(tag)
            for (chat in changed) {
                events += LastChatMessageChangedEvent(tag, chat)
                if (loadMessages) {
                    val messages = account.getChatHistory(chat.id.toString(), interlocutorUsername = chat.name)
                    val newMessages = knownMessageIds[chat.id]?.let { last -> messages.filter { it.id > last } }.orEmpty()
                    val stackId = UUID.randomUUID().toString()
                    newMessages.forEach { events += NewMessageEvent(tag, it, stackId) }
                    messages.maxOfOrNull { it.id }?.let { knownMessageIds[chat.id] = it }
                }
            }
        }
        knownChats.clear(); knownChats.putAll(chats.associateBy { it.id }); account.addChats(chats)

        if (loadOrders) {
            val (_, orders) = account.getSells()
            if (!firstRequest && orders != knownOrders.values.toList()) events += OrdersListChangedEvent(tag, account.activePurchases, account.activeSales)
            for (order in orders) {
                val previous = knownOrders[order.id]
                when {
                    previous == null && firstRequest -> events += InitialOrderEvent(tag, order)
                    previous == null -> events += NewOrderEvent(tag, order)
                    previous.status != order.status -> events += OrderStatusChangedEvent(tag, order)
                }
                knownOrders[order.id] = order
            }
        }
        firstRequest = false
        return events
    }
}
