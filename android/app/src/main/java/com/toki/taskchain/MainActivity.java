package com.toki.taskchain;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.SslErrorHandler;
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
    private static final String KEY_OFFICIAL = "official_url";
    private static final String KEY_SESSION = "sid_token";
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

    private WebView webView;
    private String serverUrl = "";
    private ValueCallback<Uri[]> fileCallback;
    private LinearLayout loadingOverlay;
    /** https 尝试失败记录（避免 http↔https 往返死循环） */
    private String lastFailedHttps = "";
    private String lastHttpFallback = "";
    /** 本次主帧加载是否失败：区分「失败后的页面回调」与「局域网真实渲染成功」 */
    private volatile boolean mainFrameLoadFailed = false;
    /** 局域网地址连续原地重试次数：超过上限才允许切公网自救，避免局域网抖动反复横跳 */
    private int lanRetryCount = 0;
    /** 本地存档外网地址的连续重试次数：偶发失败先原地重试一次，仍失败才走救援邮箱 */
    private int backupRetry = 0;
    /** 页面加载 5 秒仍未见分晓（未渲染完成也未报错）时，主动切换公网/备用地址 */
    private static final long LOAD_TIMEOUT_MS = 5000;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable loadTimeoutTask = new Runnable() {
        @Override
        public void run() {
            onLoadTimeout();
        }
    };
    /** 本次加载是否已见分晓（完成/报错），防止超时与回调重复自救 */
    private volatile boolean loadSettled = true;
    /** 网络变化后的局域网探测（防抖 + 去重） */
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean lanProbeRunning = false;
    private final Runnable lanProbeTask = new Runnable() {
        @Override
        public void run() {
            probeLanAndSwitch(true); // 网络变化触发的探测：弹提示让用户知道正在尝试
        }
    };

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
                mainFrameLoadFailed = false;
                loadSettled = false;
                mainHandler.removeCallbacks(loadTimeoutTask);
                mainHandler.postDelayed(loadTimeoutTask, LOAD_TIMEOUT_MS);
                showLoading(true);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loadSettled = true;
                mainHandler.removeCallbacks(loadTimeoutTask);
                showLoading(false);
                if (!mainFrameLoadFailed) {
                    backupRetry = 0; // 加载成功：清零本地外网地址重试计数
                }
                // 无主帧错误的加载结束 = 当前地址真实可用，清零局域网重试计数
                if (isLanAddress(serverUrl) && !mainFrameLoadFailed) {
                    lanRetryCount = 0;
                }
                if (!mainFrameLoadFailed && !serverUrl.isEmpty()) {
                    ensureSession(serverUrl); // 捕获/补种登录会话
                    archiveOfficialUrl(); // 每次连接成功：把官方外网地址存档到本地备用
                }
                postLoadChecks(url); // 局域网直连优先，其次跟随官方地址
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                           SslError error) {
                // 信任用户自行配置的服务器/入口域名（frp 服务商如 SakuraFrp 的
                // 自动 TLS 为自签名证书，SAN 与域名匹配）。放行以正常使用；
                // 若需严格校验请移除本方法。已按主界面输入的地址为信任边界。
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    mainFrameLoadFailed = true;
                    loadSettled = true;
                    mainHandler.removeCallbacks(loadTimeoutTask);
                    // https 尝试失败：回落到原 http 地址再试一次
                    if (serverUrl.startsWith("https://") && !lastHttpFallback.isEmpty()
                            && serverUrl.endsWith(lastHttpFallback.substring(7))) {
                        lastFailedHttps = serverUrl;
                        String fb = lastHttpFallback;
                        lastHttpFallback = "";
                        loadServerUrl(fb);
                        return;
                    }
                    // 当前地址加载失败：本地有存档外网地址且不同于当前 → 立即自动切过去重试
                    // （局域网连不上就用外网，不再原地重试/受 15 秒冷却限制）
                    String backup = backupOfficial();
                    if (usableBackup(backup)) {
                        switchToServerNow(backup);
                        return;
                    }
                    if (!backup.isEmpty() && backup.equals(serverUrl)) {
                        backupRetryOrRescue(); // 正用存档外网地址：先重试一次，仍失败再救援邮箱
                        return;
                    }
                    // 无存档可用：局域网绝对优先（重试/探测），确认失联才走自救链
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
            discoverOnLan(true);
        } else if (getIntent() != null && getIntent().getBooleanExtra("taskchain_push", false)) {
            loadServerUrl(serverUrl);
            handlePushIntent(getIntent()); // 冷启动：通知点击直达任务
        } else {
            loadServerUrl(serverUrl);
        }
        registerNetworkWatcher(); // 每次网络变化自动探测局域网，进入局域网范围即优先切回
        startNotifyService();
        checkForUpdate(false); // 启动静默检查新版本
    }

    /** 检查更新：只走服务器分发（/apk/info 版本查询 + /apk 下载），
     *  连不上服务器或服务器未放置 APK 时不回退任何第三方源。 */
    private void checkForUpdate(final boolean manual) {
        new Thread(() -> {
            if (serverUrl.isEmpty()) {
                if (manual) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "请先在菜单「切换服务器地址」中设置服务器", Toast.LENGTH_LONG).show());
                }
                return;
            }
            String srvVer = null;
            try {
                HttpURLConnection conn = TrustedHttp.open(this, serverUrl + "/apk/info");
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
            if (srvVer == null || srvVer.isEmpty()) {
                if (manual) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "无法连接服务器或服务器未放置 APK，检查更新失败", Toast.LENGTH_LONG).show());
                }
                return;
            }
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
        ensureSession(url); // 切换地址后自动补种登录会话，避免每次重新登录
        showLoading(true);
        webView.loadUrl(url);
    }

    /** 归一化地址：局域网一律用 http（含历史误存的 https 局域网地址降级回来）；
     *  公网 http 自动升级 https（升级失败会在 onReceivedError 回落）。 */
    private String normalizeServerUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (isLanAddress(url)) {
            // 局域网服务器是纯 http；此前误升级/误存的 https 局域网地址在此纠正
            if (url.startsWith("https://")) {
                url = "http://" + url.substring(8);
            }
        } else if (url.startsWith("http://")
                && !("https://" + url.substring(7)).equals(lastFailedHttps)) {
            lastHttpFallback = url;
            url = "https://" + url.substring(7);
        }
        return url;
    }

    /** 登录态跨地址保持：WebView 的会话 cookie 按域名存，局域网/外网切换后新域名
     *  没有 cookie 就得重新登录。这里把 sid 会话 token 单独存一份（服务端会话与域名无关），
     *  每次加载前检查：目标地址有 sid 则顺带刷新存档；没有且本地有存档则重新种入。 */
    private String cookieSid(String url) {
        try {
            String c = CookieManager.getInstance().getCookie(url);
            if (c == null) {
                return "";
            }
            for (String part : c.split(";")) {
                String p = part.trim();
                if (p.startsWith("sid=")) {
                    return p.substring(4);
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void ensureSession(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            String current = cookieSid(url);
            String saved = sp.getString(KEY_SESSION, "");
            if (!current.isEmpty()) {
                if (!current.equals(saved)) {
                    sp.edit().putString(KEY_SESSION, current).apply();
                }
                return;
            }
            if (!saved.isEmpty()) {
                CookieManager.getInstance().setCookie(url,
                        "sid=" + saved + "; path=/; max-age=" + (30L * 24 * 3600));
                CookieManager.getInstance().flush();
            }
        } catch (Exception ignored) {
        }
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
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(loadTimeoutTask);
        mainHandler.removeCallbacks(lanProbeTask);
        if (networkCallback != null) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
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

    /** 自动地址切换冷却：15 秒内只允许一次自动切换，打断任何潜在震荡环路。
     *  手动「设置服务器地址」不受限。 */
    private boolean autoSwitchAllowed() {
        long t = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("last_switch", 0);
        return System.currentTimeMillis() - t > 15000;
    }

    private void markSwitch() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong("last_switch", System.currentTimeMillis()).apply();
    }

    /** 当前地址加载超过 5 秒仍未成功（既没渲染完成也没报错）：不等 WebView 错误回调，
     *  直接用本地存档的外网地址切换（连接成功时已自动存档，无需现查）；
     *  没有存档才向当前服务器要 /api/appconfig → 固定入口 → 救援邮箱 → 常规失联链。 */
    private void onLoadTimeout() {
        if (loadSettled) {
            return;
        }
        loadSettled = true;
        mainHandler.removeCallbacks(loadTimeoutTask);
        mainFrameLoadFailed = true;
        String backup = backupOfficial();
        if (!backup.isEmpty() && backup.equals(serverUrl)) {
            backupRetryOrRescue(); // 本地存档外网地址连不上：先原地重试一次，仍失败再救援邮箱
            return;
        }
        if (usableBackup(backup)) {
            switchToServerNow(backup);
            return;
        }
        fetchOfficialAndSwitch(); // 本地无存档时的兜底：主动向当前服务器要官方外网地址
    }

    /** 本地存档的外网（官方）地址：每次连接成功后由 archiveOfficialUrl 刷新。 */
    private String backupOfficial() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_OFFICIAL, "");
    }

    private void saveBackupOfficial(String url) {
        if (url != null && !url.isEmpty()
                && (url.startsWith("http://") || url.startsWith("https://"))) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_OFFICIAL, url).apply();
        }
    }

    private boolean usableBackup(String url) {
        return url != null && !url.isEmpty() && !url.equals(serverUrl)
                && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /** 当前用的正是本地存档的外网地址且加载失败：偶发失败先原地重试一次
     *  （可能只是网络抖动），连续失败才走救援邮箱。 */
    private void backupRetryOrRescue() {
        if (backupRetry < 1) {
            backupRetry++;
            Toast.makeText(this, "正在用本地外网地址重试…", Toast.LENGTH_SHORT).show();
            loadServerUrl(serverUrl);
            return;
        }
        rescueNow();
    }

    /** 当前地址（本地存档的外网地址）连不上：直接走救援邮箱取最新服务器地址；
     *  未配置救援邮箱时退回常规失联链。 */
    private void rescueNow() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!p.getString(KEY_RESCUE_USER, "").isEmpty()
                && !p.getString(KEY_RESCUE_TOKEN, "").isEmpty()) {
            rescueMailFetch(false); // 内部先提示「正在获取服务器地址…」
        } else {
            discoverOnLan(false);
        }
    }

    /** 每次连接成功后的例行动作：向当前服务器拉一份官方（外网）地址存档到本地，
     *  只存档不切换（局域网不被拉回公网；公网发现局域网仍即时优先，见 postLoadChecks）。 */
    private void archiveOfficialUrl() {
        final String current = serverUrl;
        if (current.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                HttpURLConnection conn = TrustedHttp.open(this, current + "/api/appconfig");
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
                saveBackupOfficial(obj.optString("app_server_url", "").trim());
                // 顺带缓存救援邮箱凭据（仅已登录会话能拿到）
                String ru = obj.optString("rescue_user", "").trim();
                String rt = obj.optString("rescue_token", "").trim();
                if (!ru.isEmpty() && !rt.isEmpty()) {
                    SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                    sp.edit().putString(KEY_RESCUE_USER, ru)
                            .putString(KEY_RESCUE_TOKEN, rt)
                            .putString(KEY_RESCUE_POP, obj.optString("rescue_pop_host", "pop.qq.com"))
                            .apply();
                }
            } catch (Exception ignored) {
                // 存档失败不影响使用，下次连接成功再试
            }
        }).start();
    }

    /** 5 秒看门狗触发的切换：当前地址已确认不响应，不受 15 秒冷却限制，避免卡死。
     *  注意仅在页面加载停滞（超时）时调用，正常页面的自动切换仍走冷却。 */
    private void switchToServerNow(String url) {
        markSwitch();
        serverUrl = url;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SERVER, url).apply();
        Toast.makeText(this, "当前地址无法连接，切换服务器地址：" + url, Toast.LENGTH_SHORT).show();
        loadServerUrl(url);
    }

    /** 看门狗兜底：请求当前服务器 /api/appconfig 拿官方（外网）地址并立即切换；
     *  当前服务器也要不到时，再走固定入口 → 救援邮箱 → 常规失联链。 */
    private void fetchOfficialAndSwitch() {
        Toast.makeText(this, "正在获取官方外网地址…", Toast.LENGTH_SHORT).show();
        final String current = serverUrl;
        if (current.isEmpty()) {
            discoverOnLan(false);
            return;
        }
        new Thread(() -> {
            String official = null;
            try {
                HttpURLConnection conn = TrustedHttp.open(this, current + "/api/appconfig");
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
                official = new JSONObject(sb.toString()).optString("app_server_url", "").trim();
            } catch (Exception ignored) {
            }
            final String target = official;
            runOnUiThread(() -> {
                if (target != null && !target.isEmpty() && !target.equals(serverUrl)
                        && (target.startsWith("http://") || target.startsWith("https://"))) {
                    saveBackupOfficial(target); // 也补一份本地存档
                    switchToServerNow(target); // 拿到官方外网地址，立即执行
                    return;
                }
                if (!entryUrl().isEmpty() && !entryUrl().equals(serverUrl)) {
                    resolveViaEntry(false); // 固定入口给出的官方地址（通常为公网）
                    return;
                }
                SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (p.getString(KEY_RESCUE_USER, "").isEmpty()
                        || p.getString(KEY_RESCUE_TOKEN, "").isEmpty()) {
                    discoverOnLan(false); // 常规失联链兜底
                } else {
                    rescueMailFetch(false); // 救援邮箱直接取最新公网地址
                }
            });
        }).start();
    }

    /** 判断是否局域网/本机地址（这些地址不做 http→https 升级）。
     *  除标准私网/CGNAT 段外，与本机任一网卡同子网即视为局域网——
     *  覆盖路由器自定义网段（如 172.42.50.x，不在 RFC1918 172.16-172.31 内）。 */
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
            if (p.length == 4 && p[0].matches("\\d+") && p[1].matches("\\d+")
                    && p[2].matches("\\d+") && p[3].matches("\\d+")) {
                int a = Integer.parseInt(p[0]);
                int b = Integer.parseInt(p[1]);
                if (a == 10 || a == 127 || (a == 192 && b == 168)
                        || (a == 172 && b >= 16 && b <= 31)
                        || (a == 100 && b >= 64 && b <= 127)) {
                    return true; // 标准私网 + localhost + Tailscale CGNAT 段
                }
                // 非标准网段：与本机任一网卡同子网也算局域网
                return inLocalSubnet(ipToInt(p));
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static int ipToInt(String[] p) {
        return (Integer.parseInt(p[0]) << 24) | (Integer.parseInt(p[1]) << 16)
                | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
    }

    private static boolean inLocalSubnet(int host) {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                java.net.NetworkInterface ni = nifs.nextElement();
                if (!ni.isUp()) {
                    continue;
                }
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress ip = ia.getAddress();
                    if (!(ip instanceof java.net.Inet4Address)) {
                        continue;
                    }
                    int prefix = ia.getNetworkPrefixLength();
                    int mask = prefix >= 32 ? -1 : (prefix <= 0 ? 0 : (0xFFFFFFFF << (32 - prefix)));
                    byte[] raw = ip.getAddress();
                    int local = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                            | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
                    if ((host & mask) == (local & mask)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 同步 UDP 探测：返回发现的局域网服务器地址，未发现返回 null（须在后台线程调用）。 */
    private String udpDiscover() {
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
            String found = null;
            try {
                s.receive(pkt);
                String resp = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8");
                if (resp.startsWith("TASKCHAIN_SERVER|")) {
                    found = resp.substring("TASKCHAIN_SERVER|".length()).trim();
                }
            } catch (Exception ignored) {
            }
            s.close();
            return found;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 局域网 UDP 自动发现：广播问询，服务器应答 "TASKCHAIN_SERVER|http://ip:port"。 */
    private void discoverOnLan(final boolean onFailAsk) {
        Toast.makeText(this, "正在局域网搜索服务器…", Toast.LENGTH_SHORT).show();
        final boolean wasLan = isLanAddress(serverUrl); // 调用方均为主线程：此刻若在局域网，说明它刚加载失败
        new Thread(() -> {
            String result = udpDiscover();
            if (result == null && wasLan) {
                // 局域网地址加载失败：单次 UDP 探测可能抖动，间隔重探一次再放弃局域网
                try {
                    Thread.sleep(2500);
                } catch (Exception ignored) {
                }
                result = udpDiscover();
            }
            final String lanResult = result;
            runOnUiThread(() -> {
                if (lanResult != null && !lanResult.isEmpty() && !lanResult.equals(serverUrl)
                        && lanResult.startsWith("http")) {
                    if (!autoSwitchAllowed()) {
                        return;
                    }
                    markSwitch();
                    serverUrl = lanResult;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_SERVER, lanResult).apply();
                    Toast.makeText(MainActivity.this, "已连接到服务器：" + lanResult,
                            Toast.LENGTH_SHORT).show();
                    loadServerUrl(lanResult);
                    return;
                }
                // 探测到的就是当前局域网地址：网页加载失败但服务器仍在 → 原地重试，不切公网
                if (lanResult != null && isLanAddress(serverUrl)
                        && lanResult.equals(serverUrl) && lanRetryCount < 2) {
                    lanRetryCount++;
                    loadServerUrl(serverUrl);
                    return;
                }
                // 局域网确认失联：先试本地存档的官方（外网）地址（无需现查），
                // 再走内置入口 → 固定入口 → 救援邮箱 → 手动
                String backup = backupOfficial();
                if (usableBackup(backup) && autoSwitchAllowed()) {
                    markSwitch();
                    serverUrl = backup;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_SERVER, backup).apply();
                    Toast.makeText(MainActivity.this, "已切换到新地址：" + backup, Toast.LENGTH_SHORT).show();
                    loadServerUrl(backup);
                    return;
                }
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
                HttpURLConnection conn = TrustedHttp.open(this, entry);
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
                    if (!autoSwitchAllowed()) {
                        return;
                    }
                    markSwitch();
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

    /** 页面加载完成后的地址策略（方向不对称）：
     *  当前走公网 → 静默探测局域网，发现直连地址立即切回（局域网更快、不占隧道流量）；
     *  当前走局域网 → 保持（官方地址已由 onPageFinished 存档备用，不被拉回公网）。
     *  网络变化也会主动探测（见 registerNetworkWatcher），进入局域网范围即切回。 */
    private void postLoadChecks(String url) {
        if (isLanAddress(url)) {
            return; // 局域网直连优先：不被拉回公网官方地址
        }
        probeLanAndSwitch(false); // 正常页面加载后的例行探测保持静默
    }

    /** 执行一次局域网探测：发现可用局域网地址且不同于当前 → 立即切回；没发现 → 保持现状。
     *  供页面加载完成后的公网站点（静默）与网络变化（hint=true 弹提示）复用。 */
    private void probeLanAndSwitch(final boolean hint) {
        if (lanProbeRunning) {
            return;
        }
        if (hint) {
            Toast.makeText(this, "正在局域网搜索服务器…", Toast.LENGTH_SHORT).show();
        }
        lanProbeRunning = true;
        new Thread(() -> {
            try {
                final String lan = udpDiscover();
                runOnUiThread(() -> {
                    if (lan != null && !lan.isEmpty() && !lan.equals(serverUrl)
                            && lan.startsWith("http")) {
                        // 家里 WiFi 可直连：切回局域网（更快、不占隧道流量）
                        if (!autoSwitchAllowed()) {
                            return;
                        }
                        markSwitch();
                        serverUrl = lan;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString(KEY_SERVER, lan).apply();
                        Toast.makeText(MainActivity.this, "已切换到局域网直连：" + lan,
                                Toast.LENGTH_SHORT).show();
                        loadServerUrl(lan);
                    }
                });
            } finally {
                lanProbeRunning = false;
            }
        }).start();
    }

    /** 监听网络变化：每次进入/切换网络（WiFi、蜂窝等）自动探测一次局域网。
     *  发现局域网服务器 → 立即切回（进入局域网范围即优先局域网）；没有 → 保持当前地址。 */
    private void registerNetworkWatcher() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleLanProbe();
                }

                @Override
                public void onLost(Network network) {
                    scheduleLanProbe();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    scheduleLanProbe();
                }
            };
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(req, networkCallback);
        } catch (Exception ignored) {
        }
    }

    private void scheduleLanProbe() {
        // 网络事件可能连发（可用/能力变化等），统一防抖后只探测一次
        mainHandler.removeCallbacks(lanProbeTask);
        mainHandler.postDelayed(lanProbeTask, 1200);
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
        runOnUiThread(() -> Toast.makeText(this, "正在获取服务器地址…", Toast.LENGTH_SHORT).show());
        new Thread(() -> {
            final String url = pop3LatestUrl(ru, rt, rh);
            runOnUiThread(() -> {
                if (url != null && !url.isEmpty() && !url.equals(serverUrl)
                        && url.startsWith("http")) {
                    if (!autoSwitchAllowed()) {
                        return;
                    }
                    markSwitch();
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
        String backup = backupOfficial();
        if (!backup.isEmpty() && !backup.equals(serverUrl)) {
            android.widget.Button useBackup = new android.widget.Button(this);
            useBackup.setAllCaps(false);
            useBackup.setText("使用备用外网地址：" + backup);
            useBackup.setOnClickListener(v -> input.setText(backup));
            wrapper.addView(useBackup);
        }

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
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(KEY_SESSION).apply(); // 清除登录状态也要清掉本地会话存档
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
