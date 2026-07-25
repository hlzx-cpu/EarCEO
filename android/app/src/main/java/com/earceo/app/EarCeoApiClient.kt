package com.earceo.app

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

class EarCeoApiClient(
    baseUrl: String,
    private val token: String,
    val projectId: String,
) {
    data class MobileEvent(
        @SerializedName("event_id") val eventId: String,
        @SerializedName("session_id") val sessionId: String,
        @SerializedName("turn_id") val turnId: String?,
        val sequence: Int,
        val type: String,
        val data: JsonObject,
    )

    data class SessionResponse(
        @SerializedName("session_id") val sessionId: String,
        val status: String,
        @SerializedName("event_stream") val eventStream: String,
        @SerializedName("resume_cursor") val resumeCursor: String,
    )

    data class TurnResponse(
        @SerializedName("turn_id") val turnId: String,
        val status: String,
        @SerializedName("submitted_at") val submittedAt: String?,
    )

    data class ApiFailure(
        val code: String,
        val message: String,
        val retryable: Boolean,
    )

    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val eventSourceFactory = EventSources.createFactory(http)

    val configured: Boolean
        get() = normalizedBaseUrl.isNotBlank() && token.isNotBlank() && projectId.isNotBlank()

    fun createOrResumeSession(
        clientSessionId: String,
        callback: (Result<SessionResponse>) -> Unit,
    ) {
        val payload = mapOf(
            "client_session_id" to clientSessionId,
            "client" to mapOf(
                "name" to "earceo-android",
                "version" to BuildConfig.VERSION_NAME,
                "locale" to "zh-CN",
            ),
            "project_id" to projectId,
        )
        executeJson(
            path = "/v1/sessions",
            payload = payload,
            responseType = SessionResponse::class.java,
            callback = callback,
        )
    }

    fun submitTurn(
        sessionId: String,
        turnId: String,
        commandText: String,
        wavCaptured: Boolean,
        callback: (Result<TurnResponse>) -> Unit,
    ) {
        val payload = mapOf(
            "client_turn_id" to turnId,
            "project_id" to projectId,
            "input" to mapOf(
                "type" to "final_transcript",
                "text" to commandText,
                "locale" to "zh-CN",
                "source" to "viaim-text-stream",
            ),
            "client_context" to mapOf(
                "headset" to "iFLYBUDS Pro 3",
                "wav_captured" to wavCaptured,
            ),
        )
        executeJson(
            path = "/v1/sessions/$sessionId/turns",
            payload = payload,
            responseType = TurnResponse::class.java,
            extraHeaders = mapOf("Idempotency-Key" to turnId),
            callback = callback,
        )
    }

    fun streamEvents(
        sessionId: String,
        lastEventId: String?,
        onEvent: (MobileEvent) -> Unit,
        onFailure: (ApiFailure) -> Unit,
    ): EventSource {
        val request = authorizedRequest("$normalizedBaseUrl/v1/sessions/$sessionId/events")
            .apply {
                header("Accept", "text/event-stream")
                if (!lastEventId.isNullOrBlank()) {
                    header("Last-Event-ID", lastEventId)
                }
            }
            .build()
        return eventSourceFactory.newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    runCatching {
                        gson.fromJson(data, MobileEvent::class.java)
                    }.onSuccess(onEvent)
                        .onFailure {
                            onFailure(
                                ApiFailure(
                                    code = "INVALID_EVENT",
                                    message = "Backend event could not be decoded.",
                                    retryable = true,
                                ),
                            )
                        }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    throwable: Throwable?,
                    response: Response?,
                ) {
                    onFailure(parseFailure(response, throwable))
                    response?.close()
                }
            },
        )
    }

    fun cancelTurn(
        sessionId: String,
        turnId: String,
        callback: (Result<TurnResponse>) -> Unit,
    ) {
        executeJson(
            path = "/v1/sessions/$sessionId/turns/$turnId/cancel",
            payload = emptyMap<String, String>(),
            responseType = TurnResponse::class.java,
            callback = callback,
        )
    }

    fun shutdown() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
    }

    private fun <T> executeJson(
        path: String,
        payload: Any,
        responseType: Class<T>,
        extraHeaders: Map<String, String> = emptyMap(),
        callback: (Result<T>) -> Unit,
    ) {
        if (!configured) {
            callback(Result.failure(IllegalStateException("Backend is not configured.")))
            return
        }
        val request = authorizedRequest("$normalizedBaseUrl$path")
            .apply {
                extraHeaders.forEach(::header)
                post(gson.toJson(payload).toRequestBody(jsonMediaType))
            }
            .build()
        http.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    callback(Result.failure(error))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            callback(Result.failure(ApiException(parseFailure(it, null))))
                            return
                        }
                        val body = it.body?.string().orEmpty()
                        runCatching { gson.fromJson(body, responseType) }
                            .onSuccess { parsed -> callback(Result.success(parsed)) }
                            .onFailure { error -> callback(Result.failure(error)) }
                    }
                }
            },
        )
    }

    private fun authorizedRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
    }

    private fun parseFailure(response: Response?, throwable: Throwable?): ApiFailure {
        val fallback = ApiFailure(
            code = if (response == null) "NETWORK_ERROR" else "HTTP_${response.code}",
            message = throwable?.message ?: "Backend request failed.",
            retryable = response == null || response.code >= 500,
        )
        val body = response?.body?.string() ?: return fallback
        return runCatching {
            val error = gson.fromJson(body, JsonObject::class.java)
                .getAsJsonObject("error")
            ApiFailure(
                code = error.get("code")?.asString ?: fallback.code,
                message = error.get("message")?.asString ?: fallback.message,
                retryable = error.get("retryable")?.asBoolean ?: fallback.retryable,
            )
        }.getOrDefault(fallback)
    }

    class ApiException(val failure: ApiFailure) : IOException(failure.message)
}
