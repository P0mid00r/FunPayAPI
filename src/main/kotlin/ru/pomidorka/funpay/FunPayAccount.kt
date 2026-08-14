package ru.pomidorka.funpay

import com.fleeksoft.ksoup.Ksoup
import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ru.pomidorka.funpay.api.FunPayService
import ru.pomidorka.funpay.api.createFunPayService

/**
 * Coroutine-first FunPay client. A golden_key is kept only in this instance and is never logged.
 * Call [refresh] once before operations that need account metadata or CSRF protection.
 */
class FunPayAccount(
    private val goldenKey: String,
    private val userAgent: String? = null,
    timeoutMillis: Long = 10_000,
    private val client: HttpClient = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = timeoutMillis } },
) : AutoCloseable {
    companion object { const val BASE_URL = "https://funpay.com/"; const val BOT_MARKER = "⁤" }
    private val service: FunPayService = Ktorfit.Builder().baseUrl(BASE_URL).httpClient(client).build().createFunPayService()
    private val json = Json { ignoreUnknownKeys = true }

    var html: String? = null; private set
    var appData: JsonObject? = null; private set
    var id: Int? = null; private set
    var username: String? = null; private set
    var activeSales: Int = 0; private set
    var activePurchases: Int = 0; private set
    var csrfToken: String? = null; private set
    var phpsessid: String? = null; private set
    var isInitiated: Boolean = false; private set
    val categories = mutableListOf<Category>()
    val subcategories = mutableListOf<SubCategory>()
    private val categoriesById = mutableMapOf<Int, Category>()
    private val subcategoriesByType: MutableMap<SubCategoryType, MutableMap<Int, SubCategory>> = mutableMapOf(
        SubCategoryType.COMMON to mutableMapOf(), SubCategoryType.CURRENCY to mutableMapOf()
    )
    private val savedChats = mutableMapOf<Int, ChatShortcut>()

    private fun cookie(includeSession: Boolean = true) = buildString {
        append("golden_key=").append(goldenKey)
        if (includeSession && phpsessid != null) append("; PHPSESSID=").append(phpsessid)
    }
    private fun url(path: String) = if (path.startsWith("http")) path else BASE_URL + path.removePrefix("/")
    private fun requireInit() { if (!isInitiated) throw AccountNotInitiatedException() }
    private suspend fun checked(response: io.ktor.client.statement.HttpResponse, requestUrl: String): String {
        val text = response.bodyAsText()
        if (response.status.value == 403) throw UnauthorizedException()
        if (response.status.value !in 200..299) throw RequestFailedException(response.status.value, requestUrl, text)
        return text
    }
    private suspend fun get(path: String, ajax: Boolean = false, node: String? = null, lastMessage: Long? = null): String {
        val requestUrl = url(path)
        return checked(service.get(requestUrl, cookie(), userAgent, if (ajax) "XMLHttpRequest" else null, node, lastMessage), requestUrl)
    }
    private suspend fun post(path: String, fields: Map<String, String>, ajax: Boolean = true): String {
        val requestUrl = url(path)
        return checked(service.postForm(requestUrl, cookie(), userAgent, if (ajax) "XMLHttpRequest" else null, fields), requestUrl)
    }

    suspend fun refresh(updateSession: Boolean = false): FunPayAccount {
        val requestUrl = BASE_URL
        val response = service.get(requestUrl, cookie(!updateSession), userAgent)
        val page = checked(response, requestUrl)
        val document = Ksoup.parse(page)
        val name = document.selectFirst("div.user-link-name")?.text()?.trim() ?: throw UnauthorizedException()
        val body = document.selectFirst("body") ?: throw FunPayApiException("Main page has no body")
        val data = body.attr("data-app-data").takeIf { it.isNotBlank() } ?: throw FunPayApiException("Main page has no app data")
        val parsed = json.parseToJsonElement(data).jsonObject
        username = name; appData = parsed; id = parsed["userId"]?.jsonPrimitive?.content?.toIntOrNull()
        csrfToken = parsed["csrf-token"]?.jsonPrimitive?.content
        activeSales = document.selectFirst("span.badge-trade")?.text()?.trim()?.toIntOrNull() ?: 0
        activePurchases = document.selectFirst("span.badge-orders")?.text()?.trim()?.toIntOrNull() ?: 0
        response.headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("PHPSESSID=") }?.substringAfter("PHPSESSID=")?.substringBefore(';')?.let { phpsessid = it }
        html = page; if (!isInitiated) setupCategories(document); isInitiated = true
        return this
    }

    /** Python-compatible name for [refresh]. */
    suspend fun get(updatePhpSession: Boolean = false): FunPayAccount = refresh(updatePhpSession)

    suspend fun getBalance(lotId: Long = 18_853_876): Balance {
        requireInit(); val doc = Ksoup.parse(get("lots/offer?id=$lotId")); checkAuthorized(doc)
        val select = doc.selectFirst("select[name=method]") ?: throw FunPayApiException("Balance selector not found")
        fun value(name: String) = select.attr(name).toDoubleOrNull() ?: 0.0
        return Balance(value("data-balance-total-rub"), value("data-balance-rub"), value("data-balance-total-usd"), value("data-balance-usd"), value("data-balance-total-eur"), value("data-balance-eur"))
    }

    suspend fun getSubcategoryPublicLots(type: SubCategoryType, subcategoryId: Int): List<LotShortcut> {
        requireInit(); val doc = Ksoup.parse(get("${if (type == SubCategoryType.COMMON) "lots" else "chips"}/$subcategoryId/")); checkAuthorized(doc)
        val category = getSubcategory(type, subcategoryId) ?: throw FunPayApiException("Unknown subcategory $subcategoryId")
        return doc.select("a.tc-item").mapNotNull { offer ->
            val id = offer.attr("href").substringAfter("id=", "").substringBefore('&').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val priceElement = offer.selectFirst("div.tc-price") ?: return@mapNotNull null
            val price = priceElement.attr("data-s").toDoubleOrNull() ?: priceElement.text().trim().substringBefore(' ').toDoubleOrNull() ?: return@mapNotNull null
            LotShortcut(id, offer.selectFirst("div.tc-server.hidden-xxs, div.tc-server.hidden-xs")?.text()?.trim(), offer.selectFirst("div.tc-desc-text")?.text()?.trim(), price, category, offer.outerHtml())
        }
    }

    suspend fun getChatHistory(chatId: String, lastMessageId: Long = Long.MAX_VALUE, interlocutorUsername: String? = null, fromId: Long = 0): List<Message> {
        requireInit(); val root = json.parseToJsonElement(get("chat/history", true, chatId, lastMessageId)).jsonObject
        val chat = root["chat"]?.jsonObject ?: return emptyList(); val messages = chat["messages"]?.jsonArray ?: return emptyList()
        val interlocutorId = chat["node"]?.jsonObject?.get("name")?.jsonPrimitive?.content?.split('-')?.getOrNull(2)?.toIntOrNull()
        return parseMessages(messages, chatId, interlocutorId, interlocutorUsername, fromId)
    }
    suspend fun getChatsHistories(chats: Map<String, String?>): Map<String, List<Message>> = chats.mapValues { (chatId, name) -> getChatHistory(chatId, interlocutorUsername = name) }

    suspend fun sendMessage(chatId: String, text: String? = null, chatName: String? = null, imageId: Int? = null): Message {
        requireInit(); val objects = "[{\"type\":\"chat_node\",\"id\":${jsonString(chatId)},\"tag\":\"00000000\",\"data\":{\"node\":${jsonString(chatId)},\"last_message\":-1,\"content\":\"\"}}]"
        val content = if (imageId == null) BOT_MARKER + text.orEmpty() else ""
        val request = "{\"action\":\"chat_message\",\"data\":{\"node\":${jsonString(chatId)},\"last_message\":-1,\"content\":${jsonString(content)}${imageId?.let { ",\"image_id\":$it" }.orEmpty()}}}"
        val root = json.parseToJsonElement(post("runner/", mapOf("objects" to objects, "request" to request, "csrf_token" to csrf()))).jsonObject
        val error = root["response"]?.jsonObject?.get("error")?.jsonPrimitive?.contentOrNull
        if (error != null) throw FunPayApiException("Message was not delivered: $error")
        val raw = root["objects"]?.jsonArray?.firstOrNull()?.jsonObject?.get("data")?.jsonObject?.get("messages")?.jsonArray?.lastOrNull()?.jsonObject ?: throw FunPayApiException("Message response is malformed")
        return parseMessages(listOf(raw), chatId, null, chatName).single()
    }

    suspend fun uploadImage(bytes: ByteArray, filename: String = "funpay_image.png"): Int {
        requireInit(); val requestUrl = url("file/addChatImage")
        val response = client.post(requestUrl) { headers.append(HttpHeaders.Cookie, cookie()); userAgent?.let { headers.append(HttpHeaders.UserAgent, it) }; headers.append("X-Requested-With", "XMLHttpRequest"); setBody(MultiPartFormDataContent(formData { append("file_id", "0"); append("file", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"$filename\""); append(HttpHeaders.ContentType, "image/png") }) })) }
        val root = json.parseToJsonElement(checked(response, requestUrl)).jsonObject
        return root["fileId"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw FunPayApiException(root["msg"]?.jsonPrimitive?.content ?: "Image upload failed")
    }
    suspend fun sendImage(chatId: String, bytes: ByteArray, filename: String = "funpay_image.png", chatName: String? = null) = sendMessage(chatId, chatName = chatName, imageId = uploadImage(bytes, filename))

    suspend fun refund(orderId: String) { requireInit(); val root = json.parseToJsonElement(post("orders/refund", mapOf("id" to orderId, "csrf_token" to csrf()))).jsonObject; if (root["error"]?.jsonPrimitive?.contentOrNull == "true") throw FunPayApiException(root["msg"]?.jsonPrimitive?.content ?: "Refund failed") }
    suspend fun sendReview(orderId: String, text: String, rating: Int = 5): String { require(rating in 1..5); requireInit(); val root = json.parseToJsonElement(post("orders/review", mapOf("authorId" to id.toString(), "text" to text, "rating" to rating.toString(), "csrf_token" to csrf(), "orderId" to orderId))).jsonObject; return root["content"]?.jsonPrimitive?.content ?: throw FunPayApiException(root["msg"]?.jsonPrimitive?.content ?: "Review failed") }
    suspend fun deleteReview(orderId: String): String { requireInit(); val root = json.parseToJsonElement(post("orders/reviewDelete", mapOf("authorId" to id.toString(), "csrf_token" to csrf(), "orderId" to orderId))).jsonObject; return root["content"]?.jsonPrimitive?.content ?: throw FunPayApiException(root["msg"]?.jsonPrimitive?.content ?: "Review deletion failed") }
    suspend fun withdraw(currency: Currency, wallet: Wallet, amount: Double, address: String): Double { requireInit(); val cur = mapOf(Currency.RUB to "rub", Currency.USD to "usd", Currency.EUR to "eur").getValue(currency); val wall = mapOf(Wallet.QIWI to "qiwi", Wallet.YOUMONEY to "fps", Wallet.BINANCE to "binance", Wallet.TRC to "usdt_trc", Wallet.CARD_RUB to "card_rub", Wallet.CARD_USD to "card_usd", Wallet.CARD_EUR to "card_eur", Wallet.WEBMONEY to "wmz").getValue(wallet); val root = json.parseToJsonElement(post("withdraw/withdraw", mapOf("csrf_token" to csrf(), "currency_id" to cur, "ext_currency_id" to wall, "wallet" to address, "amount_int" to amount.toString()))).jsonObject; if (root["error"]?.jsonPrimitive?.contentOrNull == "true") throw FunPayApiException(root["msg"]?.jsonPrimitive?.content ?: "Withdrawal failed"); return root["amount_ext"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0 }

    suspend fun getRaiseModal(categoryId: Int): JsonObject {
        requireInit(); val category = getCategory(categoryId) ?: throw FunPayApiException("Category $categoryId not found")
        val nodeId = category.subcategories.firstOrNull()?.id ?: throw FunPayApiException("Category has no subcategories")
        return json.parseToJsonElement(post("lots/raise", mapOf("game_id" to categoryId.toString(), "node_id" to nodeId.toString()))).jsonObject
    }
    suspend fun raiseLots(categoryId: Int, subcategoryIds: Collection<Int>? = null, exclude: Set<Int> = emptySet()): Boolean {
        requireInit(); val category = getCategory(categoryId) ?: throw FunPayApiException("Category $categoryId not found")
        val nodes = (subcategoryIds?.mapNotNull { category.getSubcategory(SubCategoryType.COMMON, it) } ?: category.subcategories.filter { it.type == SubCategoryType.COMMON }).filterNot { it.id in exclude }
        if (nodes.isEmpty()) throw FunPayApiException("No common subcategories to raise")
        val fields = buildMap { put("game_id", categoryId.toString()); put("node_id", nodes.first().id.toString()); nodes.forEachIndexed { index, sub -> put("node_ids[$index]", sub.id.toString()) } }
        val root = json.parseToJsonElement(post("lots/raise", fields)).jsonObject
        if (root["error"]?.jsonPrimitive?.contentOrNull == "true") throw FunPayApiException(root["msg"]?.jsonPrimitive?.content ?: "Lot raise failed")
        return true
    }
    suspend fun getUser(userId: Int): UserProfile {
        requireInit(); val page = get("users/$userId/"); val doc = Ksoup.parse(page); checkAuthorized(doc)
        val name = doc.selectFirst("span.mr4")?.text()?.trim() ?: throw FunPayApiException("User not found")
        val style = doc.selectFirst("div.avatar-photo")?.attr("style").orEmpty(); val photo = Regex("\\(([^)]+)\\)").find(style)?.groupValues?.get(1)?.let { if (it.startsWith("http")) it else BASE_URL.trimEnd('/') + it }
        val profile = UserProfile(userId, name, photo, "Онлайн" in doc.selectFirst("span.media-user-status")?.text().orEmpty(), doc.selectFirst("span.label-danger") != null, page)
        doc.select("div.offer-list-title-container").forEach { container ->
            val link = container.selectFirst("h3 a")?.attr("href").orEmpty(); val type = if ("chips" in link) SubCategoryType.CURRENCY else SubCategoryType.COMMON
            val sub = getSubcategory(type, link.trimEnd('/').substringAfterLast('/').toIntOrNull() ?: return@forEach) ?: return@forEach
            container.parent()?.select("a.tc-item")?.forEach { parseLot(it, sub)?.let(profile.lots::add) }
        }; return profile
    }
    suspend fun getChat(chatId: Int): Chat {
        requireInit(); val page = get("chat/?node=$chatId"); val doc = Ksoup.parse(page); checkAuthorized(doc)
        val name = doc.selectFirst("div.chat-header div.media-user-name a")?.text()?.trim() ?: throw FunPayApiException("Chat $chatId not found")
        val looking = doc.selectFirst("div.param-item.chat-panel a")
        return Chat(chatId, name, looking?.attr("href")?.takeIf(String::isNotBlank), looking?.text()?.trim(), page, getChatHistory(chatId.toString(), interlocutorUsername = name))
    }
    suspend fun getOrder(orderId: String): Order {
        requireInit(); val page = get("orders/$orderId/"); val doc = Ksoup.parse(page); checkAuthorized(doc)
        val status = when (doc.selectFirst("span.text-warning, span.text-success")?.text()?.trim()) { "Возврат" -> OrderStatus.REFUNDED; "Закрыт" -> OrderStatus.CLOSED; else -> OrderStatus.PAID }
        var title: String? = null; var description: String? = null; var sum: Double? = null; var subcategory: SubCategory? = null; var currency: Currency = Currency.RUB
        doc.select("div.param-item").forEach { item -> when (item.selectFirst("h5")?.text()?.trim()) { "Краткое описание" -> title = item.selectFirst("div")?.text()?.trim(); "Подробное описание" -> description = item.selectFirst("div")?.text()?.trim(); "Сумма" -> { val sumText = item.selectFirst("span")?.text()?.trim().orEmpty(); sum = parsePrice(sumText); currency = detectCurrency(sumText) }; "Категория" -> { val link = item.selectFirst("a")?.attr("href").orEmpty(); subcategory = getSubcategory(if ("chips" in link) SubCategoryType.CURRENCY else SubCategoryType.COMMON, link.trimEnd('/').substringAfterLast('/').toIntOrNull() ?: -1) } } }
        val user = doc.selectFirst("div.chat-header div.media-user-name a"); val counterpartName = user?.text()?.trim(); val counterpartId = user?.attr("href")?.trimEnd('/')?.substringAfterLast('/')?.toIntOrNull(); val sales = "Продажи" in doc.selectFirst("ul.navbar-right li.active a")?.text().orEmpty()
        return Order(orderId.removePrefix("#"), status, subcategory, title, description, sum, if (sales) counterpartId else id, if (sales) counterpartName else username, if (sales) id else counterpartId, if (sales) username else counterpartName, page, null, currency)
    }
    suspend fun getSells(startFrom: String? = null, includePaid: Boolean = true, includeClosed: Boolean = true, includeRefunded: Boolean = true, filters: Map<String, String> = emptyMap()): Pair<String?, List<OrderShortcut>> {
        requireInit(); val query = filters.entries.joinToString("&") { "${it.key}=${it.value}" }; val page = if (startFrom == null) get("orders/trade" + if (query.isBlank()) "" else "?$query") else post("orders/trade", filters + ("continue" to startFrom), false)
        val doc = Ksoup.parse(page); if (doc.selectFirst("div.content-account-login") != null) throw UnauthorizedException(); val next = doc.selectFirst("input[name=continue]")?.attr("value")?.takeIf(String::isNotBlank)
        val orders = doc.select("a.tc-item").mapNotNull { item -> val status = when { "warning" in item.classNames() -> OrderStatus.REFUNDED; "info" in item.classNames() -> OrderStatus.PAID; else -> OrderStatus.CLOSED }; if ((status == OrderStatus.PAID && !includePaid) || (status == OrderStatus.CLOSED && !includeClosed) || (status == OrderStatus.REFUNDED && !includeRefunded)) return@mapNotNull null; val orderId = item.selectFirst("div.tc-order")?.text()?.trim()?.removePrefix("#") ?: return@mapNotNull null; val buyer = item.selectFirst("div.media-user-name span"); val buyerId = buyer?.attr("data-href")?.trimEnd('/')?.substringAfterLast('/')?.toIntOrNull() ?: 0; val priceText = item.selectFirst("div.tc-price")?.text()?.trim().orEmpty(); val price = parsePrice(priceText); OrderShortcut(orderId, item.selectFirst("div.order-desc div")?.text()?.trim().orEmpty(), price, buyer?.text()?.trim().orEmpty(), buyerId, status, item.selectFirst("div.text-muted")?.text()?.trim().orEmpty(), item.outerHtml(), detectCurrency(priceText)) }; return next to orders
    }
    suspend fun getLotFields(lotId: Int): LotFields {
        requireInit(); val root = json.parseToJsonElement(get("lots/offerEdit?offer=$lotId", true)).jsonObject; val doc = Ksoup.parse(root["html"]?.jsonPrimitive?.content ?: throw FunPayApiException("Lot edit form missing")); val fields = mutableMapOf<String, String>(); doc.select("input[name], textarea[name], select[name]").forEach { input -> val name = input.attr("name"); if (name.isNotBlank()) fields[name] = if (input.tagName() == "textarea") input.text() else input.attr("value") }; doc.select("input[type=checkbox][checked]").forEach { fields[it.attr("name")] = "on" }; return LotFields(lotId, fields)
    }
    suspend fun saveLot(lotFields: LotFields) { requireInit(); val root = json.parseToJsonElement(post("lots/offerSave", lotFields.renew().fields() + ("location" to "trade"))).jsonObject; if (root["error"] != null) throw FunPayApiException(root["error"]?.jsonPrimitive?.content ?: "Lot saving failed") }

    suspend fun requestChats(): List<ChatShortcut> {
        requireInit()
        val objectJson = "[{\"type\":\"chat_bookmarks\",\"id\":$id,\"tag\":\"00000000\",\"data\":false}]"
        val root = json.parseToJsonElement(post("runner/", mapOf("objects" to objectJson, "request" to "false", "csrf_token" to csrf()))).jsonObject
        val markup = root["objects"]?.jsonArray?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "chat_bookmarks" }?.jsonObject?.get("data")?.jsonObject?.get("html")?.jsonPrimitive?.content ?: return emptyList()
        return Ksoup.parse(markup).select("a.contact-item").mapNotNull { element ->
            element.attr("data-id").toIntOrNull()?.let { chatId ->
                ChatShortcut(chatId, element.selectFirst("div.media-user-name")?.text()?.trim(), element.selectFirst("div.contact-item-message")?.text()?.trim().orEmpty(), "unread" in element.classNames(), element.outerHtml())
            }
        }
    }
    fun addChats(chats: Iterable<ChatShortcut>) { chats.forEach { savedChats[it.id] = it } }
    suspend fun getChats(update: Boolean = false): Map<Int, ChatShortcut> { requireInit(); if (update) requestChats().forEach { savedChats[it.id] = it }; return savedChats.toMap() }
    suspend fun getChatByName(name: String, refresh: Boolean = false): ChatShortcut? { getChats(refresh); return savedChats.values.firstOrNull { it.name == name } }
    suspend fun getChatById(chatId: Int, refresh: Boolean = false): ChatShortcut? { getChats(refresh); return savedChats[chatId] }

    fun getCategory(categoryId: Int) = categoriesById[categoryId]
    fun getSubcategory(type: SubCategoryType, subcategoryId: Int) = subcategoriesByType.getValue(type)[subcategoryId]
    override fun close() = client.close()

    private fun csrf() = csrfToken ?: throw AccountNotInitiatedException()
    private fun parsePrice(text: String): Double = text.replace(Regex("[₽$€\\s]"), "").replace("USD", "").replace("EUR", "").replace("RUB", "").replace(',', '.').toDoubleOrNull() ?: 0.0
    private fun detectCurrency(text: String): Currency { val upper = text.uppercase(); return when { '$' in text || "USD" in upper -> Currency.USD; '€' in text || "EUR" in upper -> Currency.EUR; else -> Currency.RUB } }
    private fun parseLot(element: com.fleeksoft.ksoup.nodes.Element, subcategory: SubCategory): LotShortcut? {
        val id = element.attr("href").substringAfter("id=", "").substringBefore('&').takeIf(String::isNotBlank) ?: return null
        val priceElement = element.selectFirst("div.tc-price") ?: return null
        val price = priceElement.attr("data-s").toDoubleOrNull() ?: priceElement.text().trim().substringBefore(' ').replace(',', '.').toDoubleOrNull() ?: return null
        return LotShortcut(id, element.selectFirst("div.tc-server.hidden-xxs, div.tc-server.hidden-xs")?.text()?.trim(), element.selectFirst("div.tc-desc-text")?.text()?.trim(), price, subcategory, element.outerHtml())
    }
    private fun checkAuthorized(document: com.fleeksoft.ksoup.nodes.Document) { if (document.selectFirst("div.user-link-name") == null) throw UnauthorizedException() }
    private fun setupCategories(document: com.fleeksoft.ksoup.nodes.Document) { val table = document.select("div.promo-game-list").getOrNull(1) ?: document.selectFirst("div.promo-game-list") ?: return; table.select("div.promo-game-item").forEach { game -> val id = game.selectFirst("div.game-title")?.attr("data-id")?.toIntOrNull() ?: return@forEach; val category = Category(id, game.selectFirst("a")?.text()?.trim().orEmpty()); game.select("li a").forEach { link -> val href = link.attr("href"); val subId = href.trimEnd('/').substringAfterLast('/').toIntOrNull() ?: return@forEach; val type = if ("chips" in href) SubCategoryType.CURRENCY else SubCategoryType.COMMON; val sub = SubCategory(subId, link.text().trim(), type, category); category.addSubcategory(sub); subcategories += sub; subcategoriesByType.getValue(type)[subId] = sub }; categories += category; categoriesById[id] = category } }
    private fun parseMessages(raw: List<JsonElement>, chatId: String, interlocutorId: Int?, interlocutorName: String?, fromId: Long = 0): List<Message> = raw.mapNotNull { item -> val obj = item.jsonObject; val messageId = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null; if (messageId < fromId) return@mapNotNull null; val authorId = obj["author"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0; val markup = obj["html"]?.jsonPrimitive?.content.orEmpty(); val doc = Ksoup.parse(markup); val image = doc.selectFirst("a.chat-img-link")?.attr("href")?.takeIf { chatId.all(Char::isDigit) }; var text = if (image != null) "" else doc.selectFirst(if (authorId == 0) "div.alert.alert-with-icon.alert-info" else "div.message-text")?.text()?.trim().orEmpty(); val byBot = text.startsWith(BOT_MARKER); if (byBot) text = text.removePrefix(BOT_MARKER); val author = when (authorId) { id -> username; 0 -> "FunPay"; interlocutorId -> interlocutorName; else -> doc.selectFirst("div.media-user-name a")?.text()?.trim() }; Message(messageId, text, chatId, interlocutorName, author, authorId, markup, image, byBot, doc.selectFirst("div.media-user-name span")?.text()?.trim()) }
    private fun jsonString(value: String) = JsonPrimitive(value).toString()
}
