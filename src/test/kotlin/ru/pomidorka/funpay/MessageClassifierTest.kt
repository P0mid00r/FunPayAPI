package ru.pomidorka.funpay

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageClassifierTest {
    @Test
    fun classifiesDiscord() {
        assertEquals(MessageType.DISCORD, MessageClassifier.classify("Подключите Discord"))
    }

    @Test
    fun classifiesOrderPurchased() {
        assertEquals(MessageType.ORDER_PURCHASED, MessageClassifier.classify("Покупатель оплатил заказ #123"))
        assertEquals(MessageType.ORDER_PURCHASED, MessageClassifier.classify("оплатил заказ"))
    }

    @Test
    fun classifiesOrderConfirmed() {
        assertEquals(MessageType.ORDER_CONFIRMED, MessageClassifier.classify("Покупатель подтвердил успешное выполнение заказа"))
    }

    @Test
    fun classifiesNewFeedback() {
        assertEquals(MessageType.NEW_FEEDBACK, MessageClassifier.classify("Покупатель написал отзыв о заказе #1"))
    }

    @Test
    fun classifiesRefund() {
        assertEquals(MessageType.REFUND, MessageClassifier.classify("Продавец вернул деньги за заказ #1"))
    }

    @Test
    fun classifiesOrderReopened() {
        assertEquals(MessageType.ORDER_REOPENED, MessageClassifier.classify("Заказ #1 открыт повторно"))
    }

    @Test
    fun classifiesRegularTextAsNonSystem() {
        assertEquals(MessageType.NON_SYSTEM, MessageClassifier.classify("hello"))
        assertEquals(MessageType.NON_SYSTEM, MessageClassifier.classify(""))
        assertEquals(MessageType.NON_SYSTEM, MessageClassifier.classify("Просто текст без ключевых слов"))
    }

    @Test
    fun classificationIsCaseSensitive() {
        assertEquals(MessageType.NON_SYSTEM, MessageClassifier.classify("Оплатил заказ"))
    }
}
