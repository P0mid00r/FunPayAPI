package ru.pomidorka.funpay

sealed class SubCategoryType { object COMMON : SubCategoryType(); object CURRENCY : SubCategoryType() }
sealed class OrderStatus { object PAID : OrderStatus(); object CLOSED : OrderStatus(); object REFUNDED : OrderStatus() }
sealed class Currency { object USD : Currency(); object RUB : Currency(); object EUR : Currency() }
sealed class Wallet { object QIWI : Wallet(); object BINANCE : Wallet(); object TRC : Wallet(); object CARD_RUB : Wallet(); object CARD_USD : Wallet(); object CARD_EUR : Wallet(); object WEBMONEY : Wallet(); object YOUMONEY : Wallet() }
sealed class MessageType { object NON_SYSTEM : MessageType(); object ORDER_PURCHASED : MessageType(); object ORDER_CONFIRMED : MessageType(); object NEW_FEEDBACK : MessageType(); object FEEDBACK_CHANGED : MessageType(); object FEEDBACK_DELETED : MessageType(); object NEW_FEEDBACK_ANSWER : MessageType(); object FEEDBACK_ANSWER_CHANGED : MessageType(); object FEEDBACK_ANSWER_DELETED : MessageType(); object ORDER_REOPENED : MessageType(); object REFUND : MessageType(); object PARTIAL_REFUND : MessageType(); object ORDER_CONFIRMED_BY_ADMIN : MessageType(); object DISCORD : MessageType() }

data class Category(val id: Int, val name: String, val subcategories: MutableList<SubCategory> = mutableListOf()) {
    fun addSubcategory(subcategory: SubCategory) { if (subcategory !in subcategories) subcategories += subcategory }
    fun getSubcategory(type: SubCategoryType, id: Int) = subcategories.firstOrNull { it.type == type && it.id == id }
}

data class SubCategory(val id: Int, val name: String, val type: SubCategoryType, val category: Category) {
    val fullname get() = "$name ${category.name}"
    val publicLink get() = "https://funpay.com/${if (type == SubCategoryType.CURRENCY) "chips" else "lots"}/$id/"
    val privateLink get() = "${publicLink}trade"
}

data class LotShortcut(val id: String, val server: String?, val description: String?, val price: Double, val subcategory: SubCategory, val html: String) {
    val title get() = description
    val publicLink get() = "https://funpay.com/${if (subcategory.type == SubCategoryType.CURRENCY) "chips" else "lots"}/offer?id=$id"
}

data class Balance(val totalRub: Double, val availableRub: Double, val totalUsd: Double, val availableUsd: Double, val totalEur: Double, val availableEur: Double)
data class ChatShortcut(val id: Int, val name: String?, val lastMessageText: String, val unread: Boolean, val html: String) {
    val lastMessageType get() = MessageClassifier.classify(lastMessageText)
}
data class Chat(val id: Int, val name: String, val lookingLink: String?, val lookingText: String?, val html: String, val messages: List<Message> = emptyList())
data class Message(val id: Long, val text: String, val chatId: String, var chatName: String?, var author: String?, val authorId: Int, val html: String, val imageLink: String? = null, var byBot: Boolean = false, var badge: String? = null) {
    val type get() = if (authorId == 0) MessageClassifier.classify(text) else MessageType.NON_SYSTEM
    val authorName get() = author
}
data class OrderShortcut(val id: String, val description: String, val price: Double, val buyerUsername: String, val buyerId: Int, val status: OrderStatus, val subcategoryName: String, val html: String, val currency: Currency = Currency.RUB) {
    val amount: Int = Regex("(\\d+)\\s*(?:шт|штук|ед)", RegexOption.IGNORE_CASE).find(description)?.groupValues?.get(1)?.toIntOrNull() ?: 1
}
data class Review(val stars: Int?, val text: String?, val reply: String?, val anonymous: Boolean, val html: String, val orderId: String, val authorUsername: String, val authorId: Int)
data class Order(val id: String, val status: OrderStatus, val subcategory: SubCategory?, val shortDescription: String?, val fullDescription: String?, val sum: Double?, val buyerId: Int?, val buyerUsername: String?, val sellerId: Int?, val sellerUsername: String?, val html: String, val review: Review?, val currency: Currency = Currency.RUB) {
    val title get() = shortDescription
}
data class UserProfile(val id: Int, val username: String, val profilePhoto: String?, val online: Boolean, val banned: Boolean, val html: String, val lots: MutableList<LotShortcut> = mutableListOf())

class LotFields(val lotId: Int, private val values: MutableMap<String, String>) {
    var titleRu: String? by values.delegate("fields[summary][ru]")
    var titleEn: String? by values.delegate("fields[summary][en]")
    var descriptionRu: String? by values.delegate("fields[desc][ru]")
    var descriptionEn: String? by values.delegate("fields[desc][en]")
    var price: Double? get() = values["price"]?.toDoubleOrNull(); set(value) { values["price"] = value?.toString().orEmpty() }
    var amount: Int? get() = values["amount"]?.toIntOrNull(); set(value) { values["amount"] = value?.toString().orEmpty() }
    var active: Boolean get() = "active" in values; set(value) { values.setFlag("active", value) }
    var deactivateAfterSale: Boolean get() = "deactivate_after_sale" in values || "deactivate_after_sale[]" in values; set(value) { values.setFlag("deactivate_after_sale", value) }
    fun fields(): Map<String, String> = values.toMap()
    fun edit(fields: Map<String, String>) = values.putAll(fields)
    fun renew(): LotFields = apply { values.setFlag("active", active); values.setFlag("deactivate_after_sale", deactivateAfterSale) }
}

private fun MutableMap<String, String>.setFlag(name: String, value: Boolean) { if (value) this[name] = "on" else remove(name) }
private fun MutableMap<String, String>.delegate(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, String?> {
    override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = this@delegate[key]
    override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) { this@delegate[key] = value.orEmpty() }
}

object MessageClassifier {
    fun classify(text: String): MessageType = when {
        "Discord" in text -> MessageType.DISCORD
        "оплатил заказ" in text -> MessageType.ORDER_PURCHASED
        "подтвердил успешное выполнение" in text -> MessageType.ORDER_CONFIRMED
        "написал отзыв" in text -> MessageType.NEW_FEEDBACK
        "вернул деньги" in text -> MessageType.REFUND
        "открыт повторно" in text -> MessageType.ORDER_REOPENED
        else -> MessageType.NON_SYSTEM
    }
}
