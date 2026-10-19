package com.vassa.attendance;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    private TextView tvStatus, tvEmpDetails, tvLastSync;
    private Button btnToggleTracking, btnCheckIn, btnSaveConfig;
    private EditText etEmpId, etEmpName, etWebhook;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("VassaPrefs", MODE_PRIVATE);

        tvStatus = findViewById(R.id.tvStatus);
        tvEmpDetails = findViewById(R.id.tvEmpDetails);
        tvLastSync = findViewById(R.id.tvLastSync);
        btnToggleTracking = findViewById(R.id.btnToggleTracking);
        btnCheckIn = findViewById(R.id.btnCheckIn);
        btnSaveConfig = findViewById(R.id.btnSaveConfig);

        etEmpId = findViewById(R.id.etEmpId);
        etEmpName = findViewById(R.id.etEmpName);
        etWebhook = findViewById(R.id.etWebhook);

        // Load saved preferences
        String empId = prefs.getString("emp_id", "VP-004");
        String empName = prefs.getString("emp_name", "Govind Gore");
        String webhook = prefs.getString("webhook_url", LocationTrackingService.DEFAULT_WEBHOOK_URL);

        etEmpId.setText(empId);
        etEmpName.setText(empName);
        etWebhook.setText(webhook);

        updateUI();

        btnSaveConfig.setOnClickListener(v -> {
            String newId = etEmpId.getText().toString().trim();
            String newName = etEmpName.getText().toString().trim();
            String newUrl = etWebhook.getText().toString().trim();

            if (newId.isEmpty() || newName.isEmpty()) {
                Toast.makeText(this, "Please enter Employee ID and Name", Toast.LENGTH_SHORT).show();
                return;
            }

            prefs.edit()
                    .putString("emp_id", newId)
                    .putString("emp_name", newName)
                    .putString("webhook_url", newUrl.isEmpty() ? LocationTrackingService.DEFAULT_WEBHOOK_URL : newUrl)
                    .apply();

            Toast.makeText(this, "Profile Saved: " + newName + " (" + newId + ")", Toast.LENGTH_SHORT).show();
            updateUI();
        });

        btnToggleTracking.setOnClickListener(v -> {
            if (!hasLocationPermissions()) {
                requestLocationPermissions();
                return;
            }

            boolean isCurrentlyActive = prefs.getBoolean("is_tracking_active", false);
            if (isCurrentlyActive) {
                stopLocationService();
            } else {
                startLocationService();
            }
        });

        btnCheckIn.setOnClickListener(v -> {
            if (!hasLocationPermissions()) {
                requestLocationPermissions();
                return;
            }
            startLocationService();
            String timeNow = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date());
            Toast.makeText(this, "✅ Check-In Recorded at " + timeNow + "! Background radar running.", Toast.LENGTH_LONG).show();
        });

        checkAndRequestPermissions();
    }

    private void updateUI() {
        String empId = prefs.getString("emp_id", "VP-004");
        String empName = prefs.getString("emp_name", "Govind Gore");
        boolean isActive = prefs.getBoolean("is_tracking_active", false);

        tvEmpDetails.setText("👤 " + empName + " (" + empId + ") • Vassa Properties");

        if (isActive) {
            tvStatus.setText("🟢 24/7 BACKGROUND TRACKING: ACTIVE");
            tvStatus.setTextColor(0xFF34D399);
            btnToggleTracking.setText("⏹️ STOP TRACKING (END DUTY)");
            btnToggleTracking.setBackgroundColor(0xFFDC2626);
        } else {
            tvStatus.setText("⚪ TRACKING STATUS: IDLE");
            tvStatus.setTextColor(0xFF94A3B8);
            btnToggleTracking.setText("▶️ START REAL-TIME TRACKING");
            btnToggleTracking.setBackgroundColor(0xFF0284C7);
        }
    }

    private void startLocationService() {
        prefs.edit().putBoolean("is_tracking_active", true).apply();
        Intent intent = new Intent(this, LocationTrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateUI();
        Toast.makeText(this, "🟢 Background GPS Service Started!", Toast.LENGTH_SHORT).show();
    }

    private void stopLocationService() {
        prefs.edit().putBoolean("is_tracking_active", false).apply();
        Intent intent = new Intent(this, LocationTrackingService.class);
        stopService(intent);
        updateUI();
        Toast.makeText(this, "⏹️ Tracking Stopped.", Toast.LENGTH_SHORT).show();
    }

    private boolean hasLocationPermissions() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return fine && coarse;
    }

    private void checkAndRequestPermissions() {
        if (!hasLocationPermissions()) {
            requestLocationPermissions();
        }
    }

    private void requestLocationPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS
                },
                PERMISSION_REQUEST_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Now request background location on Android 10+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(
                                this,
                                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                1002
                        );
                    }
                }
                Toast.makeText(this, "✅ GPS Permissions Granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ Location permission is required for field duty.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
