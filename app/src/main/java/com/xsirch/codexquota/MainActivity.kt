package com.xsirch.codexquota

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLException

class MainActivity : ComponentActivity() {
    companion object {
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val AUTH_BASE = "https://auth.openai.com"
        private const val DEVICE_URL = "https://auth.openai.com/codex/device"
        private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        private const val USAGE_FALLBACK_URL = "https://chatgpt.com/backend-api/codex/usage"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var secureStore: SecureStore
    private lateinit var network: ResilientHttpClient

    private val uiState = mutableStateOf<UiState>(UiState.SignedOut)
    private val isRefreshing = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        secureStore = SecureStore()
        network = ResilientHttpClient()

        setContent {
            CodexQuotaTheme {
                AppRoot()
            }
        }

        if (secureStore.get("access_token").isNullOrBlank()) {
            uiState.value = UiState.SignedOut
        } else {
            loadUsage()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startDeviceLogin() {
        uiState.value = UiState.AuthStarting
        executor.execute {
            try {
                val req = JSONObject().put("client_id", CLIENT_ID)
                val result = request(
                    method = "POST",
                    url = "$AUTH_BASE/api/accounts/deviceauth/usercode",
                    contentType = "application/json",
                    body = req.toString()
                )

                if (result.code == 404) {
                    throw IllegalStateException(
                        "O login por código de dispositivo não está habilitado para esta conta ou workspace."
                    )
                }
                if (result.code !in 200..299) {
                    throw IllegalStateException("Falha ao iniciar login (HTTP ${result.code}).")
                }

                val json = JSONObject(result.body)
                val userCode = json.optString("user_code").ifBlank { json.optString("usercode") }
                val deviceAuthId = json.optString("device_auth_id")
                val interval = json.optString("interval", "15").trim().toLongOrNull()?.coerceAtLeast(5L) ?: 15L

                if (userCode.isBlank() || deviceAuthId.isBlank()) {
                    throw IllegalStateException("A resposta de autenticação veio incompleta.")
                }

                main.post {
                    uiState.value = UiState.AwaitingAuth(userCode)
                    copyCode(userCode, showToast = false)
                    openLoginPage()
                    Toast.makeText(this, "Código copiado. Cole no navegador.", Toast.LENGTH_LONG).show()
                }

                val deadline = System.currentTimeMillis() + 15 * 60_000L
                while (System.currentTimeMillis() < deadline && !executor.isShutdown) {
                    Thread.sleep(interval * 1000L)

                    val pollBody = JSONObject()
                        .put("device_auth_id", deviceAuthId)
                        .put("user_code", userCode)

                    val poll = request(
                        method = "POST",
                        url = "$AUTH_BASE/api/accounts/deviceauth/token",
                        contentType = "application/json",
                        body = pollBody.toString()
                    )

                    if (poll.code == 403 || poll.code == 404 || poll.code == 429) continue
                    if (poll.code !in 200..299) {
                        throw IllegalStateException("Falha ao verificar login (HTTP ${poll.code}).")
                    }

                    val tokenJson = JSONObject(poll.body)
                    val authCode = tokenJson.optString("authorization_code")
                    val verifier = tokenJson.optString("code_verifier")
                    if (authCode.isBlank() || verifier.isBlank()) {
                        throw IllegalStateException("A autorização foi concluída, mas o token retornado é inválido.")
                    }

                    exchangeAuthorizationCode(authCode, verifier)
                    main.post { loadUsage() }
                    return@execute
                }

                throw IllegalStateException("O código expirou. Gere um novo código e tente novamente.")
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private fun exchangeAuthorizationCode(code: String, verifier: String) {
        val body = form(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to "$AUTH_BASE/deviceauth/callback",
            "client_id" to CLIENT_ID,
            "code_verifier" to verifier
        )
        val result = request(
            method = "POST",
            url = "$AUTH_BASE/oauth/token",
            contentType = "application/x-www-form-urlencoded",
            body = body
        )
        if (result.code !in 200..299) {
            throw IllegalStateException("Falha ao concluir autenticação (HTTP ${result.code}).")
        }
        saveTokens(JSONObject(result.body), null)
    }

    private fun saveTokens(json: JSONObject, oldRefresh: String?) {
        val access = json.optString("access_token")
        if (access.isBlank()) throw IllegalStateException("O servidor não retornou um access token.")

        val refresh = json.optString("refresh_token").ifBlank { oldRefresh.orEmpty() }
        val idToken = json.optString("id_token")
        val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        val accountId = extractAccountId(idToken).ifBlank { extractAccountId(access) }

        secureStore.put("access_token", access)
        secureStore.put("refresh_token", refresh)
        secureStore.put("account_id", accountId)
        secureStore.put("expires_at", (System.currentTimeMillis() + expiresIn * 1000L).toString())
    }

    private fun loadUsage() {
        isRefreshing.value = true
        if (uiState.value !is UiState.Dashboard) uiState.value = UiState.LoadingUsage

        executor.execute {
            try {
                ensureFreshToken()

                var token = secureStore.get("access_token")
                val accountId = secureStore.get("account_id").orEmpty()
                if (token.isNullOrBlank()) throw IllegalStateException("Sessão inválida. Entre novamente.")

                fun usageHeaders(currentToken: String): Map<String, String> = buildMap {
                    put("Authorization", "Bearer $currentToken")
                    put("Accept", "application/json")
                    put("User-Agent", "CodexQuotaAndroid/1.1")
                    if (accountId.isNotBlank()) put("ChatGPT-Account-ID", accountId)
                }

                var result = request("GET", USAGE_URL, headers = usageHeaders(token))
                if (result.code == 404 || result.code == 405) {
                    result = request("GET", USAGE_FALLBACK_URL, headers = usageHeaders(token))
                }

                if (result.code == 401) {
                    refreshToken()
                    token = secureStore.get("access_token")
                    if (token.isNullOrBlank()) throw IllegalStateException("Sessão expirada.")
                    result = request("GET", USAGE_URL, headers = usageHeaders(token))
                    if (result.code == 404 || result.code == 405) {
                        result = request("GET", USAGE_FALLBACK_URL, headers = usageHeaders(token))
                    }
                }

                if (result.code !in 200..299) {
                    throw IllegalStateException("Não foi possível consultar a cota (HTTP ${result.code}).")
                }

                val usage = parseUsage(JSONObject(result.body))
                main.post {
                    isRefreshing.value = false
                    uiState.value = UiState.Dashboard(usage)
                }
            } catch (e: Exception) {
                main.post { isRefreshing.value = false }
                showError(e)
            }
        }
    }

    private fun ensureFreshToken() {
        val expiresAt = secureStore.get("expires_at")?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() > expiresAt - 5 * 60_000L) refreshToken()
    }

    private fun refreshToken() {
        val refresh = secureStore.get("refresh_token")
        if (refresh.isNullOrBlank()) {
            secureStore.clear()
            throw IllegalStateException("Sessão expirada. Entre novamente.")
        }

        val body = form(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to CLIENT_ID
        )

        val result = request(
            method = "POST",
            url = "$AUTH_BASE/oauth/token",
            contentType = "application/x-www-form-urlencoded",
            body = body
        )

        if (result.code !in 200..299) {
            secureStore.clear()
            throw IllegalStateException("Sessão expirada. Entre novamente.")
        }

        saveTokens(JSONObject(result.body), refresh)
    }

    private fun parseUsage(root: JSONObject): UsageData {
        val windows = mutableListOf<QuotaWindow>()

        fun addWindows(group: String, rate: JSONObject?) {
            if (rate == null) return
            addWindowIfPresent(windows, group, rate.optJSONObject("primary_window"))
            addWindowIfPresent(windows, group, rate.optJSONObject("secondary_window"))
        }

        addWindows("Codex", root.optJSONObject("rate_limit"))
        addWindows("Code Review", root.optJSONObject("code_review_rate_limit"))

        val additional: JSONArray? = root.optJSONArray("additional_rate_limits")
        if (additional != null) {
            for (i in 0 until additional.length()) {
                val item = additional.optJSONObject(i) ?: continue
                val name = item.optString("limit_name", "Limite adicional")
                addWindows(name, item.optJSONObject("rate_limit"))
            }
        }

        val creditsObj = root.optJSONObject("credits")
        val credits = when {
            creditsObj == null -> null
            creditsObj.optBoolean("unlimited", false) -> "Ilimitados"
            creditsObj.optBoolean("has_credits", false) || creditsObj.has("balance") ->
                creditsObj.optString("balance", "0")
            else -> null
        }

        val ordered = windows.sortedWith(
            compareBy<QuotaWindow> { if (it.group == "Codex") 0 else 1 }
                .thenBy { it.windowSeconds }
        )

        return UsageData(
            plan = prettyPlan(root.optString("plan_type")),
            windows = ordered,
            credits = credits,
            updatedAt = System.currentTimeMillis(),
            secureDnsUsed = network.secureDnsWasUsed()
        )
    }

    private fun addWindowIfPresent(target: MutableList<QuotaWindow>, group: String, json: JSONObject?) {
        if (json == null) return
        val used = json.optDouble("used_percent", 0.0).coerceIn(0.0, 100.0)
        target += QuotaWindow(
            group = group,
            windowSeconds = json.optLong("limit_window_seconds", 0L),
            usedPercent = used,
            remainingPercent = (100.0 - used).coerceIn(0.0, 100.0),
            resetAt = json.optLong("reset_at", 0L)
        )
    }

    private fun showError(error: Exception) {
        val raw = error.message.orEmpty()
        val friendly = when (error) {
            is UnknownHostException ->
                "Não consegui resolver o endereço da OpenAI. O app tentou o DNS do Android e também um DNS seguro."
            is SocketTimeoutException ->
                "A conexão demorou demais para responder. Verifique sua internet e tente novamente."
            is SSLException ->
                "Não foi possível estabelecer uma conexão HTTPS segura."
            else -> raw.ifBlank { "Ocorreu um erro inesperado." }
        }

        main.post {
            uiState.value = UiState.Error(
                title = if (error is UnknownHostException) "Falha de DNS" else "Não foi possível conectar",
                message = friendly,
                technical = raw.takeIf { it.isNotBlank() && it != friendly }
            )
        }
    }

    private fun retryFromError() {
        if (secureStore.get("access_token").isNullOrBlank()) startDeviceLogin() else loadUsage()
    }

    private fun logout() {
        secureStore.clear()
        uiState.value = UiState.SignedOut
    }

    private fun copyCode(code: String, showToast: Boolean = true) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Código de login Codex", code))
        if (showToast) Toast.makeText(this, "Código copiado", Toast.LENGTH_SHORT).show()
    }

    private fun openLoginPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DEVICE_URL)))
        } catch (e: Exception) {
            Toast.makeText(this, "Não encontrei um navegador para abrir o login.", Toast.LENGTH_LONG).show()
        }
    }

    private fun request(
        method: String,
        url: String,
        contentType: String? = null,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val requestBuilder = Request.Builder().url(url)
        requestBuilder.header("Accept", "application/json")
        requestBuilder.header("User-Agent", "CodexQuotaAndroid/1.1")

        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        val requestBody = body?.toRequestBody(contentType?.toMediaTypeOrNull())
        requestBuilder.method(method, requestBody)

        network.execute(requestBuilder.build()).use { response ->
            return HttpResult(response.code, response.body?.string().orEmpty())
        }
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) ->
            "${java.net.URLEncoder.encode(key, "UTF-8") }=${java.net.URLEncoder.encode(value, "UTF-8") }"
        }

    private fun extractAccountId(jwt: String?): String {
        if (jwt.isNullOrBlank()) return ""
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return ""
            val decoded = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val json = JSONObject(String(decoded, StandardCharsets.UTF_8))
            json.optString("chatgpt_account_id").ifBlank {
                json.optString("https://api.openai.com/auth.chatgpt_account_id").ifBlank {
                    json.optJSONObject("https://api.openai.com/auth")
                        ?.optString("chatgpt_account_id")
                        .orEmpty()
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppRoot() {
        val state = uiState.value
        var menuOpen by remember { mutableStateOf(false) }
        val showActions = state is UiState.Dashboard

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppMark()
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Cota Codex", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                if (state is UiState.Dashboard && state.usage.plan.isNotBlank()) {
                                    Text(
                                        state.usage.plan,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (showActions) {
                            IconButton(onClick = { loadUsage() }, enabled = !isRefreshing.value) {
                                if (isRefreshing.value) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar")
                                }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Text("•••", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Sair da conta") },
                                        leadingIcon = { Icon(Icons.Rounded.Logout, null) },
                                        onClick = { menuOpen = false; logout() }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            when (state) {
                UiState.SignedOut -> SignedOutScreen(innerPadding)
                UiState.AuthStarting -> LoadingScreen(innerPadding, "Criando um código seguro…", "Conectando ao serviço de autenticação do ChatGPT.")
                is UiState.AwaitingAuth -> AwaitingAuthScreen(innerPadding, state)
                UiState.LoadingUsage -> LoadingScreen(innerPadding, "Lendo sua cota…", "Buscando as janelas e horários de reset da sua conta.")
                is UiState.Dashboard -> DashboardScreen(innerPadding, state.usage)
                is UiState.Error -> ErrorScreen(innerPadding, state)
            }
        }
    }

    @Composable
    private fun AppMark() {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1FD1A5), Color(0xFF157BFF)))),
            contentAlignment = Alignment.Center
        ) {
            Text("C", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun SignedOutScreen(padding: PaddingValues) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Seu Codex,\nem um relance.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, lineHeight = 44.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Veja quanto da sua cota ainda está disponível e exatamente quando cada janela será renovada.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 24.sp
                )
            }
            item {
                PremiumCard {
                    FeatureRow(Icons.Rounded.Security, "Login do ChatGPT", "Você entra no navegador. O app não recebe sua senha.")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    FeatureRow(Icons.Rounded.CloudDone, "Cota em tempo real", "Consulta diretamente as janelas de uso vinculadas à sua conta.")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    FeatureRow(Icons.Rounded.CheckCircle, "Conexão resiliente", "Se o DNS do Android falhar, o app tenta DNS-over-HTTPS automaticamente.")
                }
            }
            item {
                Button(
                    onClick = { startDeviceLogin() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9FFF8), contentColor = Color(0xFF073D30))
                ) {
                    Icon(Icons.Rounded.Login, null); Spacer(Modifier.width(10.dp)); Text("Conectar ao ChatGPT", fontWeight = FontWeight.Bold)
                }
            }
            item {
                Text(
                    "Este é um utilitário pessoal e não um aplicativo oficial da OpenAI. A rota de uso do Codex é interna e pode mudar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
        Row(verticalAlignment = Alignment.Top) {
            Surface(modifier = Modifier.size(42.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color(0xFF57E7BE)) }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(3.dp))
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }

    @Composable
    private fun AwaitingAuthScreen(padding: PaddingValues, state: UiState.AwaitingAuth) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Finalize no navegador", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("O código já foi copiado. Entre no ChatGPT e cole quando a página pedir.", color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
            }
            item {
                PremiumCard {
                    Text("CÓDIGO ÚNICO", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Spacer(Modifier.height(14.dp))
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xFF0B0E11), border = BorderStroke(1.dp, Color(0xFF2A333A))) {
                        Text(state.code, modifier = Modifier.padding(vertical = 22.dp, horizontal = 16.dp), textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 2.sp, color = Color(0xFF79F2CE))
                    }
                    Spacer(Modifier.height(18.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF57E7BE), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text("Aguardando você autorizar. O app detecta automaticamente quando terminar.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            item {
                Button(onClick = { copyCode(state.code, false); openLoginPage() }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.OpenInBrowser, null); Spacer(Modifier.width(10.dp)); Text("Abrir página de login")
                }
            }
            item {
                OutlinedButton(onClick = { copyCode(state.code) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(9.dp)); Text("Copiar código novamente")
                }
            }
        }
    }

    @Composable
    private fun LoadingScreen(padding: PaddingValues, title: String, subtitle: String) {
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(38.dp), color = Color(0xFF57E7BE), strokeWidth = 3.dp)
                Spacer(Modifier.height(20.dp)); Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp); Spacer(Modifier.height(6.dp))
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun DashboardScreen(padding: PaddingValues, usage: UsageData) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(usage.updatedAt) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
        val primary = usage.windows.firstOrNull { it.group == "Codex" }
        val remaining = usage.windows.filterNot { it === primary }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Disponível agora", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Text("Sua cota do Codex", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    }
                    AssistChip(onClick = {}, label = { Text("AO VIVO", fontWeight = FontWeight.Bold, fontSize = 10.sp) }, leadingIcon = { Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF57E7BE))) })
                }
            }
            if (primary != null) item { HeroQuotaCard(primary, now) }
            if (remaining.isNotEmpty()) {
                item { Text("Outras janelas", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
                items(remaining, key = { "${it.group}-${it.windowSeconds}-${it.resetAt}" }) { QuotaRowCard(it, now) }
            }
            if (usage.credits != null) {
                item {
                    PremiumCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Créditos", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                Text(usage.credits, fontWeight = FontWeight.Bold, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
                            }
                            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { FooterStatus(usage, now) }
        }
    }

    @Composable
    private fun HeroQuotaCard(window: QuotaWindow, now: Long) {
        val accent = quotaColor(window.remainingPercent)
        PremiumCard {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                QuotaGauge(window.remainingPercent, accent); Spacer(Modifier.width(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("${window.group} · ${windowLabel(window.windowSeconds)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp)); Text(relativeReset(window.resetAt, now), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Spacer(Modifier.height(4.dp)); Text(absoluteReset(window.resetAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun QuotaGauge(remaining: Double, accent: Color) {
        Box(modifier = Modifier.size(138.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 12.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.08f), -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(accent, -90f, (360f * (remaining / 100.0)).toFloat(), false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${remaining.toInt()}%", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Text("restante", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun QuotaRowCard(window: QuotaWindow, now: Long) {
        val accent = quotaColor(window.remainingPercent)
        PremiumCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(window.group, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(windowLabel(window.windowSeconds), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Text("${window.remainingPercent.toInt()}%", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = accent)
            }
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(progress = (window.remainingPercent / 100.0).toFloat(), modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape), color = accent, trackColor = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(7.dp))
                Text("${relativeReset(window.resetAt, now)} · ${absoluteReset(window.resetAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun FooterStatus(usage: UsageData, now: Long) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Atualizado ${relativeAgo(usage.updatedAt, now)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            if (usage.secureDnsUsed) {
                Spacer(Modifier.height(8.dp))
                AssistChip(onClick = {}, label = { Text("DNS seguro ativado automaticamente", fontSize = 10.sp) }, leadingIcon = { Icon(Icons.Rounded.Security, null, modifier = Modifier.size(15.dp), tint = Color(0xFF57E7BE)) })
            }
        }
    }

    @Composable
    private fun ErrorScreen(padding: PaddingValues, error: UiState.Error) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Surface(modifier = Modifier.size(54.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFF3A171B)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFFF8A92), modifier = Modifier.size(28.dp)) } }
                Spacer(Modifier.height(20.dp)); Text(error.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp))
                Text(error.message, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
            }
            if (error.technical != null) {
                item {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF0B0E11), border = BorderStroke(1.dp, Color(0xFF252C32))) {
                        Column(Modifier.padding(14.dp)) {
                            Text("DETALHES TÉCNICOS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(7.dp)); Text(error.technical, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
            item {
                Button(onClick = { retryFromError() }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(9.dp)); Text("Tentar novamente") }
            }
            item { TextButton(onClick = { secureStore.clear(); uiState.value = UiState.SignedOut }, modifier = Modifier.fillMaxWidth()) { Text("Voltar para o login") } }
        }
    }

    @Composable
    private fun PremiumCard(content: @Composable ColumnScope.() -> Unit) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(modifier = Modifier.padding(18.dp), content = content)
        }
    }

    private fun windowLabel(seconds: Long): String = when {
        seconds in 17_900..18_100 -> "Janela de 5 horas"
        seconds in 604_000..605_500 -> "Limite semanal"
        seconds == 3600L -> "Janela de 1 hora"
        seconds == 86_400L -> "Janela de 24 horas"
        seconds > 0 && seconds % 86_400L == 0L -> "Janela de ${seconds / 86_400L} dias"
        seconds > 0 && seconds % 3600L == 0L -> "Janela de ${seconds / 3600L} horas"
        else -> "Limite de uso"
    }

    private fun absoluteReset(unixSeconds: Long): String {
        if (unixSeconds <= 0) return "Reset indisponível"
        val formatter = DateTimeFormatter.ofPattern("dd/MM · HH:mm", Locale("pt", "BR")).withZone(ZoneId.systemDefault())
        return formatter.format(Instant.ofEpochSecond(unixSeconds))
    }

    private fun relativeReset(unixSeconds: Long, nowMillis: Long): String {
        if (unixSeconds <= 0) return "Reset indisponível"
        val diff = unixSeconds * 1000L - nowMillis
        if (diff <= 0) return "Renovando agora"
        val minutes = diff / 60_000L; val days = minutes / 1440; val hours = (minutes % 1440) / 60; val mins = minutes % 60
        return when { days > 0 -> "Renova em ${days}d ${hours}h"; hours > 0 -> "Renova em ${hours}h ${mins}min"; else -> "Renova em ${mins.coerceAtLeast(1)}min" }
    }

    private fun relativeAgo(timestamp: Long, now: Long): String {
        val seconds = ((now - timestamp).coerceAtLeast(0L) / 1000L)
        return when { seconds < 15 -> "agora"; seconds < 60 -> "há ${seconds}s"; else -> "há ${seconds / 60}min" }
    }

    private fun prettyPlan(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
        "prolite" -> "Prolite"; "plus" -> "Plus"; "pro" -> "Pro"; "go" -> "Go"; "free" -> "Free"; "business" -> "Business"; "enterprise" -> "Enterprise"; "team" -> "Team"; "edu", "education" -> "Edu"
        else -> raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun quotaColor(remaining: Double): Color = when { remaining >= 50.0 -> Color(0xFF57E7BE); remaining >= 20.0 -> Color(0xFFFFC857); else -> Color(0xFFFF727C) }

    private data class HttpResult(val code: Int, val body: String)
    private data class QuotaWindow(val group: String, val windowSeconds: Long, val usedPercent: Double, val remainingPercent: Double, val resetAt: Long)
    private data class UsageData(val plan: String, val windows: List<QuotaWindow>, val credits: String?, val updatedAt: Long, val secureDnsUsed: Boolean)

    private sealed interface UiState {
        data object SignedOut : UiState
        data object AuthStarting : UiState
        data class AwaitingAuth(val code: String) : UiState
        data object LoadingUsage : UiState
        data class Dashboard(val usage: UsageData) : UiState
        data class Error(val title: String, val message: String, val technical: String?) : UiState
    }

    private inner class SecureStore {
        private val prefsName = "codex_quota_secure"
        private val keyAlias = "codex_quota_aes_v2"

        fun put(key: String, value: String) {
            val secretKey = getOrCreateKey(); val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
            getSharedPreferences(prefsName, MODE_PRIVATE).edit().putString(key, packed).apply()
        }

        fun get(key: String): String? = try {
            val packed = getSharedPreferences(prefsName, MODE_PRIVATE).getString(key, null) ?: return null
            val parts = packed.split(":", limit = 2); if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP); val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (_: Exception) { null }

        fun clear() { getSharedPreferences(prefsName, MODE_PRIVATE).edit().clear().apply() }

        private fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()
            generator.init(spec); return generator.generateKey()
        }
    }

    private class ResilientHttpClient {
        private val secureDnsUsed = AtomicBoolean(false)
        private val standard = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build()
        private val secureDnsClient: OkHttpClient by lazy {
            val bootstrap = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
            val dns = DnsOverHttps.Builder().client(bootstrap).url("https://cloudflare-dns.com/dns-query".toHttpUrl()).bootstrapDnsHosts(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1))).includeIPv6(true).build()
            standard.newBuilder().dns(dns).build()
        }
        fun execute(request: Request): okhttp3.Response = try { standard.newCall(request).execute() } catch (e: UnknownHostException) { secureDnsUsed.set(true); secureDnsClient.newCall(request).execute() }
        fun secureDnsWasUsed(): Boolean = secureDnsUsed.get()
    }
}

private val AppColors = darkColorScheme(
    primary = Color(0xFF57E7BE), onPrimary = Color(0xFF062E24), primaryContainer = Color(0xFF123B31), onPrimaryContainer = Color(0xFFB8FFE9),
    background = Color(0xFF090B0D), onBackground = Color(0xFFF2F5F4), surface = Color(0xFF111519), onSurface = Color(0xFFF2F5F4),
    surfaceVariant = Color(0xFF1A2025), onSurfaceVariant = Color(0xFFA7B0B7), outline = Color(0xFF3A434A), outlineVariant = Color(0xFF252C32), error = Color(0xFFFF727C)
)

@Composable
private fun CodexQuotaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = Typography(), content = content)
}
