package ru.pomidorka.funpay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun categoryProvidesLinksAndLookup() {
        val category = Category(1, "Game")
        val subcategory = SubCategory(2, "Gold", SubCategoryType.CURRENCY, category)
        category.addSubcategory(subcategory)

        assertEquals(subcategory, category.getSubcategory(SubCategoryType.CURRENCY, 2))
        assertEquals("https://funpay.com/chips/2/", subcategory.publicLink)
    }

    @Test
    fun classifierRecognizesCommonSystemMessages() {
        assertEquals(MessageType.ORDER_PURCHASED, MessageClassifier.classify("Покупатель оплатил заказ #123"))
        assertEquals(MessageType.NON_SYSTEM, MessageClassifier.classify("hello"))
    }

    @Test
    fun lotFieldsExposeEdits() {
        val fields = LotFields(10, mutableMapOf("price" to "10"))
        fields.active = true
        fields.amount = 2

        assertTrue(fields.active)
        assertEquals("2", fields.fields()["amount"])
    }

    // --- Category ---

    @Test
    fun categoryStartsWithEmptySubcategories() {
        assertTrue(Category(1, "Game").subcategories.isEmpty())
    }

    @Test
    fun categoryIgnoresDuplicateSubcategories() {
        val category = Category(1, "Game")
        val sub = SubCategory(2, "Gold", SubCategoryType.COMMON, category)
        category.addSubcategory(sub)
        category.addSubcategory(sub)
        assertEquals(1, category.subcategories.size)
    }

    @Test
    fun categoryLookupFiltersByTypeAndId() {
        val category = Category(1, "Game")
        val common = SubCategory(2, "Gold", SubCategoryType.COMMON, category)
        val currency = SubCategory(2, "Chips", SubCategoryType.CURRENCY, category)
        category.addSubcategory(common)
        category.addSubcategory(currency)

        assertEquals(common, category.getSubcategory(SubCategoryType.COMMON, 2))
        assertEquals(currency, category.getSubcategory(SubCategoryType.CURRENCY, 2))
        assertNull(category.getSubcategory(SubCategoryType.COMMON, 99))
        assertNull(category.getSubcategory(SubCategoryType.CURRENCY, 99))
    }

    // --- SubCategory ---

    @Test
    fun subCategoryBuildsLinks() {
        val category = Category(1, "Game")
        val common = SubCategory(2, "Gold", SubCategoryType.COMMON, category)
        val currency = SubCategory(3, "Chips", SubCategoryType.CURRENCY, category)

        assertEquals("Gold Game", common.fullname)
        assertEquals("https://funpay.com/lots/2/", common.publicLink)
        assertEquals("https://funpay.com/lots/2/trade", common.privateLink)
        assertEquals("https://funpay.com/chips/3/", currency.publicLink)
        assertEquals("https://funpay.com/chips/3/trade", currency.privateLink)
    }

    // --- LotShortcut ---

    @Test
    fun lotShortcutBuildsLinks() {
        val category = Category(1, "Game")
        val common = SubCategory(2, "Gold", SubCategoryType.COMMON, category)
        val currency = SubCategory(3, "Chips", SubCategoryType.CURRENCY, category)

        val chipsLot = LotShortcut("77", "Server", "Nice lot", 200.0, currency, "")
        assertEquals("Nice lot", chipsLot.title)
        assertEquals("https://funpay.com/chips/offer?id=77", chipsLot.publicLink)

        val commonLot = LotShortcut("78", null, null, 100.0, common, "")
        assertNull(commonLot.server)
        assertNull(commonLot.description)
        assertEquals("https://funpay.com/lots/offer?id=78", commonLot.publicLink)
    }

    // --- Balance ---

    @Test
    fun balanceHoldsAllCurrencies() {
        val balance = Balance(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        assertEquals(1.0, balance.totalRub)
        assertEquals(2.0, balance.availableRub)
        assertEquals(3.0, balance.totalUsd)
        assertEquals(4.0, balance.availableUsd)
        assertEquals(5.0, balance.totalEur)
        assertEquals(6.0, balance.availableEur)
    }

    // --- ChatShortcut ---

    @Test
    fun chatShortcutClassifiesLastMessage() {
        val system = ChatShortcut(5, "Alice", "Покупатель оплатил заказ #1", false, "")
        assertEquals(MessageType.ORDER_PURCHASED, system.lastMessageType)

        val normal = ChatShortcut(6, "Bob", "hello", true, "")
        assertEquals(MessageType.NON_SYSTEM, normal.lastMessageType)
    }

    // --- Chat ---

    @Test
    fun chatDefaultsToEmptyMessages() {
        assertTrue(Chat(1, "Name", null, null, "").messages.isEmpty())
    }

    // --- Message ---

    @Test
    fun messageExposesAuthorNameAlias() {
        val message = Message(1, "text", "123", "Alice", "Alice", 42, "")
        assertEquals("Alice", message.author)
        assertEquals("Alice", message.authorName)
        assertNull(Message(2, "text", "123", "Alice", null, 42, "").authorName)
    }

    @Test
    fun messageTypeIsSystemOnlyForFunPay() {
        val system = Message(1, "Покупатель оплатил заказ #1", "123", "Chat", "FunPay", 0, "")
        assertEquals(MessageType.ORDER_PURCHASED, system.type)

        val normal = Message(2, "Покупатель оплатил заказ #1", "123", "Alice", "Alice", 42, "")
        assertEquals(MessageType.NON_SYSTEM, normal.type)
    }

    @Test
    fun messageBotFlagIsMutable() {
        val message = Message(1, "text", "123", "Alice", "Bot", 42, "")
        assertFalse(message.byBot)
        message.byBot = true
        assertTrue(message.byBot)
    }

    // --- OrderShortcut ---

    @Test
    fun orderShortcutParsesAmount() {
        fun shortcut(description: String) = OrderShortcut("1", description, 1.0, "Buyer", 0, OrderStatus.PAID, "Cat", "")

        assertEquals(5, shortcut("5 шт золота").amount)
        assertEquals(3, shortcut("3 штук").amount)
        assertEquals(2, shortcut("2 ед").amount)
        assertEquals(1, shortcut("Gold x5").amount)
        assertEquals(1, shortcut("").amount)
    }

    @Test
    fun orderShortcutDefaultsCurrencyToRub() {
        val order = OrderShortcut("1", "desc", 10.0, "Buyer", 0, OrderStatus.PAID, "Cat", "")
        assertEquals(Currency.RUB, order.currency)

        val usd = OrderShortcut("1", "desc", 10.0, "Buyer", 0, OrderStatus.PAID, "Cat", "", Currency.USD)
        assertEquals(Currency.USD, usd.currency)
    }

    // --- Order ---

    @Test
    fun orderExposesTitleAlias() {
        val order = Order("100", OrderStatus.PAID, null, "Short", "Full", 500.0, null, null, null, null, "", null)
        assertEquals("Short", order.title)
        assertNull(order.review)
        assertEquals(Currency.RUB, order.currency)
    }

    // --- Review ---

    @Test
    fun reviewHoldsAllFields() {
        val review = Review(5, "text", "reply", true, "", "100", "Buyer", 1)
        assertEquals(5, review.stars)
        assertEquals("text", review.text)
        assertEquals("reply", review.reply)
        assertTrue(review.anonymous)
        assertEquals("100", review.orderId)
        assertEquals("Buyer", review.authorUsername)
        assertEquals(1, review.authorId)
    }

    // --- UserProfile ---

    @Test
    fun userProfileDefaultsToEmptyLots() {
        val profile = UserProfile(1, "user", null, online = true, banned = false, "")
        assertTrue(profile.lots.isEmpty())
        assertTrue(profile.online)
        assertFalse(profile.banned)
    }

    // --- LotFields ---

    @Test
    fun lotFieldsExposeTextFields() {
        val fields = LotFields(10, mutableMapOf("fields[summary][ru]" to "Old"))
        assertEquals("Old", fields.titleRu)
        assertNull(fields.titleEn)

        fields.titleRu = "Новый заголовок"
        fields.titleEn = "New title"
        fields.descriptionRu = "Описание"
        fields.descriptionEn = "Description"

        assertEquals("Новый заголовок", fields.fields()["fields[summary][ru]"])
        assertEquals("New title", fields.fields()["fields[summary][en]"])
        assertEquals("Описание", fields.fields()["fields[desc][ru]"])
        assertEquals("Description", fields.fields()["fields[desc][en]"])
    }

    @Test
    fun lotFieldsExposeNumericValues() {
        val fields = LotFields(10, mutableMapOf())
        assertNull(fields.price)
        assertNull(fields.amount)

        fields.price = 99.5
        fields.amount = 3
        assertEquals("99.5", fields.fields()["price"])
        assertEquals("3", fields.fields()["amount"])
        assertEquals(99.5, fields.price)
        assertEquals(3, fields.amount)

        fields.price = null
        assertEquals("", fields.fields()["price"])
        assertNull(fields.price)
    }

    @Test
    fun lotFieldsExposeFlags() {
        val fields = LotFields(10, mutableMapOf())
        assertFalse(fields.active)
        assertFalse(fields.deactivateAfterSale)

        fields.active = true
        fields.deactivateAfterSale = true
        assertEquals("on", fields.fields()["active"])
        assertEquals("on", fields.fields()["deactivate_after_sale"])

        fields.active = false
        assertFalse("active" in fields.fields())
        assertTrue("deactivate_after_sale" in fields.fields())

        fields.deactivateAfterSale = false
        assertFalse("deactivate_after_sale" in fields.fields())
    }

    @Test
    fun lotFieldsEditMergesValues() {
        val fields = LotFields(10, mutableMapOf("price" to "10"))
        fields.edit(mapOf("price" to "5", "amount" to "2"))
        assertEquals("5", fields.fields()["price"])
        assertEquals("2", fields.fields()["amount"])
    }

    @Test
    fun lotFieldsRenewKeepsFlags() {
        val fields = LotFields(10, mutableMapOf("price" to "10"))
        fields.active = true
        fields.deactivateAfterSale = true
        fields.renew()

        assertEquals("on", fields.fields()["active"])
        assertEquals("on", fields.fields()["deactivate_after_sale"])
    }

    // --- Sealed-классы: синглтоны ---

    @Test
    fun sealedClassInstancesAreSingletons() {
        assertEquals(Currency.RUB, Currency.RUB)
        assertTrue(Currency.RUB != Currency.USD)
        assertTrue(OrderStatus.PAID != OrderStatus.CLOSED)
        assertTrue(SubCategoryType.COMMON != SubCategoryType.CURRENCY)
        assertTrue(MessageType.ORDER_PURCHASED != MessageType.NON_SYSTEM)
    }
}
