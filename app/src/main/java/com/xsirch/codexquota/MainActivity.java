package com.xsirch.codexquota;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String AUTH_BASE = "https://auth.openai.com";
    private static final String DEVICE_URL = "https://auth.openai.com/codex/device";
    private static final String USAGE_URL = "https://chatgpt.com/backend-api/codex/usage";
    private static final String USAGE_FALLBACK_URL = "https://chatgpt.com/backend-api/wham/usage";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private TextView status;
    private Button loginButton;
    private Button refreshButton;
    private Button logoutButton;
    private SecureStore secureStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secureStore = new SecureStore();
        buildUi();

        if (secureStore.has("access_token")) {
            setStatus("Atualizando cota…");
            loadUsage();
        } else {
            renderSignedOut();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 247, 248));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(24), dp(22), dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("Cota Codex", 30, Typeface.BOLD, Color.rgb(20,20,20));
        content.addView(title);

        TextView subtitle = text("Uso restante e próximo reset da sua conta", 15, Typeface.NORMAL, Color.rgb(95,95,100));
        subtitle.setPadding(0, dp(5), 0, dp(18));
        content.addView(subtitle);

        status = text("", 14, Typeface.NORMAL, Color.rgb(95,95,100));
        status.setPadding(0, 0, 0, dp(14));
        content.addView(status);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        loginButton = button("Entrar com ChatGPT");
        loginButton.setOnClickListener(v -> startDeviceLogin());
        actions.addView(loginButton, new LinearLayout.LayoutParams(0, dp(48), 1));

        refreshButton = button("Atualizar");
        refreshButton.setOnClickListener(v -> loadUsage());
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        refreshLp.setMargins(dp(10),0,0,0);
        actions.addView(refreshButton, refreshLp);

        content.addView(actions);

        logoutButton = button("Sair da conta");
        logoutButton.setOnClickListener(v -> {
            secureStore.clear();
            renderSignedOut();
        });
        LinearLayout.LayoutParams logoutLp = new LinearLayout.LayoutParams(-1, dp(46));
        logoutLp.setMargins(0, dp(10), 0, dp(8));
        content.addView(logoutButton, logoutLp);

        TextView note = text("Dados consultados diretamente do serviço de uso do Codex. O endpoint é interno e pode mudar em versões futuras.", 12, Typeface.NORMAL, Color.rgb(120,120,125));
        note.setPadding(0, dp(12), 0, 0);
        content.addView(note);

        setContentView(scroll);
    }

    private void renderSignedOut() {
        clearCards();
        status.setText("Conecte sua conta para consultar a cota real do Codex.");
        loginButton.setVisibility(View.VISIBLE);
        refreshButton.setVisibility(View.GONE);
        logoutButton.setVisibility(View.GONE);
    }

    private void clearCards() {
        while (content.getChildCount() > 6) {
            content.removeViewAt(6);
        }
    }

    private void setStatus(String s) {
        main.post(() -> status.setText(s));
    }

    private void startDeviceLogin() {
        loginButton.setEnabled(false);
        setStatus("Gerando código de login…");

        executor.execute(() -> {
            try {
                JSONObject req = new JSONObject().put("client_id", CLIENT_ID);
                HttpResult r = request("POST", AUTH_BASE + "/api/accounts/deviceauth/usercode", "application/json", req.toString(), null);
                if (r.code / 100 != 2) throw new Exception("Falha ao iniciar login (HTTP " + r.code + ")");

                JSONObject j = new JSONObject(r.body);
                String userCode = j.getString("user_code");
                String deviceAuthId = j.getString("device_auth_id");
                int interval = Math.max(5, j.optInt("interval", 15));

                main.post(() -> {
                    setStatus("Código: " + userCode + " — conclua o login no navegador. O app detectará automaticamente.");
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DEVICE_URL)));
                    } catch (Exception ignored) {}
                });

                long deadline = System.currentTimeMillis() + 10 * 60_000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval * 1000L);
                    JSONObject pollReq = new JSONObject()
                            .put("device_auth_id", deviceAuthId)
                            .put("user_code", userCode);
                    HttpResult poll = request("POST", AUTH_BASE + "/api/accounts/deviceauth/token", "application/json", pollReq.toString(), null);
                    if (poll.code == 403 || poll.code == 404) continue;
                    if (poll.code / 100 != 2) throw new Exception("Falha verificando login (HTTP " + poll.code + ")");

                    JSONObject p = new JSONObject(poll.body);
                    String authCode = p.getString("authorization_code");
                    String verifier = p.getString("code_verifier");
                    exchangeAuthorizationCode(authCode, verifier);
                    main.post(() -> loginButton.setEnabled(true));
                    loadUsage();
                    return;
                }
                throw new Exception("Tempo de login expirado. Tente novamente.");
            } catch (Exception e) {
                main.post(() -> loginButton.setEnabled(true));
                setStatus(e.getMessage());
            }
        });
    }

    private void exchangeAuthorizationCode(String code, String verifier) throws Exception {
        String body = form(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", "https://auth.openai.com/deviceauth/callback",
                "client_id", CLIENT_ID,
                "code_verifier", verifier
        );
        HttpResult r = request("POST", AUTH_BASE + "/oauth/token", "application/x-www-form-urlencoded", body, null);
        if (r.code / 100 != 2) throw new Exception("Falha ao concluir autenticação (HTTP " + r.code + ")");
        saveTokens(new JSONObject(r.body), null);
    }

    private void saveTokens(JSONObject j, String oldRefresh) throws Exception {
        String access = j.getString("access_token");
        String refresh = j.optString("refresh_token", oldRefresh == null ? "" : oldRefresh);
        String idToken = j.optString("id_token", "");
        long expiresIn = j.optLong("expires_in", 3600);
        String accountId = extractAccountId(idToken);
        if (accountId.isEmpty()) accountId = extractAccountId(access);
        if (accountId.isEmpty()) throw new Exception("Não foi possível identificar a conta ChatGPT.");

        secureStore.put("access_token", access);
        secureStore.put("refresh_token", refresh);
        secureStore.put("account_id", accountId);
        secureStore.put("expires_at", String.valueOf(System.currentTimeMillis() + Math.max(60, expiresIn) * 1000L));
    }

    private void loadUsage() {
        setStatus("Atualizando cota…");
        refreshButton.setEnabled(false);

        executor.execute(() -> {
            try {
                ensureFreshToken();
                String token = secureStore.get("access_token");
                String accountId = secureStore.get("account_id");
                if (token == null || accountId == null) throw new Exception("Sessão inválida. Entre novamente.");

                JSONObject headers = new JSONObject()
                        .put("Authorization", "Bearer " + token)
                        .put("ChatGPT-Account-Id", accountId)
                        .put("Accept", "application/json")
                        .put("User-Agent", "codex-cli");

                HttpResult r = request("GET", USAGE_URL, null, null, headers);
                if (r.code == 404 || r.code == 405) {
                    r = request("GET", USAGE_FALLBACK_URL, null, null, headers);
                }
                if (r.code == 401) {
                    refreshToken();
                    token = secureStore.get("access_token");
                    headers.put("Authorization", "Bearer " + token);
                    r = request("GET", USAGE_URL, null, null, headers);
                    if (r.code == 404 || r.code == 405) r = request("GET", USAGE_FALLBACK_URL, null, null, headers);
                }
                if (r.code / 100 != 2) throw new Exception("Não foi possível consultar a cota (HTTP " + r.code + ")");

                JSONObject usage = new JSONObject(r.body);
                main.post(() -> renderUsage(usage));
            } catch (Exception e) {
                setStatus(e.getMessage());
            } finally {
                main.post(() -> refreshButton.setEnabled(true));
            }
        });
    }

    private void ensureFreshToken() throws Exception {
        long expiresAt;
        try { expiresAt = Long.parseLong(secureStore.get("expires_at")); }
        catch (Exception e) { expiresAt = 0; }
        if (System.currentTimeMillis() > expiresAt - 5 * 60_000L) refreshToken();
    }

    private void refreshToken() throws Exception {
        String refresh = secureStore.get("refresh_token");
        if (refresh == null || refresh.isEmpty()) throw new Exception("Sessão expirada. Entre novamente.");

        String body = form(
                "grant_type", "refresh_token",
                "refresh_token", refresh,
                "client_id", CLIENT_ID
        );
        HttpResult r = request("POST", AUTH_BASE + "/oauth/token", "application/x-www-form-urlencoded", body, null);
        if (r.code / 100 != 2) {
            secureStore.clear();
            throw new Exception("Sessão expirada. Entre novamente.");
        }
        saveTokens(new JSONObject(r.body), refresh);
    }

    private void renderUsage(JSONObject usage) {
        clearCards();
        loginButton.setVisibility(View.GONE);
        refreshButton.setVisibility(View.VISIBLE);
        logoutButton.setVisibility(View.VISIBLE);

        String plan = usage.optString("plan_type", "");
        status.setText(plan.isEmpty() ? "Atualizado agora" : "Plano: " + plan + " • atualizado agora");

        JSONObject rate = usage.optJSONObject("rate_limit");
        if (rate != null) {
            addWindow("Codex", rate.optJSONObject("primary_window"));
            addWindow("Codex", rate.optJSONObject("secondary_window"));
        }

        JSONObject review = usage.optJSONObject("code_review_rate_limit");
        if (review != null) {
            addWindow("Code Review", review.optJSONObject("primary_window"));
            addWindow("Code Review", review.optJSONObject("secondary_window"));
        }

        JSONArray extra = usage.optJSONArray("additional_rate_limits");
        if (extra != null) {
            for (int i = 0; i < extra.length(); i++) {
                JSONObject item = extra.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("limit_name", "Limite adicional");
                JSONObject er = item.optJSONObject("rate_limit");
                if (er != null) {
                    addWindow(name, er.optJSONObject("primary_window"));
                    addWindow(name, er.optJSONObject("secondary_window"));
                }
            }
        }

        JSONObject credits = usage.optJSONObject("credits");
        if (credits != null && (credits.optBoolean("has_credits", false) || credits.has("balance"))) {
            String balance = credits.optString("balance", "0");
            addSimpleCard("Créditos", credits.optBoolean("unlimited", false) ? "Ilimitados" : balance);
        }
    }

    private void addWindow(String group, JSONObject w) {
        if (w == null) return;
        double used = Math.max(0, Math.min(100, w.optDouble("used_percent", 0)));
        int left = (int)Math.round(100.0 - used);
        long windowSec = w.optLong("limit_window_seconds", 0);
        long resetAt = w.optLong("reset_at", 0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);

        TextView label = text(group + " • " + windowName(windowSec), 14, Typeface.BOLD, Color.rgb(65,65,70));
        card.addView(label);

        TextView remaining = text(left + "% restante", 28, Typeface.BOLD, Color.rgb(20,20,20));
        remaining.setPadding(0, dp(6), 0, dp(8));
        card.addView(remaining);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress((int)Math.round(left * 10));
        card.addView(bar, new LinearLayout.LayoutParams(-1, dp(8)));

        String resetText = resetAt > 0 ? "Reset: " + formatReset(resetAt) : "Reset indisponível";
        TextView reset = text(resetText, 14, Typeface.NORMAL, Color.rgb(85,85,90));
        reset.setPadding(0, dp(10), 0, 0);
        card.addView(reset);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(12), 0, 0);
        content.addView(card, lp);
    }

    private void addSimpleCard(String label, String value) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
        card.addView(text(label, 14, Typeface.BOLD, Color.rgb(65,65,70)));
        TextView v = text(value, 25, Typeface.BOLD, Color.rgb(20,20,20));
        v.setPadding(0, dp(6), 0, 0);
        card.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(12), 0, 0);
        content.addView(card, lp);
    }

    private String windowName(long sec) {
        if (sec >= 604000 && sec <= 605500) return "semanal";
        if (sec >= 17900 && sec <= 18100) return "5 horas";
        if (sec == 3600) return "1 hora";
        if (sec == 86400) return "24 horas";
        if (sec > 0 && sec % 86400 == 0) return (sec / 86400) + " dias";
        if (sec > 0 && sec % 3600 == 0) return (sec / 3600) + " horas";
        return "limite";
    }

    private String formatReset(long unixSeconds) {
        long when = unixSeconds * 1000L;
        long diff = when - System.currentTimeMillis();
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", new Locale("pt", "BR"));
        fmt.setTimeZone(TimeZone.getDefault());
        String absolute = fmt.format(new Date(when));
        if (diff <= 0) return absolute;
        long totalMin = diff / 60_000L;
        long days = totalMin / 1440;
        long hours = (totalMin % 1440) / 60;
        long mins = totalMin % 60;
        String relative;
        if (days > 0) relative = days + "d " + hours + "h";
        else if (hours > 0) relative = hours + "h " + mins + "min";
        else relative = mins + "min";
        return absolute + " (em " + relative + ")";
    }

    private static String extractAccountId(String jwt) {
        if (jwt == null || jwt.isEmpty()) return "";
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return "";
            byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject j = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            String id = j.optString("chatgpt_account_id", "");
            if (!id.isEmpty()) return id;
            id = j.optString("https://api.openai.com/auth.chatgpt_account_id", "");
            if (!id.isEmpty()) return id;
            JSONObject auth = j.optJSONObject("https://api.openai.com/auth");
            return auth == null ? "" : auth.optString("chatgpt_account_id", "");
        } catch (Exception e) {
            return "";
        }
    }

    private HttpResult request(String method, String url, String contentType, String body, JSONObject headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "CodexQuotaAndroid/1.0");
        if (headers != null) {
            java.util.Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                c.setRequestProperty(k, headers.optString(k));
            }
        }
        if (body != null) {
            c.setDoOutput(true);
            if (contentType != null) c.setRequestProperty("Content-Type", contentType);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        c.disconnect();
        return new HttpResult(code, sb.toString());
    }

    private String form(String... pairs) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(pairs[i], "UTF-8"));
            sb.append('=');
            sb.append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
        }
        return sb.toString();
    }

    private TextView text(String value, int sp, int style, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(14);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body; }
    }

    private class SecureStore {
        private static final String PREFS = "codex_quota_secure";
        private static final String KEY_ALIAS = "codex_quota_aes";

        void put(String key, String value) throws Exception {
            SecretKey secretKey = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String packed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, packed).apply();
        }

        String get(String key) {
            try {
                String packed = getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, null);
                if (packed == null) return null;
                String[] p = packed.split(":", 2);
                if (p.length != 2) return null;
                byte[] iv = Base64.decode(p[0], Base64.NO_WRAP);
                byte[] encrypted = Base64.decode(p[1], Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
                return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        boolean has(String key) { return get(key) != null; }

        void clear() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply();
        }

        private SecretKey getOrCreateKey() throws Exception {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            return kg.generateKey();
        }
    }
}
