package com.toki.taskchain;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 闹钟广播接收器：由 AlarmManager 每 15 分钟左右触发一次后台通知检查。 */
public class NotifyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                NotifyPoller.poll(context);
            } finally {
                result.finish();
            }
        }).start();
    }
}
