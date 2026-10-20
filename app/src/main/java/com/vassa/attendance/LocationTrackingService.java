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
import android.os.PowerManager;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LocationTrackingService extends Service {
    private static final String TAG = "VassaLocationService";
    private static final String CHANNEL_ID = "vassa_tracking_channel";
    private static final int NOTIFICATION_ID = 25955;

    // Active Webhook URL (Linked directly to Google Apps Script)
    public static final String DEFAULT_WEBHOOK_URL = "https://script.google.com/macros/s/AKfycby1bYzPfBAuC9niiJMBHTa4JPx5HjZog1yEnzj9oTzKbIv7EwDGX76dZ_jZHFUemQUR/exec";

    // 5 Minutes auto-sync interval (300,000 ms)
    private static final long SYNC_INTERVAL_MS = 300000;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private ScheduledExecutorService scheduler;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = getSharedPreferences("VassaPrefs", MODE_PRIVATE);
        String savedWebhook = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL);
        if (!savedWebhook.contains("AKfycby1bYzPfBAuC9niiJMBHTa4JPx5HjZog1yEnzj9oTzKbIv7EwDGX76dZ_jZHFUemQUR")) {
            prefs.edit().putString("webhook_url", DEFAULT_WEBHOOK_URL).apply();
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Auto-Sync Active (Every 5 mins) • Stationary Mode ON"));

        // Acquire partial wakelock capability to wake CPU during stationary sleep
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VassaAttendance::StationarySyncWakeLock");
            wakeLock.setReferenceCounted(false);
        }

        // Setup Dual-Trigger Tracking
        setupLocationUpdates();
        startStationaryPeriodicSyncTimer();
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

    /**
     * Trigger 1: Active FusedLocation Updates (Triggers on GPS hardware fixes)
     * Min distance is explicitly 0 to allow stationary fixes
     */
    private void setupLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, SYNC_INTERVAL_MS
        )
                .setMinUpdateIntervalMillis(120000)
                .setMinUpdateDistanceMeters(0) // Stationary fix: 0 meters movement restriction
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                SharedPreferences p = getSharedPreferences("VassaPrefs", MODE_PRIVATE);
                if (!p.getBoolean("has_checked_in", false) || p.getBoolean("has_checked_out", false)) {
                    stopSelf();
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        pushLocationToGoogleSheets(location, "GPS Provider Trigger");
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

    /**
     * Trigger 2: Ironclad Stationary Guard (Scheduled Timer every 5 minutes)
     * Bypasses Android stationary sleep / Doze mode throttling when phone is sitting still on a desk!
     */
    private void startStationaryPeriodicSyncTimer() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();

        // Run immediately after 1 minute, then strictly every 5 minutes (300 seconds)
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                SharedPreferences p = getSharedPreferences("VassaPrefs", MODE_PRIVATE);
                if (!p.getBoolean("has_checked_in", false) || p.getBoolean("has_checked_out", false)) {
                    Log.i(TAG, "⛔ Stationary timer: Staff is not on active duty. Halting service.");
                    stopSelf();
                    return;
                }
                if (wakeLock != null) {
                    wakeLock.acquire(15000); // 15s wake lock to complete network request
                }
                Log.d(TAG, "⏰ 5-Minute Stationary Guard triggered: Requesting fresh location fix...");

                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener(location -> {
                            if (location != null) {
                                pushLocationToGoogleSheets(location, "5-Min Auto Timer (Stationary)");
                            } else {
                                // Fallback to last known location if indoor GPS satellite lock is momentarily weak
                                fusedLocationClient.getLastLocation().addOnSuccessListener(lastLoc -> {
                                    if (lastLoc != null) {
                                        pushLocationToGoogleSheets(lastLoc, "5-Min Auto Timer (Last Fix)");
                                    }
                                });
                            }
                        })
                        .addOnFailureListener(e -> {
                            Log.w(TAG, "getCurrentLocation failed: " + e.getMessage());
                        });

            } catch (SecurityException se) {
                Log.e(TAG, "SecurityException in timer: " + se.getMessage());
            } catch (Exception ex) {
                Log.e(TAG, "Exception in stationary timer: " + ex.getMessage());
            } finally {
                // WakeLock automatically expires via timeout or released after HTTP request
            }
        }, 60, 300, TimeUnit.SECONDS);
    }

    private void pushLocationToGoogleSheets(Location location, String triggerSource) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("VassaPrefs", MODE_PRIVATE);
                boolean hasCheckedIn = prefs.getBoolean("has_checked_in", false);
                boolean hasCheckedOut = prefs.getBoolean("has_checked_out", false);

                // STRICT RULE: Location sync MUST ONLY run while currently On Duty (Checked-In AND NOT Checked-Out)
                if (!hasCheckedIn || hasCheckedOut) {
                    Log.i(TAG, "⛔ [SYNC HALTED] Staff is not on active duty (hasCheckedIn=" + hasCheckedIn + ", hasCheckedOut=" + hasCheckedOut + "). Stopping background service.");
                    stopSelf();
                    return;
                }

                String empId = prefs.getString("locked_emp_id", prefs.getString("emp_id", ""));
                String empName = prefs.getString("locked_emp_name", prefs.getString("emp_name", ""));
                if (empId.isEmpty()) {
                    Log.i(TAG, "No registered staff on device, skipping background ping.");
                    stopSelf();
                    return;
                }
                String webhookUrl = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL);

                // Cache last coordinates for fallback
                prefs.edit()
                        .putFloat("last_known_lat", (float) location.getLatitude())
                        .putFloat("last_known_lon", (float) location.getLongitude())
                        .apply();

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
                json.put("remarks", "Native Android Auto-Sync 5min (" + triggerSource + ")");

                URL url = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = json.toString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                String timeStr = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date());

                if (code == 200 || code == 302) {
                    Log.i(TAG, "✅ [GPS AUTO-SYNC SUCCESS] " + empName + " lat=" + location.getLatitude() + " lon=" + location.getLongitude() + " at " + timeStr + " (" + triggerSource + ")");
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
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    try { wakeLock.release(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences("VassaPrefs", MODE_PRIVATE);
        boolean hasCheckedIn = prefs.getBoolean("has_checked_in", false);
        boolean hasCheckedOut = prefs.getBoolean("has_checked_out", false);
        if (!hasCheckedIn || hasCheckedOut) {
            Log.i(TAG, "LocationTrackingService onStartCommand: User not on active duty. Stopping immediately.");
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        stopForeground(true);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
