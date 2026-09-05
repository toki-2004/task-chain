package com.toki.taskchain;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

/**
 * 协同任务链 WebView 壳。
 * 全部业务界面由服务端网页提供；本壳负责加载地址、文件选择（图片/视频）、Cookie 持久化。
 * 管理后台设置的「APK 官方访问地址」会在每次成功加载页面后自动拉取并跟随切换。
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final String PREFS = "taskchain";
    private static final String KEY_SERVER = "server_url";
    private static final String KEY_ENTRY = "entry_url";
    private static final String KEY_RESCUE_USER = "rescue_user";
    private static final String KEY_RESCUE_TOKEN = "rescue_token";
    private static final String KEY_RESCUE_POP = "rescue_pop_host";

    /**
     * 内置默认入口（编译前可改）：全网可达的固定网址（如花生壳隧道域名、Gitee raw 配置文件）。
     * 留空 = 不启用；局域网场景由 UDP 自动发现覆盖，无需填写。
     */
    private static final String DEFAULT_ENTRY = "";
    /** 救援邮件主题标记（与服务端 RESCUE_SUBJECT 一致，全 ASCII） */
    private static final String RESCUE_SUBJECT = "task-chain address update";
    private static final String UPDATE_API = "https://api.github.com/repos/toki-2004/task-chain/releases/latest";
    private static final String RELEASE_PAGE = "https://github.com/toki-2004/task-chain/releases/latest";

    private WebView webView;
    private String serverUrl = "";
    private ValueCallback<Uri[]> fileCallback;
    private LinearLayout loadingOverlay;
    /** https 尝试失败记录（避免 http↔https 往返死循环） */
    private String lastFailedHttps = "";
    private String lastHttpFallback = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl = sp.getString(KEY_SERVER, "");

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 加载动画遮罩（页面加载期间覆盖 WebView，避免白屏焦虑）
        loadingOverlay = new LinearLayout(this);
        loadingOverlay.setOrientation(LinearLayout.VERTICAL);
        loadingOverlay.setGravity(android.view.Gravity.CENTER);
        loadingOverlay.setBackgroundColor(Color.parseColor("#F4F6F9"));
        ProgressBar pb = new ProgressBar(this);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        loadingOverlay.addView(pb, pbLp);
        TextView loadTip = new TextView(this);
        loadTip.setText("正在加载…");
        loadTip.setPadding(0, 28, 0, 0);
        loadTip.setGravity(android.view.Gravity.CENTER);
        loadTip.setTextColor(Color.parseColor("#8A94A6"));
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        loadingOverlay.addView(loadTip, tvLp);
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(false);
        webView.setBackgroundColor(Color.parseColor("#F4F6F9"));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (scheme.equals("http") || scheme.equals("https")) {
                    return false; // 站内照常加载
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri)); // 外链（如合同条例）交给系统
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                showLoading(true);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                showLoading(false);
                syncOfficialUrl(); // 联网成功时检查管理后台设置的官方地址
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    // https 尝试失败：回落到原 http 地址再试一次
                    if (serverUrl.startsWith("https://") && !lastHttpFallback.isEmpty()
                            && serverUrl.endsWith(lastHttpFallback.substring(7))) {
                        lastFailedHttps = serverUrl;
                        String fb = lastHttpFallback;
                        lastHttpFallback = "";
                        loadServerUrl(fb);
                        return;
                    }
                    // 主页加载失败：局域网发现 → 内置入口 → 固定入口 → 救援邮箱
                    discoverOnLan(false);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                try {
                    startActivityForResult(
                            Intent.createChooser(intent, "选择图片或视频"), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(root);
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        }
        if (serverUrl.isEmpty()) {
            // 全新启动：局域网 UDP 自动发现 → 内置默认入口 → 固定入口 → 救援邮箱 → 手动输入
            showLoading(true);
            Toast.makeText(this, "正在局域网搜索服务器…", Toast.LENGTH_SHORT).show();
            discoverOnLan(true);
        } else if (getIntent() != null && getIntent().getBooleanExtra("taskchain_push", false)) {
            loadServerUrl(serverUrl);
            handlePushIntent(getIntent()); // 冷启动：通知点击直达任务
        } else {
            loadServerUrl(serverUrl);
        }
        startNotifyService();
        checkForUpdate(false); // 启动静默检查新版本
    }

    /** 检查更新：优先问服务器 /apk/info（国内直连，APK 由服务器分发）；
     *  服务器无分发时回退 GitHub Releases API。发现新版本弹窗引导下载。 */
    private void checkForUpdate(final boolean manual) {
        new Thread(() -> {
            // 1) 服务器分发渠道
            if (!serverUrl.isEmpty()) {
                String srvVer = null;
                try {
                    HttpURLConnection conn = (HttpURLConnection)
                            new URL(serverUrl + "/apk/info").openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(6000);
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    br.close();
                    srvVer = new JSONObject(sb.toString()).optString("version", "").trim();
                } catch (Exception ignored) {
                }
                if (srvVer != null && !srvVer.isEmpty()) {
                    String cur;
                    try {
                        cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    } catch (Exception e) {
                        cur = "0.0.0";
                    }
                    final boolean newer = isNewerVersion(srvVer, cur);
                    final String fVer = srvVer;
                    final String fCur = cur;
                    final boolean fManual = manual;
                    runOnUiThread(() -> {
                        if (newer) {
                            new AlertDialog.Builder(this)
                                    .setTitle("发现新版本 " + fVer)
                                    .setMessage("当前版本 " + fCur + "\\n将从服务器下载新 APK")
                                    .setCancelable(true)
                                    .setPositiveButton("下载更新", (d, w) -> {
                                        try {
                                            startActivity(new Intent(Intent.ACTION_VIEW,
                                                    Uri.parse(serverUrl + "/apk")));
                                        } catch (Exception e) {
                                            Toast.makeText(this, "打开失败", Toast.LENGTH_SHORT).show();
                                        }
                                    })
                                    .setNegativeButton("以后再说", null)
                                    .show();
                        } else if (fManual) {
                            Toast.makeText(this, "已是最新版本（" + fCur + "）", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return; // 服务器渠道有效即止
                }
            }
            // 2) 回退：GitHub Releases API
            queryGitHubRelease(manual);
        }).start();
    }

    /** 回退渠道：直接查询 GitHub Releases（手机网络可能无法访问 GitHub）。 */
    private void queryGitHubRelease(final boolean manual) {
        new Thread(() -> {
            String latest = null, notes = null, apkUrl = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(UPDATE_API).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "task-chain-app");
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();
                JSONObject obj = new JSONObject(sb.toString());
                latest = obj.optString("tag_name", "").trim();
                notes = obj.optString("body", "");
                org.json.JSONArray assets = obj.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        String name = a.optString("name", "");
                        if (name.endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", "");
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            final String fLatest = latest;
            final String fNotes = notes == null ? "" : notes;
            final String fApk = apkUrl == null || apkUrl.isEmpty() ? RELEASE_PAGE : apkUrl;
            runOnUiThread(() -> {
                if (fLatest == null || fLatest.isEmpty()) {
                    if (manual) {
                        Toast.makeText(MainActivity.this,
                                "检查更新失败（网络原因，也可稍后在浏览器打开 " + RELEASE_PAGE + "）",
                                Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                String cur;
                try {
                    cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Exception e) {
                    cur = "0.0.0";
                }
                if (!isNewerVersion(fLatest, cur)) {
                    if (manual) {
                        Toast.makeText(this, "已是最新版本（" + cur + "）", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                String brief = fNotes.length() > 260 ? fNotes.substring(0, 260) + "…" : fNotes;
                new AlertDialog.Builder(this)
                        .setTitle("发现新版本 " + fLatest)
                        .setMessage("当前版本 " + cur + "\\n\\n" + brief)
                        .setCancelable(true)
                        .setPositiveButton("前往下载", (d, w) -> {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fApk)));
                            } catch (Exception e) {
                                Toast.makeText(this, "打开失败，请手动访问 " + RELEASE_PAGE, Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("以后再说", null)
                        .show();
            });
        }).start();
    }

    /** 版本号比较：v1.10.0 > v1.9.2，逐段数字比较，非数字后缀忽略。 */
    private static boolean isNewerVersion(String remote, String local) {
        String[] r = remote.replaceFirst("^[vV]", "").trim().split("\\.");
        String[] l = (local == null ? "0.0.0" : local.trim()).split("\\.");
        int n = Math.max(r.length, l.length);
        for (int i = 0; i < n; i++) {
            String rs = i < r.length ? r[i].replaceAll("\\D", "") : "";
            String ls = i < l.length ? l[i].replaceAll("\\D", "") : "";
            int ri = rs.isEmpty() ? 0 : Integer.parseInt(rs);
            int li = ls.isEmpty() ? 0 : Integer.parseInt(ls);
            if (ri != li) {
                return ri > li;
            }
        }
        return false;
    }

    /** 统一的地址加载入口：公网 http 地址自动尝试 https（frp 服务商多启用自动 HTTPS，
     *  HTTP 访问会被 501 拒绝），失败时在 onReceivedError 里回落到原 http 地址。 */
    private void loadServerUrl(String url) {
        url = normalizeServerUrl(url);
        if (url == null || url.isEmpty()) {
            return;
        }
        serverUrl = url;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SERVER, url).apply();
        showLoading(true);
        webView.loadUrl(url);
    }

    /** 归一化地址：公网 http 自动升级 https（升级失败会在 onReceivedError 回落）。 */
    private String normalizeServerUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.startsWith("http://") && !isLanAddress(url)
                && !("https://" + url.substring(7)).equals(lastFailedHttps)) {
            lastHttpFallback = url;
            url = "https://" + url.substring(7);
        }
        return url;
    }

    /** 系统通知权限（API 33+ 需运行时申请）+ 注册后台闹钟检查（约 15 分钟一次，无常驻图标）。 */
    private void startNotifyService() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
        }
        AlarmManager am = getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 10,
                new Intent(this, NotifyReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, 20000,
                15 * 60 * 1000L, pi);
        // 打开 App 立即检查一次
        new Thread(() -> NotifyPoller.poll(this)).start();
    }

    /** 通知点击：直达对应任务详情。 */
    private void handlePushIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("taskchain_push", false)) {
            return;
        }
        int nid = intent.getIntExtra("node", 0);
        if (nid > 0 && !serverUrl.isEmpty()) {
            showLoading(true);
            String base = normalizeServerUrl(serverUrl);
            serverUrl = base;
            webView.loadUrl(base + "/#/node/" + nid);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 切到后台时立即检查一次，之后由系统闹钟约 15 分钟一次
        new Thread(() -> NotifyPoller.poll(this)).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePushIntent(intent);
    }

    private void showLoading(boolean show) {
        if (loadingOverlay != null) {
            runOnUiThread(() -> loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE));
        }
    }

    /** 判断是否局域网/本机地址（这些地址不做 http→https 升级）。 */
    private static boolean isLanAddress(String url) {
        try {
            String host = new URL(url).getHost();
            if (host == null) {
                return false;
            }
            if (host.equals("localhost")) {
                return true;
            }
            String[] p = host.split("\\.");
            if (p.length == 4 && p[0].matches("\\d+") && p[1].matches("\\d+")) {
                int a = Integer.parseInt(p[0]);
                int b = Integer.parseInt(p[1]);
                return a == 10 || a == 127 || (a == 192 && b == 168)
                        || (a == 172 && b >= 16 && b <= 31)
                        || (a == 100 && b >= 64 && b <= 127); // 含 Tailscale CGNAT 段
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 局域网 UDP 自动发现：广播问询，服务器应答 "TASKCHAIN_SERVER|http://ip:port"。 */
    private void discoverOnLan(final boolean onFailAsk) {
        new Thread(() -> {
            String found = null;
            try {
                DatagramSocket s = new DatagramSocket();
                s.setBroadcast(true);
                s.setSoTimeout(2500);
                byte[] probe = "TASKCHAIN_DISCOVER".getBytes("UTF-8");
                java.util.List<InetAddress> targets = new java.util.ArrayList<>();
                try {
                    targets.add(InetAddress.getByName("255.255.255.255"));
                } catch (Exception ignored) {
                }
                String myIp = localIp();
                if (myIp != null) {
                    String[] p = myIp.split("\\.");
                    if (p.length == 4) {
                        try {
                            targets.add(InetAddress.getByName(p[0] + "." + p[1] + "." + p[2] + ".255"));
                        } catch (Exception ignored) {
                        }
                    }
                }
                for (InetAddress t : targets) {
                    try {
                        s.send(new DatagramPacket(probe, probe.length, t, 9875));
                    } catch (Exception ignored) {
                    }
                }
                byte[] buf = new byte[256];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(pkt);
                    String resp = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8");
                    if (resp.startsWith("TASKCHAIN_SERVER|")) {
                        found = resp.substring("TASKCHAIN_SERVER|".length()).trim();
                    }
                } catch (Exception ignored) {
                }
                s.close();
            } catch (Exception ignored) {
            }
            final String result = found;
            runOnUiThread(() -> {
                if (result != null && !result.isEmpty() && !result.equals(serverUrl)
                        && result.startsWith("http")) {
                    serverUrl = result;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_SERVER, result).apply();
                    Toast.makeText(MainActivity.this, "已连接到服务器：" + result, Toast.LENGTH_SHORT).show();
                    loadServerUrl(result);
                    return;
                }
                // 发现失败：内置默认入口 → 固定入口 → 救援邮箱 → 手动
                if (!DEFAULT_ENTRY.isEmpty() && !DEFAULT_ENTRY.equals(serverUrl)) {
                    serverUrl = DEFAULT_ENTRY;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_SERVER, DEFAULT_ENTRY).apply();
                    loadServerUrl(DEFAULT_ENTRY);
                } else if (!entryUrl().isEmpty() && !entryUrl().equals(serverUrl)) {
                    resolveViaEntry(false);
                } else {
                    rescueMailFetch(onFailAsk);
                }
            });
        }).start();
    }

    private String localIp() {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("10.255.255.255", 1), 100);
            String ip = s.getLocalAddress().getHostAddress();
            s.close();
            return ip;
        } catch (Exception e) {
            return null;
        }
    }

    private String entryUrl() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ENTRY, "");
    }

    /** 从固定入口（Gitee raw JSON / 纯文本地址）解析官方访问地址。
     *  失联自救：保存的地址连不上时，靠它拿到新地址。静默失败。 */
    private void resolveViaEntry(final boolean onFailAskServer) {
        final String entry = entryUrl();
        if (entry.isEmpty()) {
            if (onFailAskServer && serverUrl.isEmpty()) {
                runOnUiThread(this::askServerDialog);
            }
            return;
        }
        new Thread(() -> {
            String official = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(entry).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setInstanceFollowRedirects(true);
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();
                official = extractServerUrl(sb.toString());
            } catch (Exception ignored) {
            }
            final String result = official;
            runOnUiThread(() -> {
                if (result != null && !result.isEmpty() && !result.equals(serverUrl)
                        && (result.startsWith("http://") || result.startsWith("https://"))) {
                    serverUrl = result;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_SERVER, result).apply();
                    Toast.makeText(MainActivity.this, "已通过固定入口获取服务器地址", Toast.LENGTH_SHORT).show();
                    loadServerUrl(result);
                } else if (onFailAskServer && serverUrl.isEmpty()) {
                    askServerDialog();
                }
            });
        }).start();
    }

    /** 严格解析：优先 JSON 的 app_server_url 字段；纯文本时整段必须是一个 URL，避免误取网页里的杂链。 */
    private static String extractServerUrl(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        try {
            String u = new JSONObject(trimmed).optString("app_server_url", "").trim();
            if (!u.isEmpty()) {
                return u;
            }
        } catch (Exception ignored) {
        }
        if (trimmed.matches("https?://[^\\s\"']+")) {
            return trimmed;
        }
        return null;
    }

    /** 从当前服务器拉取官方访问地址；不同则自动切换（管理后台可改，本方法静默失败）。 */
    private void syncOfficialUrl() {
        final String current = serverUrl;
        if (current.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL(current + "/api/appconfig").openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();
                JSONObject obj = new JSONObject(sb.toString());
                // 缓存服务器下发的救援邮箱凭据（仅已登录会话能拿到）
                String ru = obj.optString("rescue_user", "").trim();
                String rt = obj.optString("rescue_token", "").trim();
                if (!ru.isEmpty() && !rt.isEmpty()) {
                    SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                    sp.edit().putString(KEY_RESCUE_USER, ru)
                            .putString(KEY_RESCUE_TOKEN, rt)
                            .putString(KEY_RESCUE_POP, obj.optString("rescue_pop_host", "pop.qq.com"))
                            .apply();
                }
                final String official = obj.optString("app_server_url", "").trim();
                if (official.isEmpty() || official.equals(current)) {
                    return;
                }
                if (!official.startsWith("http://") && !official.startsWith("https://")) {
                    return;
                }
                runOnUiThread(() -> {
                    serverUrl = official;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_SERVER, official).apply();
                    Toast.makeText(MainActivity.this, "服务器地址已更新", Toast.LENGTH_SHORT).show();
                    loadServerUrl(official);
                });
            } catch (Exception ignored) {
                // 拉取失败保持现地址，不影响使用
            }
        }).start();
    }

    /** 失联自救最后一环：登录救援邮箱（POP3）读最新邮件里的服务器地址。静默失败。 */
    private void rescueMailFetch(final boolean onFailAsk) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String ru = p.getString(KEY_RESCUE_USER, "");
        final String rt = p.getString(KEY_RESCUE_TOKEN, "");
        final String rh = p.getString(KEY_RESCUE_POP, "pop.qq.com");
        if (ru.isEmpty() || rt.isEmpty()) {
            if (onFailAsk && serverUrl.isEmpty()) {
                runOnUiThread(this::askServerDialog);
            }
            return;
        }
        runOnUiThread(() -> Toast.makeText(this, "正在通过救援邮箱获取地址…", Toast.LENGTH_SHORT).show());
        new Thread(() -> {
            final String url = pop3LatestUrl(ru, rt, rh);
            runOnUiThread(() -> {
                if (url != null && !url.isEmpty() && !url.equals(serverUrl)
                        && url.startsWith("http")) {
                    serverUrl = url;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_SERVER, url).apply();
                    Toast.makeText(MainActivity.this, "已通过救援邮箱恢复连接", Toast.LENGTH_SHORT).show();
                    loadServerUrl(url);
                } else if (onFailAsk && serverUrl.isEmpty()) {
                    askServerDialog();
                }
            });
        }).start();
    }

    /** 手写 POP3 客户端（零依赖）：登录救援邮箱，从最新往回找主题标记的救援邮件，
     *  提取正文中的第一个 URL。最多扫描最近 10 封。 */
    private static String pop3LatestUrl(String user, String token, String popHost) {
        String host = popHost == null || popHost.isEmpty() ? "pop.qq.com" : popHost;
        int port = 995;
        if (popHost != null && popHost.contains(":")) {
            host = popHost.substring(0, popHost.indexOf(':'));
            port = Integer.parseInt(popHost.substring(popHost.indexOf(':') + 1));
        }
        javax.net.ssl.SSLSocket sock = null;
        try {
            sock = (javax.net.ssl.SSLSocket) javax.net.ssl.SSLSocketFactory
                    .getDefault().createSocket(host, port);
            sock.setSoTimeout(15000);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), "UTF-8"));
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(sock.getOutputStream(), "UTF-8"), true);
            if (!r.readLine().startsWith("+OK")) {
                return null;
            }
            w.println("A1 USER " + user);
            if (!popWaitOk(r, "A1")) {
                return null;
            }
            w.println("A2 PASS " + token);
            if (!popWaitOk(r, "A2")) {
                return null;
            }
            w.println("A3 STAT");
            String stat = popWaitLine(r, "A3");
            if (stat == null || !stat.startsWith("+OK")) {
                return null;
            }
            int count = Integer.parseInt(stat.split("\\s+")[1]);
            String found = null;
            for (int i = count; i >= 1 && i > count - 10; i--) {
                // 只取头部，按主题标记定位救援邮件（避免误读邮箱里其他含链接的邮件）
                w.println("A4" + i + " TOP " + i + " 0");
                if (!popWaitOk(r, "A4" + i)) {
                    continue;
                }
                StringBuilder head = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null && !line.equals(".")) {
                    head.append(line).append('\n');
                }
                if (!head.toString().contains(RESCUE_SUBJECT)) {
                    continue;
                }
                w.println("A5" + i + " RETR " + i);
                if (!popWaitOk(r, "A5" + i)) {
                    continue;
                }
                StringBuilder body = new StringBuilder();
                boolean inBody = false;
                while ((line = r.readLine()) != null && !line.equals(".")) {
                    if (line.isEmpty()) {
                        inBody = true;
                    }
                    if (inBody) {
                        body.append(line).append('\n');
                    }
                }
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("https?://[^\\s<>\"']+").matcher(body);
                if (m.find()) {
                    found = m.group();
                }
                break; // 找到最新一封救援邮件即止（无论是否含地址）
            }
            w.println("A9 QUIT");
            sock.close();
            return found;
        } catch (Exception e) {
            try {
                if (sock != null) {
                    sock.close();
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private static boolean popWaitOk(BufferedReader r, String tag) throws Exception {
        String s = popWaitLine(r, tag);
        return s != null && s.startsWith("+OK");
    }

    private static String popWaitLine(BufferedReader r, String tag) throws Exception {
        String s;
        while ((s = r.readLine()) != null) {
            if (s.startsWith(tag)) {
                return s;
            }
        }
        return null;
    }

    private void askServerDialog() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        final EditText input = new EditText(this);
        input.setHint("http://电脑局域网IP:8000");
        input.setSingleLine(true);
        if (!serverUrl.isEmpty()) {
            input.setText(serverUrl);
        }
        wrapper.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("设置服务器地址")
                .setMessage("请输入运行「协同任务链」服务端的电脑地址（手机需与电脑连同一 WiFi）")
                .setView(wrapper)
                .setCancelable(false)
                .setPositiveButton("保存并连接", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            url = "http://" + url;
                        }
                        while (url.endsWith("/")) {
                            url = url.substring(0, url.length() - 1);
                        }
                        serverUrl = url;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString(KEY_SERVER, url).apply();
                        loadServerUrl(serverUrl);
                    }
                })
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "切换服务器地址");
        menu.add(0, 2, 0, "清除登录状态");
        menu.add(0, 3, 0, "设置固定入口地址");
        menu.add(0, 4, 0, "检查更新");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            askServerDialog();
            return true;
        } else if (item.getItemId() == 2) {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            loadServerUrl(serverUrl);
            return true;
        } else if (item.getItemId() == 3) {
            askEntryDialog();
            return true;
        } else if (item.getItemId() == 4) {
            checkForUpdate(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 设置固定入口地址（如 Gitee raw 配置文件 URL），用于失联自救。 */
    private void askEntryDialog() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        final EditText input = new EditText(this);
        input.setHint("https://gitee.com/用户/仓库/raw/master/app/config.json");
        input.setSingleLine(true);
        final EditText manual = new EditText(this);
        manual.setHint("（可选）手动填一次当前服务器地址");
        manual.setSingleLine(true);
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ENTRY, "");
        if (!saved.isEmpty()) {
            input.setText(saved);
        }
        wrapper.addView(input);
        wrapper.addView(manual);

        new AlertDialog.Builder(this)
                .setTitle("设置固定入口地址")
                .setMessage("入口文件应返回 {\"app_server_url\":\"当前地址\"} 或纯地址文本。\n"
                        + "保存的地址失联时，App 会自动从入口拿新地址。\n"
                        + "下方手动地址仅本次立即生效，不影响入口。")
                .setView(wrapper)
                .setCancelable(true)
                .setPositiveButton("保存", (d, w) -> {
                    String entry = input.getText().toString().trim();
                    if (!entry.isEmpty()) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString(KEY_ENTRY, entry).apply();
                    }
                    String manualUrl = manual.getText().toString().trim();
                    if (!manualUrl.isEmpty()) {
                        if (!manualUrl.startsWith("http://") && !manualUrl.startsWith("https://")) {
                            manualUrl = "http://" + manualUrl;
                        }
                        while (manualUrl.endsWith("/")) {
                            manualUrl = manualUrl.substring(0, manualUrl.length() - 1);
                        }
                        serverUrl = manualUrl;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString(KEY_SERVER, manualUrl).apply();
                        loadServerUrl(manualUrl);
                    } else if (!entry.isEmpty() && !entry.equals(saved)) {
                        resolveViaEntry(false);
                    }
                })
                .show();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }
}
