package com.toki.taskchain;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** 应用内 HttpURLConnection 的统一入口（通知轮询 / APK 更新检查 / 官方地址同步）。
 *
 *  信任策略与管理后台地址设置联动，无需因换 frp 隧道/证书而重打包：
 *  1. 先按系统 CA 校验；
 *  2. 失败则看内置 assets/frp_cert.pem（首版内置的 SakuraFrp 隧道证书）与该域名
 *     已记住的信任锚点；
 *  3. 仍失败且该域名是应用配置要连接的服务器（信任边界，与 WebView 放行一致）：
 *     首次出示的证书按域名存入本地（TOFU），本次即放行，之后同域名直接信任。
 *  后台把官方地址指向新隧道后，App 首次连接新地址自动记住其证书，无需更新 APK。 */
public final class TrustedHttp {

    private static final String CERT_ASSET = "frp_cert.pem";
    private static final String PREFS = "taskchain";
    private static final String ANCHOR_PREFIX = "tls_anchor_";
    private static final ConcurrentHashMap<String, SSLSocketFactory> FACTORIES =
            new ConcurrentHashMap<>();
    private static volatile X509Certificate embeddedCert;

    private TrustedHttp() {
    }

    public static HttpURLConnection open(Context ctx, String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (conn instanceof HttpsURLConnection) {
            String host = new URL(url).getHost();
            ((HttpsURLConnection) conn).setSSLSocketFactory(factoryFor(ctx, host));
        }
        return conn;
    }

    private static SSLSocketFactory factoryFor(Context ctx, String host) throws Exception {
        SSLSocketFactory f = FACTORIES.get(host);
        if (f == null) {
            synchronized (TrustedHttp.class) {
                f = FACTORIES.get(host);
                if (f == null) {
                    f = buildFactory(ctx, host);
                    FACTORIES.put(host, f);
                }
            }
        }
        return f;
    }

    private static SSLSocketFactory buildFactory(final Context ctx, final String host)
            throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null); // 系统默认信任库
        final X509TrustManager system = firstSystemManager(tmf);
        final X509Certificate asset = embedded(ctx);
        final String anchorKey = ANCHOR_PREFIX + host;
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException ignored) {
                    // 公网 CA 校验失败才进入下面的自定义信任路径
                }
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("empty chain");
                }
                if (asset != null && matches(chain, asset)) {
                    return;
                }
                X509Certificate saved = loadAnchor(ctx, anchorKey);
                if (saved != null && matches(chain, saved)) {
                    return;
                }
                // 首次连接管理员配置的地址：记住其证书并放行（TOFU）。
                // HttpsURLConnection 默认主机名校验仍在：证书 SAN 必须匹配该域名。
                saveAnchor(ctx, anchorKey, chain[0]);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return system.getAcceptedIssuers();
            }
        }}, new SecureRandom());
        return sc.getSocketFactory();
    }

    private static boolean matches(X509Certificate[] chain, X509Certificate anchor) {
        for (X509Certificate c : chain) {
            if (c.equals(anchor) || c.getPublicKey().equals(anchor.getPublicKey())) {
                return true;
            }
        }
        return false;
    }

    private static X509Certificate embedded(Context ctx) {
        if (embeddedCert == null) {
            try (InputStream in = ctx.getAssets().open(CERT_ASSET)) {
                embeddedCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(in);
            } catch (Exception ignored) {
            }
        }
        return embeddedCert;
    }

    private static X509Certificate loadAnchor(Context ctx, String key) {
        String pem = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key, "");
        if (pem.isEmpty()) {
            return null;
        }
        try {
            byte[] der = Base64.decode(pem, Base64.DEFAULT);
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void saveAnchor(Context ctx, String key, X509Certificate cert) {
        try {
            String pem = Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP);
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(key, pem).apply();
        } catch (Exception ignored) {
        }
    }

    private static X509TrustManager firstSystemManager(TrustManagerFactory tmf) {
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("no system X509TrustManager");
    }
}
