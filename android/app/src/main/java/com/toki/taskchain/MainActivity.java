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
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * 协同任务链 WebView 壳。
 * 全部业务界面由服务端网页提供；本壳负责加载地址、文件选择（图片/视频）、Cookie 持久化。
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final String PREFS = "taskchain";
    private static final String KEY_SERVER = "server_url";

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
            askServerDialog(); // 首次启动：必须输入服务器地址
        } else {
            webView.loadUrl(serverUrl);
        }
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
        }
        return super.onOptionsItemSelected(item);
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
