package ru.pomidorka.funpay

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** Главная страница FunPay, которую отдаёт mock для успешного `get()`/`refresh()`. */
val MAIN_PAGE: String = """
<html><body data-app-data='{"userId":"42","csrf-token":"csrf123"}'>
  <div class="user-link-name">testuser</div>
  <span class="badge-trade">3</span>
  <span class="badge-orders">5</span>
  <div class="promo-game-list">
    <div class="promo-game-item">
      <div class="game-title" data-id="100">Game One</div>
      <a href="/lots/1/">Game One</a>
      <ul>
        <li><a href="/lots/1001/">Common Sub</a></li>
        <li><a href="/chips/2001/">Chip Sub</a></li>
      </ul>
    </div>
  </div>
</body></html>
""".trimIndent()

/** Ответ runner/ с закладками чатов. */
val CHATS_JSON: String =
    """{"objects":[{"type":"chat_bookmarks","data":{"html":"<a class=\"contact-item\" data-id=\"5\"><div class=\"media-user-name\">Alice</div><div class=\"contact-item-message\">hello</div></a><a class=\"contact-item unread\" data-id=\"6\"><div class=\"media-user-name\">Bob</div><div class=\"contact-item-message\">yo</div></a>"}}]}"""

/** Ответ chat/history с несколькими типами сообщений. */
val CHAT_HISTORY_JSON: String = """{"chat":{"node":{"name":"node-0-77"},"messages":[
  {"id":"101","author":"42","html":"<div class=\"message-text\">${FunPayAccount.BOT_MARKER}Привет, я бот!</div>"},
  {"id":"102","author":"77","html":"<div class=\"message-text\">Привет!</div>"},
  {"id":"103","author":"0","html":"<div class=\"alert alert-with-icon alert-info\">Покупатель оплатил заказ #1</div>"},
  {"id":"104","author":"99","html":"<div class=\"message-text\">Имя <a class=\"chat-img-link\" href=\"https://img.example/a.png\"></a></div>"}
]}}"""

/** Ответ runner/ на отправку сообщения (echo от FunPay). */
val SEND_MESSAGE_OK_JSON: String =
    """{"objects":[{"type":"chat_node","data":{"messages":[{"id":"999","author":"42","html":"<div class=\"message-text\">${FunPayAccount.BOT_MARKER}Привет, я бот!</div>"}]}}]}"""

/** Страница orders/trade со списком продаж разных статусов и валют. */
val SELLS_HTML: String = """
<html><body>
<div class="user-link-name">testuser</div>
<a class="tc-item info" href="/orders/100/">
  <div class="tc-order">#100</div>
  <div class="tc-price">1 000,50 ₽</div>
  <div class="order-desc"><div>5 шт золота</div></div>
  <div class="media-user-name"><span data-href="/users/77/">BuyerOne</span></div>
  <div class="text-muted">Game One / Common Sub</div>
</a>
<a class="tc-item warning" href="/orders/101/">
  <div class="tc-order">#101</div>
  <div class="tc-price">${'$'}20.5</div>
  <div class="order-desc"><div>USD item</div></div>
  <div class="media-user-name"><span data-href="/users/78/">BuyerTwo</span></div>
  <div class="text-muted">Cat</div>
</a>
<a class="tc-item" href="/orders/102/">
  <div class="tc-order">#102</div>
  <div class="tc-price">€15</div>
  <div class="order-desc"><div>EUR item</div></div>
  <div class="media-user-name"><span>BuyerThree</span></div>
  <div class="text-muted">Cat</div>
</a>
<input name="continue" value="nextCursor">
</body></html>
""".trimIndent()

/** Страница деталей заказа. */
val ORDER_HTML: String = """
<html><body>
<div class="user-link-name">testuser</div>
<div class="chat-header"><div class="media-user-name"><a href="/users/77/">BuyerOne</a></div></div>
<span class="text-success">Закрыт</span>
<ul class="navbar-right"><li class="active"><a>Продажи</a></li></ul>
<div class="param-item"><h5>Краткое описание</h5><div>Gold x5</div></div>
<div class="param-item"><h5>Подробное описание</h5><div>Full desc here</div></div>
<div class="param-item"><h5>Сумма</h5><span>500 ₽</span></div>
<div class="param-item"><h5>Категория</h5><a href="/lots/1001/">Common Sub</a></div>
</body></html>
""".trimIndent()

/** Страница с формой выбора способа вывода (баланс). */
val BALANCE_HTML: String = """
<html><body>
<div class="user-link-name">testuser</div>
<select name="method" data-balance-total-rub="100.5" data-balance-rub="50" data-balance-total-usd="1.2" data-balance-usd="0.6" data-balance-total-eur="1.1" data-balance-eur="0.5"></select>
</body></html>
""".trimIndent()

/** Страница профиля пользователя с одним лотом. */
val USER_HTML: String = """
<html><body>
<div class="user-link-name">testuser</div>
<span class="mr4">BuyerOne</span>
<div class="avatar-photo" style="background-image:url(/img/avatar.png)"></div>
<span class="media-user-status">Онлайн</span>
<div class="offer-list-title-container">
  <h3><a href="/chips/2001/">Chip Sub</a></h3>
  <a class="tc-item" href="/chips/2001/offers?id=55">
    <div class="tc-price" data-s="150">150 ₽</div>
    <div class="tc-desc-text">desc text</div>
    <div class="tc-server hidden-xs">Server1</div>
  </a>
</div>
</body></html>
""".trimIndent()

/** Страница подкатегории с публичными лотами. */
val PUBLIC_LOTS_HTML: String = """
<html><body>
<div class="user-link-name">testuser</div>
<a class="tc-item" href="/lots/1001/offers?id=77">
  <div class="tc-price" data-s="200">200 ₽</div>
  <div class="tc-desc-text">Nice lot</div>
  <div class="tc-server hidden-xxs">ServerX</div>
</a>
<a class="tc-item" href="/lots/1001/offers?id=78">
  <div class="tc-price">100 ₽</div>
  <div class="tc-desc-text">Another</div>
</a>
</body></html>
""".trimIndent()

/**
 * Создаёт аккаунт, чей HTTP-клиент отвечает на главную страницу успешным `refresh()`,
 * а остальные запросы обслуживает переданный обработчик.
 */
suspend fun accountWith(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): FunPayAccount {
    val client = HttpClient(MockEngine { request ->
        if (request.url.encodedPath == "/") {
            respond(MAIN_PAGE, HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "PHPSESSID=abc123; path=/"))
        } else {
            handler(request)
        }
    })
    return FunPayAccount("golden_key", client = client).also { it.get() }
}

fun MockRequestHandleScope.json(body: String): HttpResponseData =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

fun MockRequestHandleScope.html(body: String): HttpResponseData =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))
