package ru.pomidorka.funpay.api

import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.FieldMap
import de.jensklingenberg.ktorfit.http.FormUrlEncoded
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Header
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.Query
import de.jensklingenberg.ktorfit.http.Url
import io.ktor.client.statement.HttpResponse

/** Low-level FunPay transport. Prefer [ru.pomidorka.funpay.FunPayAccount] in application code. */
interface FunPayService {
    @GET
    suspend fun get(
        @Url url: String,
        @Header("Cookie") cookie: String,
        @Header("User-Agent") userAgent: String? = null,
        @Header("X-Requested-With") requestedWith: String? = null,
        @Query("node") node: String? = null,
        @Query("last_message") lastMessage: Long? = null,
    ): HttpResponse

    @FormUrlEncoded
    @POST
    suspend fun postForm(
        @Url url: String,
        @Header("Cookie") cookie: String,
        @Header("User-Agent") userAgent: String? = null,
        @Header("X-Requested-With") requestedWith: String? = null,
        @FieldMap fields: Map<String, String>,
    ): HttpResponse
}
