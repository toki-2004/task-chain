package com.toki.taskchain;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 通知轮询器：从服务器拉取需要当前登录用户处理的事项并弹系统通知。
 * 供闹钟广播（后台约 15 分钟一次）与 App 打开/回前台时调用，无第三方依赖。
 */
public final class NotifyPoller {

    private static final String CHANNEL_ALERT = "notify_alert";

    private NotifyPoller() {
    }

    public static void poll(Context ctx) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences("taskchain", Context.MODE_PRIVATE);
        String server = sp.getString("server_url", "");
        if (server.isEmpty()) {
            return;
        }
        String cookie = CookieManager.getInstance().getCookie(server);
        if (cookie == null || !cookie.contains("sid=")) {
            return; // 未登录：不打扰
        }
        long lastId = sp.getLong("notify_last_id", 0);
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(server + "/api/notifications?since=" + lastId).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Cookie", cookie);
            if (conn.getResponseCode() != 200) {
                return; // 会话失效/地址失效：静默
            }
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            JSONObject obj = new JSONObject(sb.toString());
            JSONArray items = obj.optJSONArray("items");
            long newest = (long) obj.optDouble("last_id", lastId);
            if (items != null && items.length() > 0) {
                NotificationManager nm = ctx.getSystemService(NotificationManager.class);
                ensureChannel(nm);
                for (int i = 0; i < items.length(); i++) {
                    notifyOne(ctx, nm, items.getJSONObject(i));
                }
            }
            sp.edit().putLong("notify_last_id", newest).apply();
        } catch (Exception ignored) {
        }
    }

    private static void notifyOne(Context ctx, NotificationManager nm, JSONObject it) {
        int nid = it.optInt("node_id", 0);
        long eid = it.optLong("id", System.currentTimeMillis());
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("taskchain_push", true);
        intent.putExtra("node", nid);
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) eid, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = builder(ctx);
        b.setContentTitle(it.optString("title", "协同任务链"))
                .setContentText(it.optString("body", ""))
                .setStyle(new Notification.BigTextStyle().bigText(it.optString("body", "")))
                .setContentIntent(pi);
        nm.notify((int) eid, b.build());
    }

    private static void ensureChannel(NotificationManager nm) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ALERT, "任务提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("新任务、待审核、被驳回、申诉与反馈等需要处理的事项");
            nm.createNotificationChannel(ch);
        }
    }

    private static Notification.Builder builder(Context ctx) {
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ALERT)
                : new Notification.Builder(ctx);
        return b.setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true);
    }
}
