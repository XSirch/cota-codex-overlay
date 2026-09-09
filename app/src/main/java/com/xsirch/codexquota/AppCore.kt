package com.xsirch.codexquota

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AppCore {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val AUTH_BASE = "https://auth.openai.com"
    private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
    private const val USAGE_FALLBACK_URL = "https://chatgpt.com/backend-api/codex/usage"
    private const val CACHE_PREFS = "codex_quota_cache"

    data class QuotaWindow(
        val group: String,
        val windowSeconds: Long,
        val usedPercent: Double,
        val remainingPercent: Double,
        val resetAt: Long
    )

    data class UsageData(
        val plan: String,
        val windows: List<QuotaWindow>,
        val credits: String?,
        val updatedAt: Long,
        val secureDnsUsed: Boolean
    )

    data class LoginSession(
        val authUrl: String,
        val redirectUri: String,
        val verifier: String,
        val state: String,
        val server: ServerSocket
    )

    class SecureStore(private val context: Context) {
        private val prefsName = "codex_quota_secure_v2"
        private val keyAlias = "codex_quota_aes_v3"

        fun put(key: String, value: String) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(key, packed).apply()
        }

        fun get(key: String): String? {
            return try {
                val packed = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(key, null)
                    ?: return null
                val parts = packed.split(":", limit = 2)
                if (parts.size != 2) return null
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
                )
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

        fun clear() {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
        }

        fun hasToken(): Boolean = !get("access_token").isNullOrBlank()

        private fun getOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }
    }

    class NetworkClient {
        private val secureDnsUsed = AtomicBoolean(false)
        private val standard = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        private val doh by lazy {
            val bootstrap = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val dns = DnsOverHttps.Builder()
                .client(bootstrap)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
                    InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1))
                )
                .includeIPv6(true)
                .build()
            standard.newBuilder().dns(dns).build()
        }

        fun execute(request: Request): okhttp3.Response = try {
            standard.newCall(request).execute()
        } catch (e: java.net.UnknownHostException) {
            secureDnsUsed.set(true)
            doh.newCall(request).execute()
        }

        fun secureDnsWasUsed(): Boolean = secureDnsUsed.get()
    }

    fun beginBrowserLogin(): LoginSession {
        val server = try {
            ServerSocket(1455, 1, InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            ServerSocket(1457, 1, InetAddress.getByName("127.0.0.1"))
        }
        server.soTimeout = 15 * 60 * 1000
        val port = server.localPort
        val redirectUri = "http://localhost:$port/auth/callback"
        val verifier = randomBase64Url(32)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val state = randomBase64Url(32)
        val scope = "openid profile email offline_access api.connectors.read api.connectors.invoke"
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to redirectUri,
            "scope" to scope,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "state" to state,
            "originator" to "codex_cli_rs"
        )
        val query = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return LoginSession("$AUTH_BASE/oauth/authorize?$query", redirectUri, verifier, state, server)
    }

    fun finishBrowserLogin(context: Context, session: LoginSession, network: NetworkClient = NetworkClient()) {
        session.server.use { server ->
            var callbackCode: String? = null
            var callbackState: String? = null
            var error: String? = null
            var handled = false
            while (!handled) {
                val socket = server.accept()
                socket.use {
                    val req = readRequestLine(it)
                    val target = req.split(" ").getOrNull(1).orEmpty()
                    if (target.startsWith("/auth/callback")) {
                        val query = target.substringAfter('?', "")
                        val p = parseQuery(query)
                        callbackCode = p["code"]
                        callbackState = p["state"]
                        error = p["error"] ?: p["error_description"]
                        writeBrowserResponse(it, error == null)
                        handled = true
                    } else {
                        writeBrowserResponse(it, false)
                    }
                }
            }
            if (!error.isNullOrBlank()) throw IllegalStateException("Login recusado: $error")
            if (callbackState != session.state) throw IllegalStateException("Falha de segurança no retorno do login (state inválido).")
            val code = callbackCode ?: throw IllegalStateException("O login terminou sem código de autorização.")
            exchangeCode(context, network, code, session.redirectUri, session.verifier)
        }
    }

    private fun readRequestLine(socket: Socket): String {
        socket.soTimeout = 10_000
        return BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine().orEmpty()
    }

    private fun writeBrowserResponse(socket: Socket, success: Boolean) {
        val title = if (success) "Login concluído" else "Não foi possível concluir"
        val body = if (success) "Você já pode voltar ao Cota Codex." else "Volte ao aplicativo e tente novamente."
        val html = """<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:system-ui;background:#090b0d;color:#f2f5f4;display:grid;place-items:center;height:100vh;margin:0}.c{max-width:420px;padding:28px;text-align:center}.ok{width:64px;height:64px;border-radius:20px;background:#57e7be;color:#062e24;display:grid;place-items:center;margin:auto;font-size:30px;font-weight:800}h1{font-size:28px}p{color:#a7b0b7;line-height:1.5}</style></head><body><div class='c'><div class='ok'>✓</div><h1>$title</h1><p>$body</p></div></body></html>"""
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun exchangeCode(context: Context, network: NetworkClient, code: String, redirectUri: String, verifier: String) {
        val body = form(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to CLIENT_ID,
            "code_verifier" to verifier
        )
        val result = request(network, "POST", "$AUTH_BASE/oauth/token", "application/x-www-form-urlencoded", body)
        if (result.first !in 200..299) throw IllegalStateException("Falha ao concluir login (HTTP ${result.first}).")
        saveTokens(context, JSONObject(result.second), null)
    }

    fun fetchUsage(context: Context, network: NetworkClient = NetworkClient()): UsageData {
        val store = SecureStore(context)
        ensureFreshToken(context, network, store)
        var token = store.get("access_token") ?: throw IllegalStateException("Sessão inválida. Entre novamente.")
        val accountId = store.get("account_id").orEmpty()

        fun headers(t: String) = buildMap {
            put("Authorization", "Bearer $t")
            put("Accept", "application/json")
            put("User-Agent", "CodexQuotaAndroid/2.0")
            if (accountId.isNotBlank()) put("ChatGPT-Account-ID", accountId)
        }

        var result = request(network, "GET", USAGE_URL, headers = headers(token))
        if (result.first == 404 || result.first == 405) result = request(network, "GET", USAGE_FALLBACK_URL, headers = headers(token))
        if (result.first == 401) {
            refreshToken(context, network, store)
            token = store.get("access_token") ?: throw IllegalStateException("Sessão expirada.")
            result = request(network, "GET", USAGE_URL, headers = headers(token))
            if (result.first == 404 || result.first == 405) result = request(network, "GET", USAGE_FALLBACK_URL, headers = headers(token))
        }
        if (result.first !in 200..299) throw IllegalStateException("Não foi possível consultar a cota (HTTP ${result.first}).")
        val data = parseUsage(JSONObject(result.second), network.secureDnsWasUsed())
        cacheUsage(context, result.second, network.secureDnsWasUsed())
        return data
    }

    fun cachedUsage(context: Context): UsageData? {
        val p = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val raw = p.getString("raw", null) ?: return null
        return try { parseUsage(JSONObject(raw), p.getBoolean("secure_dns", false)) } catch (_: Exception) { null }
    }

    private fun cacheUsage(context: Context, raw: String, secureDns: Boolean) {
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
            .putString("raw", raw)
            .putBoolean("secure_dns", secureDns)
            .putLong("updated_at", System.currentTimeMillis())
            .apply()
    }

    private fun parseUsage(root: JSONObject, secureDns: Boolean): UsageData {
        val windows = mutableListOf<QuotaWindow>()
        fun add(group: String, rate: JSONObject?) {
            if (rate == null) return
            addWindow(windows, group, rate.optJSONObject("primary_window"))
            addWindow(windows, group, rate.optJSONObject("secondary_window"))
        }
        add("Codex", root.optJSONObject("rate_limit"))
        add("Code Review", root.optJSONObject("code_review_rate_limit"))
        val extra: JSONArray? = root.optJSONArray("additional_rate_limits")
        if (extra != null) for (i in 0 until extra.length()) {
            val item = extra.optJSONObject(i) ?: continue
            add(item.optString("limit_name", "Limite adicional"), item.optJSONObject("rate_limit"))
        }
        val creditsObj = root.optJSONObject("credits")
        val credits = when {
            creditsObj == null -> null
            creditsObj.optBoolean("unlimited", false) -> "Ilimitados"
            creditsObj.optBoolean("has_credits", false) || creditsObj.has("balance") -> creditsObj.optString("balance", "0")
            else -> null
        }
        return UsageData(
            prettyPlan(root.optString("plan_type")),
            windows.sortedWith(compareBy<QuotaWindow> { if (it.group == "Codex") 0 else 1 }.thenBy { it.windowSeconds }),
            credits,
            System.currentTimeMillis(),
            secureDns
        )
    }

    private fun addWindow(list: MutableList<QuotaWindow>, group: String, j: JSONObject?) {
        if (j == null) return
        val used = j.optDouble("used_percent", 0.0).coerceIn(0.0, 100.0)
        list += QuotaWindow(group, j.optLong("limit_window_seconds", 0L), used, (100.0 - used).coerceIn(0.0, 100.0), j.optLong("reset_at", 0L))
    }

    private fun ensureFreshToken(context: Context, network: NetworkClient, store: SecureStore) {
        val expiresAt = store.get("expires_at")?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() > expiresAt - 5 * 60_000L) refreshToken(context, network, store)
    }

    private fun refreshToken(context: Context, network: NetworkClient, store: SecureStore) {
        val refresh = store.get("refresh_token")
        if (refresh.isNullOrBlank()) { store.clear(); throw IllegalStateException("Sessão expirada. Entre novamente.") }
        val body = form("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to CLIENT_ID)
        val result = request(network, "POST", "$AUTH_BASE/oauth/token", "application/x-www-form-urlencoded", body)
        if (result.first !in 200..299) { store.clear(); throw IllegalStateException("Sessão expirada. Entre novamente.") }
        saveTokens(context, JSONObject(result.second), refresh)
    }

    private fun saveTokens(context: Context, json: JSONObject, oldRefresh: String?) {
        val access = json.optString("access_token")
        if (access.isBlank()) throw IllegalStateException("O servidor não retornou access token.")
        val refresh = json.optString("refresh_token").ifBlank { oldRefresh.orEmpty() }
        val idToken = json.optString("id_token")
        val accountId = extractAccountId(idToken).ifBlank { extractAccountId(access) }
        val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        SecureStore(context).apply {
            put("access_token", access)
            put("refresh_token", refresh)
            put("account_id", accountId)
            put("expires_at", (System.currentTimeMillis() + expiresIn * 1000L).toString())
        }
    }

    private fun request(
        network: NetworkClient,
        method: String,
        url: String,
        contentType: String? = null,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        val b = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "CodexQuotaAndroid/2.0")
        headers.forEach { (k, v) -> b.header(k, v) }
        val requestBody = body?.toRequestBody(contentType?.toMediaTypeOrNull())
        b.method(method, requestBody)
        network.execute(b.build()).use { return it.code to it.body?.string().orEmpty() }
    }

    private fun extractAccountId(jwt: String?): String {
        if (jwt.isNullOrBlank()) return ""
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return ""
            val decoded = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val j = JSONObject(String(decoded, StandardCharsets.UTF_8))
            j.optString("chatgpt_account_id").ifBlank {
                j.optString("https://api.openai.com/auth.chatgpt_account_id").ifBlank {
                    j.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id").orEmpty()
                }
            }
        } catch (_: Exception) { "" }
    }

    fun windowLabel(seconds: Long): String = when {
        seconds in 17_900..18_100 -> "5 horas"
        seconds in 604_000..605_500 -> "Semanal"
        seconds == 3600L -> "1 hora"
        seconds == 86_400L -> "24 horas"
        seconds > 0 && seconds % 86_400L == 0L -> "${seconds / 86_400L} dias"
        seconds > 0 && seconds % 3600L == 0L -> "${seconds / 3600L} horas"
        else -> "Limite"
    }

    private fun prettyPlan(raw: String): String = raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun randomBase64Url(bytes: Int): String {
        val b = ByteArray(bytes); SecureRandom().nextBytes(b); return base64Url(b)
    }

    private fun base64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun form(vararg pairs: Pair<String, String>): String = pairs.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
    private fun parseQuery(q: String): Map<String, String> = q.split('&').mapNotNull {
        val p = it.split('=', limit = 2); if (p.isEmpty()) null else URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
    }.toMap()
}
