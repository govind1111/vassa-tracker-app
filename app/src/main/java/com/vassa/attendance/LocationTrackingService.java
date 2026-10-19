package com.vassa.attendance;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LocationTrackingService extends Service {
    private static final String TAG = "VassaLocationService";
    private static final String CHANNEL_ID = "vassa_tracking_channel";
    private static final int NOTIFICATION_ID = 25955;

    // Default Webhook URL (Linked directly to Google Apps Script)
    public static final String DEFAULT_WEBHOOK_URL = "https://script.google.com/macros/s/AKfycby1bYzPfBAuC9niiJMBHTa4JPx5HjZog1yEnzj9oTzKbIv7EwDGX76dZ_jZHFUemQUR/exec";

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Tracking Active • Screen Off Mode"));

        setupLocationUpdates();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Vassa Properties Field Duty Radar",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains continuous background GPS tracking while on field duty");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildForegroundNotification(String statusText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🏢 Vassa Properties — Field Duty Active")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void setupLocationUpdates() {
        // Ping every 5 minutes (300,000 ms), fastest interval 2 minutes
        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 300000
        )
                .setMinUpdateIntervalMillis(120000)
                .setMinUpdateDistanceMeters(20) // Only ping if moved at least 20 meters or 5 mins passed
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        pushLocationToGoogleSheets(location);
                    }
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
            );
            Log.d(TAG, "FusedLocation updates started successfully.");
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission missing: " + e.getMessage());
        }
    }

    private void pushLocationToGoogleSheets(Location location) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("VassaPrefs", MODE_PRIVATE);
                String empId = prefs.getString("emp_id", "VP-004");
                String empName = prefs.getString("emp_name", "Staff Member");
                String webhookUrl = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL);

                // Get battery level
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                int batteryPct = (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;

                JSONObject json = new JSONObject();
                json.put("action", "live_ping");
                json.put("empId", empId);
                json.put("empName", empName);
                json.put("lat", location.getLatitude());
                json.put("lon", location.getLongitude());
                json.put("accuracy", "±" + Math.round(location.getAccuracy()) + "m");
                json.put("speed", Math.round(location.getSpeed() * 3.6) + " km/h");
                json.put("battery", (batteryPct > 0 ? (batteryPct + "%") : "Unknown"));
                json.put("remarks", "Native Android Background Service (Screen Off)");

                URL url = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = json.toString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                String timeStr = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date());

                if (code == 200) {
                    Log.i(TAG, "✅ [GPS PUSH SUCCESS] " + empName + " lat=" + location.getLatitude() + " lon=" + location.getLongitude() + " at " + timeStr);
                    // Update notification with latest location timestamp
                    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (manager != null) {
                        manager.notify(NOTIFICATION_ID, buildForegroundNotification("Live Ping Synced: " + timeStr + " (±" + Math.round(location.getAccuracy()) + "m)"));
                    }
                } else {
                    Log.w(TAG, "⚠️ [GPS PUSH HTTP " + code + "]");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error pushing location: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // Automatically recreate service if Android OS kills it
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
