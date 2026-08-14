package ru.pomidorka.funpay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventsTest {
    private val chat = ChatShortcut(5, "Alice", "hello", unread = false, "")
    private val message = Message(1, "hi", "5", "Alice", "Alice", 42, "")
    private val order = OrderShortcut("100", "desc", 10.0, "Buyer", 1, OrderStatus.PAID, "Cat", "")

    @Test
    fun initialChatEventCarriesChat() {
        val event: BaseEvent = InitialChatEvent("tag1", chat)
        assertEquals("tag1", event.runnerTag)
        assertEquals(chat, (event as InitialChatEvent).chat)
        assertTrue(event.timeMillis > 0)
    }

    @Test
    fun chatsListChangedEventHasNoPayload() {
        val event: BaseEvent = ChatsListChangedEvent("tag")
        assertEquals("tag", event.runnerTag)
    }

    @Test
    fun lastChatMessageChangedEventCarriesChat() {
        val event = LastChatMessageChangedEvent("tag", chat)
        assertEquals(chat, event.chat)
    }

    @Test
    fun newMessageEventCarriesMessageAndStackId() {
        val event = NewMessageEvent("tag", message, "stack-1")
        assertEquals(message, event.message)
        assertEquals("stack-1", event.stackId)
    }

    @Test
    fun initialOrderEventCarriesOrder() {
        val event = InitialOrderEvent("tag", order)
        assertEquals(order, event.order)
    }

    @Test
    fun ordersListChangedEventCarriesCounters() {
        val event = OrdersListChangedEvent("tag", purchases = 3, sales = 5)
        assertEquals(3, event.purchases)
        assertEquals(5, event.sales)
    }

    @Test
    fun newOrderEventCarriesOrder() {
        val event = NewOrderEvent("tag", order)
        assertEquals(order, event.order)
    }

    @Test
    fun orderStatusChangedEventCarriesOrder() {
        val event = OrderStatusChangedEvent("tag", order)
        assertEquals(order, event.order)
    }

    @Test
    fun allEventsShareBaseEventContract() {
        val events: List<BaseEvent> = listOf(
            InitialChatEvent("tag", chat),
            ChatsListChangedEvent("tag"),
            LastChatMessageChangedEvent("tag", chat),
            NewMessageEvent("tag", message, "s"),
            InitialOrderEvent("tag", order),
            OrdersListChangedEvent("tag", 1, 2),
            NewOrderEvent("tag", order),
            OrderStatusChangedEvent("tag", order),
        )
        assertEquals(8, events.size)
        events.forEach { assertTrue(it.runnerTag.isNotBlank()) }
    }
}
