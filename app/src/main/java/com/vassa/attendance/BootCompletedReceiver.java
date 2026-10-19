package com.vassa.attendance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("VassaPrefs", Context.MODE_PRIVATE);
            boolean isTrackingEnabled = prefs.getBoolean("is_tracking_active", false);

            if (isTrackingEnabled) {
                Log.d("VassaBoot", "Phone rebooted. Restarting Vassa Location Service...");
                Intent serviceIntent = new Intent(context, LocationTrackingService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
