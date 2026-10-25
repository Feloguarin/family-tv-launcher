package familytv.launcher.recs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal object RecsHttp {
    /**
     * Shared client for TMDB and Claude. Redirects are off: neither API needs them, and following one
     * could send the key (a header or query parameter) to another host. No logging interceptor, ever.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // Claude thinks before it answers
            .callTimeout(180, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** A finished HTTP exchange, already read into memory. */
internal class HttpResult(val code: Int, val body: String, val retryAfterSeconds: Long?)

/** Runs [request] without blocking the caller; cancelling the coroutine cancels the call. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun OkHttpClient.fetch(request: Request): HttpResult {
    val call = newCall(request)
    val response = suspendCancellableCoroutine<Response> { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { response.close() }
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        })
    }
    return withContext(Dispatchers.IO) {
        response.use {
            HttpResult(it.code, it.body?.string().orEmpty(), it.header("retry-after")?.trim()?.toLongOrNull())
        }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Reads a string that may be absent or JSON null (optString would return "null"). */
internal fun JSONObject.str(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
