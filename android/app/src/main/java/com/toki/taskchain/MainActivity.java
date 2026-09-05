package com.toki.taskchain;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
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

    private WebView webView;
    private String serverUrl = "";
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl = sp.getString(KEY_SERVER, "");

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
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
            public void onPageFinished(WebView view, String url) {
                syncOfficialUrl(); // 联网成功时检查管理后台设置的官方地址
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    resolveViaEntry(false); // 主页加载失败 → 尝试从固定入口自救
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
        if (serverUrl.isEmpty() && !entryUrl().isEmpty()) {
            resolveViaEntry(false); // 无保存地址但有固定入口：直接从入口解析
        } else if (serverUrl.isEmpty()) {
            askServerDialog(); // 首次启动：必须输入服务器地址
        } else {
            webView.loadUrl(serverUrl);
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
                    webView.loadUrl(result);
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
                    webView.loadUrl(official);
                });
            } catch (Exception ignored) {
                // 拉取失败保持现地址，不影响使用
            }
        }).start();
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
                        webView.loadUrl(serverUrl);
                    }
                })
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "切换服务器地址");
        menu.add(0, 2, 0, "清除登录状态");
        menu.add(0, 3, 0, "设置固定入口地址");
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
            webView.loadUrl(serverUrl);
            return true;
        } else if (item.getItemId() == 3) {
            askEntryDialog();
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
                        webView.loadUrl(manualUrl);
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
