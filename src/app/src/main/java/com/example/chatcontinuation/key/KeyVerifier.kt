package com.example.chatcontinuation.key

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 按 DeepSeek API 文档用 GET /models 校验 Key，
 * 与上游 chat-demo 的 verifyApiKey（src/services/api/client.ts）行为一致。
 *
 * 401/403 → Key 无效；402 → 余额不足；其他非 2xx → 服务错误；
 * IOException → 网络错误。结果只含状态码，不携带 Key 本体。
 */
object KeyVerifier {

    sealed class Result {
        data object Ok : Result()
        data object InvalidKey : Result()
        data object InsufficientBalance : Result()
        data object NetworkError : Result()
        data class ServerError(val code: Int) : Result()
    }

    fun verify(
        apiKey: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000
    ): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URL(ENDPOINT).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            val code = connection.responseCode
            when {
                code in 200..299 -> Result.Ok
                code == 401 || code == 403 -> Result.InvalidKey
                code == 402 -> Result.InsufficientBalance
                else -> Result.ServerError(code)
            }
        } catch (e: IOException) {
            Log.w(TAG, "key verify network error: ${e.javaClass.simpleName}")
            Result.NetworkError
        } catch (t: Throwable) {
            Log.w(TAG, "key verify failed: ${t.javaClass.simpleName}")
            Result.NetworkError
        } finally {
            connection?.disconnect()
        }
    }

    private const val TAG = "KeyVerifier"
    private const val ENDPOINT = "https://api.deepseek.com/models"
}
