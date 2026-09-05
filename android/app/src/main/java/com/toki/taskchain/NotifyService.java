package com.toki.taskchain;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 常驻前台通知服务：每 60 秒向服务器拉取需要当前登录用户处理的事项，
 * 有新事项时弹出系统通知，点击直达对应任务详情。使用 WebView 的 Cookie 鉴权。
 */
public class NotifyService extends Service {

    private static final String CHANNEL_ALERT = "notify_alert";
    private static final String CHANNEL_RUN = "notify_running";
    private static final long POLL_SECONDS = 60;
    private volatile boolean running = true;
    private Thread worker;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannels();
        startForeground(1, runningNotification());
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(pollLoop);
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private final Runnable pollLoop = new Runnable() {
        @Override
        public void run() {
            while (running) {
                try {
                    pollOnce();
                } catch (Exception ignored) {
                }
                for (int i = 0; i < POLL_SECONDS && running; i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (Exception e) {
                        return;
                    }
                }
            }
        }
    };

    private void pollOnce() {
        String server = getSharedPreferences("taskchain", MODE_PRIVATE)
                .getString("server_url", "");
        if (server.isEmpty()) {
            return;
        }
        String cookie = CookieManager.getInstance().getCookie(server);
        if (cookie == null || !cookie.contains("sid=")) {
            return; // 未登录：不打扰
        }
        long lastId = getSharedPreferences("taskchain", MODE_PRIVATE)
                .getLong("notify_last_id", 0);
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(server + "/api/notifications?since=" + lastId).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Cookie", cookie);
            if (conn.getResponseCode() != 200) {
                return; // 未登录/地址失效：静默
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
                NotificationManager nm = getSystemService(NotificationManager.class);
                for (int i = 0; i < items.length(); i++) {
                    notifyOne(nm, items.getJSONObject(i));
                }
            }
            getSharedPreferences("taskchain", MODE_PRIVATE).edit()
                    .putLong("notify_last_id", newest).apply();
        } catch (Exception ignored) {
        }
    }

    private void notifyOne(NotificationManager nm, JSONObject it) {
        int nid = it.optInt("node_id", 0);
        long eid = it.optLong("id", System.currentTimeMillis());
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("taskchain_push", true);
        intent.putExtra("node", nid);
        PendingIntent pi = PendingIntent.getActivity(this, (int) eid, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ALERT)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(it.optString("title", "协同任务链"))
                .setContentText(it.optString("body", ""))
                .setStyle(new Notification.BigTextStyle()
                        .bigText(it.optString("body", "")))
                .setContentIntent(pi)
                .setAutoCancel(true);
        nm.notify((int) eid, b.build());
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT, "任务提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            alert.setDescription("新任务、待审核、被驳回、申诉与反馈等需要处理的事项");
            nm.createNotificationChannel(alert);
            NotificationChannel run = new NotificationChannel(CHANNEL_RUN, "后台服务",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(run);
        }
    }

    private Notification runningNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_RUN)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("协同任务链")
                .setContentText("通知服务运行中，有新任务会提醒你")
                .setOngoing(true)
                .build();
    }
}
