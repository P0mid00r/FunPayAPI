package ru.pomidorka.funpay

open class FunPayException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AccountNotInitiatedException : FunPayException("Call refresh() before using this operation")
class UnauthorizedException : FunPayException("FunPay rejected the credentials (golden_key may be invalid)")
class RequestFailedException(val status: Int, val url: String, body: String) : FunPayException("FunPay request failed: HTTP $status for $url\n$body")
class FunPayApiException(message: String) : FunPayException(message)
