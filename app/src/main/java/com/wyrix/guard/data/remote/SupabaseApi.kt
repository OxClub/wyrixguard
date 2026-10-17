package com.wyrix.guard.data.remote

import com.wyrix.guard.BuildConfig
import com.wyrix.guard.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin client for Supabase Auth (GoTrue), PostgREST and Edge Functions. It only ever holds the anon key
 * and the signed-in owner's JWT; row-level security does the rest. No service_role key exists in this app.
 */
@Singleton
class SupabaseApi @Inject constructor(private val store: SecureStore) {
    class ApiException(val code: String, val status: Int) : Exception("$code ($status)")

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).writeTimeout(25, TimeUnit.SECONDS).build()
    private val base get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anon get() = BuildConfig.SUPABASE_ANON_KEY
    private val mt = "application/json".toMediaType()
    private val refreshLock = Mutex()

    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    val signedIn: Boolean get() = store.get(SecureStore.ACCESS) != null

    private suspend fun exec(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
    }

    private fun errorCode(status: Int, body: String): String = try {
        val o = json.parseToJsonElement(body).jsonObject
        (o["error"] ?: o["error_code"] ?: o["msg"] ?: o["message"])?.jsonPrimitive?.contentOrNull ?: "http_$status"
    } catch (_: Exception) { "http_$status" }

    private suspend fun token(): String {
        if (store.get(SecureStore.ACCESS) == null) throw ApiException("unauthenticated", 401)
        if (System.currentTimeMillis() / 1000 > store.getLong(SecureStore.EXPIRES) - 60) refresh()
        return store.get(SecureStore.ACCESS) ?: throw ApiException("unauthenticated", 401)
    }

    private suspend fun refresh() = refreshLock.withLock {
        if (System.currentTimeMillis() / 1000 <= store.getLong(SecureStore.EXPIRES) - 60) return@withLock
        val rt = store.get(SecureStore.REFRESH) ?: throw ApiException("unauthenticated", 401)
        val body = buildJsonObject { put("refresh_token", rt) }.toString().toRequestBody(mt)
        val (code, text) = exec(Request.Builder().url("$base/auth/v1/token?grant_type=refresh_token")
            .header("apikey", anon).post(body).build())
        if (code in 400..499) { store.clearSession(); throw ApiException("unauthenticated", 401) }
        if (code !in 200..299) throw ApiException(errorCode(code, text), code)  // transient: keep the session
        saveSession(text)
    }

    private fun saveSession(text: String) {
        val o = json.parseToJsonElement(text).jsonObject
        store.put(SecureStore.ACCESS, o["access_token"]!!.jsonPrimitive.content)
        store.put(SecureStore.REFRESH, o["refresh_token"]!!.jsonPrimitive.content)
        store.putLong(SecureStore.EXPIRES, System.currentTimeMillis() / 1000 + (o["expires_in"]?.jsonPrimitive?.long ?: 3600))
        o["user"]?.jsonObject?.let { u ->
            store.userId = u["id"]?.jsonPrimitive?.contentOrNull
            store.email = u["email"]?.jsonPrimitive?.contentOrNull
        }
    }

    // ---------------- Auth ----------------
    suspend fun signIn(email: String, password: String) {
        val body = buildJsonObject { put("email", email); put("password", password) }.toString().toRequestBody(mt)
        val (code, text) = exec(Request.Builder().url("$base/auth/v1/token?grant_type=password").header("apikey", anon).post(body).build())
        if (code !in 200..299) throw ApiException(errorCode(code, text), code)
        saveSession(text)
    }

    /** Returns true if a session was created (false = email verification required first). */
    suspend fun signUp(email: String, password: String): Boolean {
        val body = buildJsonObject { put("email", email); put("password", password) }.toString().toRequestBody(mt)
        val (code, text) = exec(Request.Builder().url("$base/auth/v1/signup").header("apikey", anon).post(body).build())
        if (code !in 200..299) throw ApiException(errorCode(code, text), code)
        val has = json.parseToJsonElement(text).jsonObject["access_token"] != null
        if (has) saveSession(text)
        return has
    }

    suspend fun recover(email: String) {
        val body = buildJsonObject { put("email", email) }.toString().toRequestBody(mt)
        val (code, text) = exec(Request.Builder().url("$base/auth/v1/recover").header("apikey", anon).post(body).build())
        if (code !in 200..299) throw ApiException(errorCode(code, text), code)
    }

    suspend fun signOut() {
        try {
            val t = store.get(SecureStore.ACCESS)
            if (t != null) exec(Request.Builder().url("$base/auth/v1/logout").header("apikey", anon)
                .header("Authorization", "Bearer $t").post("{}".toRequestBody(mt)).build())
        } catch (_: Exception) { /* offline sign-out still clears local state */ }
        store.clearSession()
    }

    // ---------------- PostgREST ----------------
    suspend fun rest(method: String, pathAndQuery: String, body: JsonElement? = null, prefer: String? = null): String {
        val rb = body?.toString()?.toRequestBody(mt)
        val b = Request.Builder().url("$base/rest/v1/$pathAndQuery").header("apikey", anon)
            .header("Authorization", "Bearer ${token()}").header("Accept", "application/json")
        prefer?.let { b.header("Prefer", it) }
        b.method(method, rb ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(mt))
        val (code, text) = exec(b.build())
        if (code !in 200..299) throw ApiException(errorCode(code, text), code)
        return text
    }

    suspend fun restArray(pathAndQuery: String): JsonArray = json.parseToJsonElement(rest("GET", pathAndQuery)).jsonArray

    // ---------------- Edge Functions ----------------
    suspend fun fn(name: String, body: JsonObject): JsonObject {
        val req = Request.Builder().url("$base/functions/v1/$name").header("apikey", anon)
            .header("Authorization", "Bearer ${token()}").post(body.toString().toRequestBody(mt)).build()
        val (code, text) = exec(req)
        if (code !in 200..299) throw ApiException(errorCode(code, text), code)
        return if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
    }
}
