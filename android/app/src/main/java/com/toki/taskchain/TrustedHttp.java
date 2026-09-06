package com.toki.taskchain;

import android.content.Context;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** 应用内 HttpURLConnection 的统一入口：系统 CA 之外，额外信任内置的
 *  SakuraFrp Automatic TLS 隧道证书（自签名、SAN 匹配 frp-off.com）。
 *  WebView 对用户配置的服务器地址已放行该证书，这里保持同一信任边界，
 *  否则 frp https 地址下通知轮询 / APK 更新检查会因证书校验失败而静默拿不到数据。 */
public final class TrustedHttp {

    private static final String CERT_ASSET = "frp_cert.pem";
    private static volatile SSLSocketFactory socketFactory;

    private TrustedHttp() {
    }

    public static HttpURLConnection open(Context ctx, String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (conn instanceof HttpsURLConnection) {
            ((HttpsURLConnection) conn).setSSLSocketFactory(sslSocketFactory(ctx));
        }
        return conn;
    }

    private static SSLSocketFactory sslSocketFactory(Context ctx) throws Exception {
        if (socketFactory == null) {
            synchronized (TrustedHttp.class) {
                if (socketFactory == null) {
                    TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                            TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init((java.security.KeyStore) null); // 系统默认信任库
                    final X509TrustManager system = firstSystemManager(tmf);
                    final X509Certificate extra;
                    try (InputStream in = ctx.getAssets().open(CERT_ASSET)) {
                        extra = (X509Certificate) CertificateFactory.getInstance("X.509")
                                .generateCertificate(in);
                    }
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
                            } catch (CertificateException e) {
                                // 回退：链上出现内置的 frp 隧道证书即放行（自签名单证书场景）
                                for (X509Certificate c : chain) {
                                    if (c.equals(extra)) {
                                        return;
                                    }
                                }
                                throw e;
                            }
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return system.getAcceptedIssuers();
                        }
                    }}, new SecureRandom());
                    socketFactory = sc.getSocketFactory();
                }
            }
        }
        return socketFactory;
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
