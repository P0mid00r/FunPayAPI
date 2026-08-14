package ru.pomidorka.funpay

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountParsingTest {
    // --- refresh / get ---

    @Test
    fun refreshParsesAccountMetadata() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/") {
                respond(MAIN_PAGE, HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "PHPSESSID=abc123; path=/"))
            } else respond("", HttpStatusCode.NotFound)
        })
        val account = FunPayAccount("golden_key", client = client)

        account.get()

        assertEquals("testuser", account.username)
        assertEquals(42, account.id)
        assertEquals("csrf123", account.csrfToken)
        assertEquals("abc123", account.phpsessid)
        assertEquals(3, account.activeSales)
        assertEquals(5, account.activePurchases)
        assertTrue(account.isInitiated)
        assertTrue(account.categories.isNotEmpty())
        assertEquals(2, account.subcategories.size)
        assertTrue(account.html!!.contains("testuser"))
        account.close()
    }

    @Test
    fun refreshThrowsWhenNotLoggedIn() = runBlocking {
        val client = HttpClient(MockEngine { respond("<html><body>no user</body></html>", HttpStatusCode.OK) })
        val account = FunPayAccount("bad_key", client = client)

        assertFailsWith<UnauthorizedException> { account.get() }
        account.close()
    }

    @Test
    fun refreshThrowsOn403() = runBlocking {
        val client = HttpClient(MockEngine { respond("forbidden", HttpStatusCode.Forbidden) })
        val account = FunPayAccount("bad_key", client = client)

        assertFailsWith<UnauthorizedException> { account.get() }
        account.close()
    }

    @Test
    fun operationsRequireInitialization() = runBlocking {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        val account = FunPayAccount("golden_key", client = client)

        assertFailsWith<AccountNotInitiatedException> { account.requestChats() }
        assertFailsWith<AccountNotInitiatedException> { account.getSells() }
        account.close()
    }

    // --- chats ---

    @Test
    fun requestChatsParsesBookmarks() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/runner/" -> json(CHATS_JSON)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val chats = account.requestChats()

        assertEquals(2, chats.size)
        val alice = chats.first { it.id == 5 }
        assertEquals("Alice", alice.name)
        assertEquals("hello", alice.lastMessageText)
        assertFalse(alice.unread)
        val bob = chats.first { it.id == 6 }
        assertEquals("Bob", bob.name)
        assertTrue(bob.unread)
        account.close()
    }

    @Test
    fun getChatByNameFindsChat() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/runner/" -> json(CHATS_JSON)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertEquals("Alice", account.getChatByName("Alice", refresh = true)?.name)
        assertNull(account.getChatByName("Nobody", refresh = true))
        account.close()
    }

    @Test
    fun getChatByIdFindsChat() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/runner/" -> json(CHATS_JSON)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertEquals(6, account.getChatById(6, refresh = true)?.id)
        assertNull(account.getChatById(999, refresh = true))
        account.close()
    }

    // --- messages ---

    @Test
    fun getChatHistoryParsesMessages() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/chat/history" -> json(CHAT_HISTORY_JSON)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val messages = account.getChatHistory("123", interlocutorUsername = "Nickname")

        assertEquals(4, messages.size)

        val bot = messages.first { it.id == 101L }
        assertTrue(bot.byBot)
        assertEquals("Привет, я бот!", bot.text)
        assertEquals("testuser", bot.authorName)

        val normal = messages.first { it.id == 102L }
        assertEquals("Привет!", normal.text)
        assertEquals("Nickname", normal.authorName)
        assertEquals("Nickname", normal.chatName)

        val system = messages.first { it.id == 103L }
        assertEquals(MessageType.ORDER_PURCHASED, system.type)
        assertEquals("FunPay", system.authorName)
        assertEquals(0, system.authorId)

        val image = messages.first { it.id == 104L }
        assertEquals("https://img.example/a.png", image.imageLink)
        assertEquals("", image.text)
        account.close()
    }

    @Test
    fun getChatHistoryFiltersFromId() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/chat/history" -> json(CHAT_HISTORY_JSON)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val messages = account.getChatHistory("123", fromId = 103)
        assertEquals(listOf(103L, 104L), messages.map { it.id })
        account.close()
    }

    @Test
    fun getChatsHistoriesFetchesMultiple() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/chat/history" -> json(CHAT_HISTORY_JSON)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val result = account.getChatsHistories(mapOf("123" to "Nickname"))
        assertEquals(4, result["123"]?.size)
        account.close()
    }

    @Test
    fun sendMessageParsesEcho() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/runner/" -> json(SEND_MESSAGE_OK_JSON)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val message = account.sendMessage("123", "Привет, я бот!")

        assertEquals("123", message.chatId)
        assertEquals("Привет, я бот!", message.text)
        assertTrue(message.byBot)
        assertEquals("testuser", message.authorName)
        account.close()
    }

    @Test
    fun sendMessageThrowsOnErrorResponse() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/runner/" -> json("""{"response":{"error":"chat not found"}}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val e = assertFailsWith<FunPayApiException> { account.sendMessage("123", "hi") }
        assertTrue(e.message!!.contains("chat not found"))
        account.close()
    }

    // --- images ---

    @Test
    fun uploadImageParsesFileId() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/file/addChatImage" -> json("""{"fileId":"7"}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertEquals(7, account.uploadImage(ByteArray(3), "x.png"))
        account.close()
    }

    // --- orders ---

    @Test
    fun getSellsParsesOrdersAndCurrency() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/orders/trade" -> html(SELLS_HTML)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val (next, orders) = account.getSells()

        assertEquals("nextCursor", next)
        assertEquals(3, orders.size)

        val paid = orders.first { it.id == "100" }
        assertEquals(OrderStatus.PAID, paid.status)
        assertEquals(1000.5, paid.price)
        assertEquals(Currency.RUB, paid.currency)
        assertEquals("BuyerOne", paid.buyerUsername)
        assertEquals(77, paid.buyerId)
        assertEquals(5, paid.amount)

        val refunded = orders.first { it.id == "101" }
        assertEquals(OrderStatus.REFUNDED, refunded.status)
        assertEquals(20.5, refunded.price)
        assertEquals(Currency.USD, refunded.currency)
        assertEquals(78, refunded.buyerId)

        val closed = orders.first { it.id == "102" }
        assertEquals(OrderStatus.CLOSED, closed.status)
        assertEquals(15.0, closed.price)
        assertEquals(Currency.EUR, closed.currency)
        assertEquals(0, closed.buyerId)
        account.close()
    }

    @Test
    fun getSellsAppliesStatusFilters() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/orders/trade" -> html(SELLS_HTML)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val (_, orders) = account.getSells(includePaid = false)
        assertEquals(listOf("101", "102"), orders.map { it.id })

        val (_, onlyPaid) = account.getSells(includeClosed = false, includeRefunded = false)
        assertEquals(listOf("100"), onlyPaid.map { it.id })
        account.close()
    }

    @Test
    fun getSellsThrowsOnLoginPage() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/orders/trade" -> html("""<html><body><div class="content-account-login">login</div></body></html>""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertFailsWith<UnauthorizedException> { account.getSells() }
        account.close()
    }

    @Test
    fun getOrderParsesDetails() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/orders/100/" -> html(ORDER_HTML)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val order = account.getOrder("100")

        assertEquals(OrderStatus.CLOSED, order.status)
        // Примечание: парсер берёт первый <div> внутри .param-item, включая сам контейнер,
        // поэтому в текст попадает и подпись h5.
        assertTrue(order.shortDescription!!.contains("Gold x5"))
        assertTrue(order.fullDescription!!.contains("Full desc here"))
        assertEquals(500.0, order.sum)
        assertEquals(Currency.RUB, order.currency)
        assertEquals(1001, order.subcategory?.id)
        assertEquals("BuyerOne", order.buyerUsername)
        assertEquals(77, order.buyerId)
        assertEquals("testuser", order.sellerUsername)
        assertEquals(42, order.sellerId)
        account.close()
    }

    @Test
    fun getOrderParsesRefundedStatus() = runBlocking {
        val refundedHtml = ORDER_HTML.replace("<span class=\"text-success\">Закрыт</span>", "<span class=\"text-warning\">Возврат</span>")
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/orders/100/" -> html(refundedHtml)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertEquals(OrderStatus.REFUNDED, account.getOrder("100").status)
        account.close()
    }

    // --- balance ---

    @Test
    fun getBalanceParsesValues() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/lots/offer" -> html(BALANCE_HTML)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val balance = account.getBalance()
        assertEquals(100.5, balance.totalRub)
        assertEquals(50.0, balance.availableRub)
        assertEquals(1.2, balance.totalUsd)
        assertEquals(0.6, balance.availableUsd)
        assertEquals(1.1, balance.totalEur)
        assertEquals(0.5, balance.availableEur)
        account.close()
    }

    // --- users ---

    @Test
    fun getUserParsesProfileAndLots() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/users/77/" -> html(USER_HTML)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val profile = account.getUser(77)

        assertEquals("BuyerOne", profile.username)
        assertTrue(profile.online)
        assertFalse(profile.banned)
        assertEquals("https://funpay.com/img/avatar.png", profile.profilePhoto)

        assertEquals(1, profile.lots.size)
        val lot = profile.lots.single()
        assertEquals("55", lot.id)
        assertEquals(150.0, lot.price)
        assertEquals("Server1", lot.server)
        assertEquals("https://funpay.com/chips/offer?id=55", lot.publicLink)
        account.close()
    }

    // --- public lots ---

    @Test
    fun getSubcategoryPublicLotsParsesOffers() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/lots/1001/" -> html(PUBLIC_LOTS_HTML)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val lots = account.getSubcategoryPublicLots(SubCategoryType.COMMON, 1001)

        assertEquals(2, lots.size)
        val first = lots[0]
        assertEquals("77", first.id)
        assertEquals(200.0, first.price)
        assertEquals("ServerX", first.server)
        assertEquals("https://funpay.com/lots/offer?id=77", first.publicLink)

        val second = lots[1]
        assertEquals("78", second.id)
        assertEquals(100.0, second.price)
        assertNull(second.server)
        account.close()
    }

    // --- lots management ---

    @Test
    fun getLotFieldsParsesForm() = runBlocking {
        val formHtml = """<input name="fields[summary][ru]" value="RuTitle"><input name="price" value="10.5"><textarea name="fields[desc][en]">Desc text</textarea><input type="checkbox" name="active" checked>"""
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/lots/offerEdit" -> json(buildJsonObject { put("html", formHtml) }.toString())
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val fields = account.getLotFields(123)

        assertEquals("RuTitle", fields.titleRu)
        assertEquals(10.5, fields.price)
        assertEquals("Desc text", fields.descriptionEn)
        assertTrue(fields.active)
        account.close()
    }

    @Test
    fun saveLotSucceeds() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/lots/offerSave" -> json("""{}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val fields = LotFields(123, mutableMapOf("price" to "10"))
        fields.active = true
        account.saveLot(fields)
        account.close()
    }

    @Test
    fun saveLotThrowsOnError() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/lots/offerSave" -> json("""{"error":"validation failed"}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val e = assertFailsWith<FunPayApiException> { account.saveLot(LotFields(123, mutableMapOf())) }
        assertEquals("validation failed", e.message)
        account.close()
    }

    @Test
    fun raiseLotsReturnsTrue() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/lots/raise" -> json("""{"error":"false"}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertTrue(account.raiseLots(100))
        account.close()
    }

    @Test
    fun raiseLotsThrowsOnError() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/lots/raise" -> json("""{"error":"true","msg":"Rate limited"}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val e = assertFailsWith<FunPayApiException> { account.raiseLots(100) }
        assertEquals("Rate limited", e.message)
        account.close()
    }

    @Test
    fun raiseLotsThrowsForUnknownCategory() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/lots/raise" -> json("""{"error":"false"}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertFailsWith<FunPayApiException> { account.raiseLots(999) }
        account.close()
    }

    @Test
    fun getRaiseModalParsesJson() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/lots/raise" -> json("""{"modal":"html"}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val modal = account.getRaiseModal(100)
        assertEquals("html", modal["modal"]?.jsonPrimitive?.content)
        account.close()
    }

    // --- reviews / refund / withdraw ---

    @Test
    fun sendReviewReturnsContent() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/orders/review" -> json("""{"content":"Отзыв опубликован"}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertEquals("Отзыв опубликован", account.sendReview("100", "Спасибо!", 5))
        account.close()
    }

    @Test
    fun sendReviewRejectsInvalidRating() = runBlocking {
        val account = accountWith { respond("", HttpStatusCode.NotFound) }

        assertFailsWith<IllegalArgumentException> { account.sendReview("100", "text", 6) }
        assertFailsWith<IllegalArgumentException> { account.sendReview("100", "text", 0) }
        account.close()
    }

    @Test
    fun deleteReviewReturnsContent() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/orders/reviewDelete" -> json("""{"content":"deleted"}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertEquals("deleted", account.deleteReview("100"))
        account.close()
    }

    @Test
    fun refundThrowsOnError() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/orders/refund" -> json("""{"error":"true","msg":"Can't refund"}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val e = assertFailsWith<FunPayApiException> { account.refund("100") }
        assertEquals("Can't refund", e.message)
        account.close()
    }

    @Test
    fun withdrawThrowsOnError() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/withdraw/withdraw" -> json("""{"error":"true","msg":"Insufficient funds"}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val e = assertFailsWith<FunPayApiException> { account.withdraw(Currency.RUB, Wallet.QIWI, 100.0, "address") }
        assertEquals("Insufficient funds", e.message)
        account.close()
    }

    @Test
    fun withdrawParsesAmount() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/withdraw/withdraw" -> json("""{"error":"false","amount_ext":"97.5"}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertEquals(97.5, account.withdraw(Currency.USD, Wallet.TRC, 100.0, "wallet"))
        account.close()
    }

    // --- transport errors ---

    @Test
    fun requestFailedExceptionOnHttp500() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/chat/history" -> respond("oops", HttpStatusCode.InternalServerError)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val e = assertFailsWith<RequestFailedException> { account.getChatHistory("123") }
        assertEquals(500, e.status)
        account.close()
    }

    @Test
    fun unauthenticatedResponseIsDetected() = runBlocking {
        val account = accountWith { request ->
            when (request.url.encodedPath) {
                "/chat/history" -> respond("forbidden", HttpStatusCode.Forbidden)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertFailsWith<UnauthorizedException> { account.getChatHistory("123") }
        account.close()
    }
}
