package com.vassa.attendance;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;


import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    public static final String MASTER_ADMIN_PIN = "25955";

    // Active Google Apps Script Deployment URL
    public static final String DEFAULT_WEBHOOK_URL = "https://script.google.com/macros/s/AKfycbxGLLU_zjd3fV5fYfDL8w-dIHI2p-4_f0dOFojexKjaLmUcuFRbmThySpoCzlm5xAXuNQ/exec";

    // UI Elements
    private TextView tvLiveClock, tvLiveDate, tvShiftTag;
    private LinearLayout llProfileBox, llStaffSelectGroup;
    private TextView tvProfileName, tvProfileMeta;
    private Button btnResetAdmin;
    private Spinner spStaff, spLocation;

    private LinearLayout llBannerStatus;
    private TextView tvBannerTitle, tvBannerTime, tvBannerWarn;

    private LinearLayout llRadarCard;
    private TextView tvRadarIcon, tvRadarTitle, tvRadarDesc;

    private Button btnCheckIn, btnCheckOut, btnManualPing;
    private TextView tvPingStatus, tvAdminSettings;

    private SharedPreferences prefs;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private double currentLat = 0.0;
    private double currentLon = 0.0;
    private float currentAccuracy = 0f;
    private float distanceToSite = 9999f;

    private Handler clockHandler = new Handler(Looper.getMainLooper());
    private Runnable clockRunnable;

    // Data Models
    public static class StaffMember {
        public String id;
        public String name;
        public String role;
        public String dept;

        public StaffMember(String id, String name, String role, String dept) {
            this.id = id;
            this.name = name;
            this.role = role;
            this.dept = dept;
        }

        @NonNull
        @Override
        public String toString() {
            return id + ": " + name + " (" + role + ")";
        }
    }

    public static class ProjectSite {
        public String id;
        public String name;
        public double lat;
        public double lon;
        public float radius;

        public ProjectSite(String id, String name, double lat, double lon, float radius) {
            this.id = id;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.radius = radius;
        }

        @NonNull
        @Override
        public String toString() {
            return id + ": " + name;
        }
    }

    private List<StaffMember> staffList = new ArrayList<>();
    private List<ProjectSite> sitesList = new ArrayList<>();
    private ArrayAdapter<StaffMember> staffAdapter;
    private ArrayAdapter<ProjectSite> siteAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("VassaPrefs", MODE_PRIVATE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        initData();
        bindViews();
        setupClock();
        setupSpinners();
        setupListeners();

        checkAndRequestPermissions();
        updateUI();
        syncLiveMetadataFromSheet(false);
    }

    private void initData() {
        staffList.clear();
        String cachedStaff = prefs.getString("cached_staff_json", null);
        if (cachedStaff != null) {
            try {
                JSONArray arr = new JSONArray(cachedStaff);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    staffList.add(new StaffMember(o.optString("id"), o.optString("name"), o.optString("role"), o.optString("dept")));
                }
            } catch (Exception ignored) {}
        }
        if (staffList.isEmpty()) {
            staffList.add(new StaffMember("VP-004", "Govind Gore", "Director / Founder", "Management"));
            staffList.add(new StaffMember("VP-002", "Vaishnavi Rajguru", "HR Pre-Sale", "Office"));
            staffList.add(new StaffMember("VP-003", "Savita Chavan", "Staff", "Office"));
        }

        sitesList.clear();
        String cachedSites = prefs.getString("cached_sites_json", null);
        if (cachedSites != null) {
            try {
                JSONArray arr = new JSONArray(cachedSites);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    sitesList.add(new ProjectSite(
                            o.optString("id"),
                            o.optString("name"),
                            o.optDouble("lat"),
                            o.optDouble("lon"),
                            (float) o.optDouble("radius", 100.0)
                    ));
                }
            } catch (Exception ignored) {}
        }
        if (sitesList.isEmpty()) {
            sitesList.add(new ProjectSite("SITE-01", "Golden City Center (Main Office)", 19.874668, 75.372983, 100f));
            sitesList.add(new ProjectSite("SITE-02", "Waluj Mahanagar-1", 19.888512, 75.465034, 100f));
            sitesList.add(new ProjectSite("SITE-03", "Waluj Mahanagar-2", 19.839045, 75.321078, 100f));
            sitesList.add(new ProjectSite("SITE-04", "Shendra MIDC - Shendra", 19.831567, 75.315023, 100f));
            sitesList.add(new ProjectSite("SITE-05", "Beed Bypass - Satara Parisar", 19.845012, 75.228045, 100f));
        }
    }

    private void bindViews() {
        tvLiveClock = findViewById(R.id.tvLiveClock);
        tvLiveDate = findViewById(R.id.tvLiveDate);
        tvShiftTag = findViewById(R.id.tvShiftTag);

        llProfileBox = findViewById(R.id.llProfileBox);
        llStaffSelectGroup = findViewById(R.id.llStaffSelectGroup);
        tvProfileName = findViewById(R.id.tvProfileName);
        tvProfileMeta = findViewById(R.id.tvProfileMeta);
        btnResetAdmin = findViewById(R.id.btnResetAdmin);
        spStaff = findViewById(R.id.spStaff);
        spLocation = findViewById(R.id.spLocation);

        llBannerStatus = findViewById(R.id.llBannerStatus);
        tvBannerTitle = findViewById(R.id.tvBannerTitle);
        tvBannerTime = findViewById(R.id.tvBannerTime);
        tvBannerWarn = findViewById(R.id.tvBannerWarn);

        llRadarCard = findViewById(R.id.llRadarCard);
        tvRadarIcon = findViewById(R.id.tvRadarIcon);
        tvRadarTitle = findViewById(R.id.tvRadarTitle);
        tvRadarDesc = findViewById(R.id.tvRadarDesc);

        btnCheckIn = findViewById(R.id.btnCheckIn);
        btnCheckOut = findViewById(R.id.btnCheckOut);
        btnManualPing = findViewById(R.id.btnManualPing);
        tvPingStatus = findViewById(R.id.tvPingStatus);
        tvAdminSettings = findViewById(R.id.tvAdminSettings);
    }

    private void setupClock() {
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                Date now = new Date();
                String timeStr = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(now);
                String dateStr = new SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(now);
                tvLiveClock.setText(timeStr);
                tvLiveDate.setText(dateStr);
                clockHandler.postDelayed(this, 1000);
            }
        };
        clockHandler.post(clockRunnable);
    }

    private void setupSpinners() {
        // Staff Spinner
        staffAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                staffList
        );
        spStaff.setAdapter(staffAdapter);

        // Location Spinner
        siteAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                sitesList
        );
        spLocation.setAdapter(siteAdapter);

        // Staff Selection Listener
        spStaff.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!prefs.getBoolean("is_device_locked", false)) {
                    StaffMember selected = staffList.get(position);
                    lockDeviceToStaff(selected);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Site Selection Listener
        spLocation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                calculateGeofenceDistance();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupListeners() {
        // 0. Radar Card Tap: Force Sync Sites & Staff from Google Sheet
        llRadarCard.setOnClickListener(v -> {
            Toast.makeText(this, "🔄 Syncing latest coordinates from Google Sheet...", Toast.LENGTH_SHORT).show();
            syncLiveMetadataFromSheet(true);
        });

        // 1. Reset Admin PIN Dialog
        btnResetAdmin.setOnClickListener(v -> promptAdminPinReset());

        // 2. Check-In Button
        btnCheckIn.setOnClickListener(v -> {
            if (distanceToSite > 100f) {
                Toast.makeText(this, "⛔ Check-In Blocked! You are " + Math.round(distanceToSite) + "m away. 100m geofence required.", Toast.LENGTH_LONG).show();
                return;
            }
            recordAttendancePunch("Check-In");
        });

        // 3. Check-Out Button
        btnCheckOut.setOnClickListener(v -> recordAttendancePunch("Check-Out"));

        // 4. Manual Location Ping Button
        btnManualPing.setOnClickListener(v -> sendManualTelemetryPing());

        // 5. Admin Settings Dialog (Webhook Endpoint)
        tvAdminSettings.setOnClickListener(v -> promptAdminSettings());
    }

    private void lockDeviceToStaff(StaffMember staff) {
        prefs.edit()
                .putBoolean("is_device_locked", true)
                .putString("locked_emp_id", staff.id)
                .putString("locked_emp_name", staff.name)
                .putString("locked_emp_role", staff.role)
                .putString("locked_emp_dept", staff.dept)
                .apply();

        updateUI();
        Toast.makeText(this, "🔒 Device successfully locked to " + staff.name, Toast.LENGTH_SHORT).show();
    }

    private void promptAdminPinReset() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🔐 Master Admin Security PIN");
        builder.setMessage("Staff members name switch nahi kar sakte. Master Admin PIN dalein device unlock / reset karne ke liye:");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Enter Admin PIN (Default: 25955)");
        builder.setView(input);

        builder.setPositiveButton("Unlock", (dialog, which) -> {
            String enteredPin = input.getText().toString().trim();
            if (MASTER_ADMIN_PIN.equals(enteredPin)) {
                prefs.edit().putBoolean("is_device_locked", false).apply();
                updateUI();
                Toast.makeText(this, "✅ Device successfully unlocked by Admin.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "❌ Galat Admin PIN! Access denied.", Toast.LENGTH_LONG).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void promptAdminSettings() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚙️ Google Apps Script Endpoint");
        builder.setMessage("Web App Deployment URL for live reading and writing:");

        final EditText input = new EditText(this);
        String currentUrl = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL);
        input.setText(currentUrl);
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newUrl = input.getText().toString().trim();
            if (!newUrl.isEmpty()) {
                prefs.edit().putString("webhook_url", newUrl).apply();
                Toast.makeText(this, "✅ API URL Saved!", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void updateUI() {
        boolean isLocked = prefs.getBoolean("is_device_locked", false);
        String empId = prefs.getString("locked_emp_id", "VP-004");
        String empName = prefs.getString("locked_emp_name", "Govind Gore");
        String empRole = prefs.getString("locked_emp_role", "Director / Founder");

        if (isLocked) {
            llProfileBox.setVisibility(View.VISIBLE);
            llStaffSelectGroup.setVisibility(View.GONE);
            tvProfileName.setText(empName);
            tvProfileMeta.setText(empId + " • " + empRole);
        } else {
            llProfileBox.setVisibility(View.GONE);
            llStaffSelectGroup.setVisibility(View.VISIBLE);
        }

        boolean hasCheckedIn = prefs.getBoolean("has_checked_in", false);
        boolean hasCheckedOut = prefs.getBoolean("has_checked_out", false);
        String checkinTime = prefs.getString("checkin_time", "-");

        if (hasCheckedIn && !hasCheckedOut) {
            // ON DUTY
            llBannerStatus.setVisibility(View.VISIBLE);
            llBannerStatus.setBackgroundResource(R.drawable.bg_radar_inside);
            tvBannerTitle.setText("🟢 Check-In Recorded (On Duty)");
            tvBannerTitle.setTextColor(0xFF047857);
            tvBannerTime.setText("🕒 Time: " + checkinTime + " (Present)");
            tvBannerWarn.setText("⚡ Duty active hai. Kaam khatam hone par niche se Check-Out karein.");
            tvBannerWarn.setVisibility(View.VISIBLE);

            btnCheckIn.setVisibility(View.GONE);
            btnCheckOut.setVisibility(View.VISIBLE);
            btnCheckOut.setEnabled(true);
        } else if (hasCheckedOut) {
            // DAY COMPLETED
            llBannerStatus.setVisibility(View.VISIBLE);
            llBannerStatus.setBackgroundResource(R.drawable.bg_radar_locating);
            tvBannerTitle.setText("🏁 Shift Completed (Checked-Out)");
            tvBannerTitle.setTextColor(0xFF0369A1);
            tvBannerTime.setText("🕒 Checked-Out today. Have a good evening!");
            tvBannerWarn.setText("✅ Aaj ka din complete ho chuka hai. Agla check-in kal subah open hoga.");
            tvBannerWarn.setVisibility(View.VISIBLE);

            btnCheckIn.setVisibility(View.GONE);
            btnCheckOut.setVisibility(View.GONE);
        } else {
            // READY TO CHECK-IN
            llBannerStatus.setVisibility(View.GONE);
            btnCheckIn.setVisibility(View.VISIBLE);
            btnCheckOut.setVisibility(View.GONE);
        }

        calculateGeofenceDistance();
    }

    private void calculateGeofenceDistance() {
        if (spLocation.getSelectedItem() == null) return;
        ProjectSite site = (ProjectSite) spLocation.getSelectedItem();

        if (currentLat == 0.0 && currentLon == 0.0) {
            llRadarCard.setBackgroundResource(R.drawable.bg_radar_locating);
            tvRadarIcon.setText("📡");
            tvRadarTitle.setText("Detecting GPS Location...");
            tvRadarTitle.setTextColor(0xFF0369A1);
            tvRadarDesc.setText("Checking 100m geofence radius");
            tvRadarDesc.setTextColor(0xFF0284C7);

            btnCheckIn.setEnabled(false);
            btnCheckIn.setBackgroundResource(R.drawable.bg_btn_disabled);
            btnCheckIn.setText("🔒 Check-In Blocked (Acquiring GPS)");
            return;
        }

        float[] results = new float[1];
        Location.distanceBetween(currentLat, currentLon, site.lat, site.lon, results);
        distanceToSite = results[0];

        boolean hasCheckedIn = prefs.getBoolean("has_checked_in", false);

        if (distanceToSite <= site.radius) {
            // INSIDE 100m GEOFENCE
            llRadarCard.setBackgroundResource(R.drawable.bg_radar_inside);
            tvRadarIcon.setText("🟢");
            tvRadarTitle.setText("Within Geofence (" + Math.round(distanceToSite) + "m away)");
            tvRadarTitle.setTextColor(0xFF065F46);
            tvRadarDesc.setText("Location Verified: " + site.name);
            tvRadarDesc.setTextColor(0xFF047857);

            if (!hasCheckedIn) {
                btnCheckIn.setEnabled(true);
                btnCheckIn.setBackgroundResource(R.drawable.bg_btn_checkin);
                btnCheckIn.setText("✅ Check-In Now (" + Math.round(distanceToSite) + "m)");
            }
        } else {
            // OUTSIDE 100m GEOFENCE
            llRadarCard.setBackgroundResource(R.drawable.bg_radar_outside);
            tvRadarIcon.setText("🔴");
            tvRadarTitle.setText("Outside 100m Range (" + Math.round(distanceToSite) + "m away)");
            tvRadarTitle.setTextColor(0xFF991B1B);
            tvRadarDesc.setText("You must be within 100 meters of site pin");
            tvRadarDesc.setTextColor(0xFFB91C1C);

            if (!hasCheckedIn) {
                btnCheckIn.setEnabled(false);
                btnCheckIn.setBackgroundResource(R.drawable.bg_btn_disabled);
                btnCheckIn.setText("🔒 Check-In Blocked (" + Math.round(distanceToSite) + "m Away)");
            }
        }
    }

    private void recordAttendancePunch(String punchType) {
        if (!hasLocationPermissions()) {
            requestLocationPermissions();
            return;
        }

        Toast.makeText(this, "⏳ Sending " + punchType + " to Attendance Log...", Toast.LENGTH_SHORT).show();

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this, location -> {
                        if (location != null) {
                            currentLat = location.getLatitude();
                            currentLon = location.getLongitude();
                            currentAccuracy = location.getAccuracy();
                            calculateGeofenceDistance();
                            dispatchPunchHttpRequest(punchType);
                        } else {
                            dispatchPunchHttpRequest(punchType);
                        }
                    })
                    .addOnFailureListener(this, e -> dispatchPunchHttpRequest(punchType));
        } catch (SecurityException e) {
            Toast.makeText(this, "Location permission missing", Toast.LENGTH_SHORT).show();
        }
    }

    private void dispatchPunchHttpRequest(String punchType) {
        new Thread(() -> {
            try {
                String empId = prefs.getString("locked_emp_id", "VP-004");
                String empName = prefs.getString("locked_emp_name", "Govind Gore");
                String webhookUrl = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL);

                ProjectSite site = (ProjectSite) spLocation.getSelectedItem();
                String siteName = (site != null) ? site.name : "Golden City Center (Main Office)";
                String dutyType = (siteName.contains("Main Office")) ? "Office" : "Site";

                String deviceId = prefs.getString("device_uuid", "");
                if (deviceId.isEmpty()) {
                    deviceId = "DEV-" + Long.toHexString(System.currentTimeMillis()).toUpperCase();
                    prefs.edit().putString("device_uuid", deviceId).apply();
                }

                JSONObject json = new JSONObject();
                json.put("empId", empId);
                json.put("empName", empName);
                json.put("punchType", punchType); // "Check-In" or "Check-Out"
                json.put("dutyType", dutyType);
                json.put("targetSiteName", siteName);
                json.put("lat", currentLat);
                json.put("lon", currentLon);
                json.put("distanceMeters", Math.round(distanceToSite));
                json.put("accuracy", "±" + Math.round(currentAccuracy) + "m");
                json.put("isManual", false);
                json.put("deviceId", deviceId);
                json.put("remarks", "Mobile GPS " + punchType + " (" + Math.round(distanceToSite) + "m)");

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
                String timeNow = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date());

                String respBody = "";
                try {
                    java.io.InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                    if (is != null) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        respBody = sb.toString();
                    }
                } catch (Exception ignored) {}

                final String serverResp = respBody;

                runOnUiThread(() -> {
                    if (code == 200 || code == 302) {
                        // Check if server returned a cutoff or proxy error
                        if (serverResp.contains("isCutoff") || serverResp.contains("isProxyBlocked")) {
                            String msg = "⛔ Check-in Blocked by Server!";
                            try {
                                JSONObject rJson = new JSONObject(serverResp);
                                msg = rJson.optString("message", msg);
                            } catch (Exception ignored) {}
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                            return;
                        }

                        if ("Check-In".equalsIgnoreCase(punchType)) {
                            prefs.edit()
                                    .putBoolean("has_checked_in", true)
                                    .putBoolean("has_checked_out", false)
                                    .putString("checkin_time", timeNow)
                                    .apply();
                            startLocationService();
                            Toast.makeText(this, "✅ Check-In Recorded in Attendance Log at " + timeNow + "!", Toast.LENGTH_LONG).show();
                        } else {
                            prefs.edit()
                                    .putBoolean("has_checked_in", false)
                                    .putBoolean("has_checked_out", true)
                                    .putString("checkout_time", timeNow)
                                    .apply();
                            stopLocationService();
                            Toast.makeText(this, "🏁 Check-Out Recorded in Attendance Log at " + timeNow + "!", Toast.LENGTH_LONG).show();
                        }
                        updateUI();
                    } else {
                        Toast.makeText(this, "⚠️ Punch submitted (HTTP " + code + ")", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "❌ Error saving punch: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void sendManualTelemetryPing() {
        if (!hasLocationPermissions()) {
            requestLocationPermissions();
            return;
        }

        btnManualPing.setEnabled(false);
        tvPingStatus.setText("📡 Acquiring high-accuracy GPS coordinates...");

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this, location -> {
                        if (location != null) {
                            currentLat = location.getLatitude();
                            currentLon = location.getLongitude();
                            currentAccuracy = location.getAccuracy();
                            calculateGeofenceDistance();
                            dispatchTelemetryPing();
                        } else {
                            dispatchTelemetryPing();
                        }
                    })
                    .addOnFailureListener(this, e -> dispatchTelemetryPing());
        } catch (SecurityException e) {
            btnManualPing.setEnabled(true);
            tvPingStatus.setText("Location permission missing");
        }
    }

    private void dispatchTelemetryPing() {
        new Thread(() -> {
            try {
                String empId = prefs.getString("locked_emp_id", "VP-004");
                String empName = prefs.getString("locked_emp_name", "Govind Gore");
                String webhookUrl = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL);

                ProjectSite site = (ProjectSite) spLocation.getSelectedItem();
                String siteName = (site != null) ? site.name : "Golden City Center (Main Office)";

                int batteryLevel = getBatteryLevel();

                JSONObject json = new JSONObject();
                json.put("action", "live_ping");
                json.put("empId", empId);
                json.put("empName", empName);
                json.put("lat", currentLat);
                json.put("lon", currentLon);
                json.put("accuracy", "±" + Math.round(currentAccuracy) + "m");
                json.put("targetSiteName", siteName);
                json.put("distanceMeters", Math.round(distanceToSite));
                json.put("battery", batteryLevel + "%");
                json.put("remarks", "Manual User Location Ping (White Dashboard App)");

                URL url = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = json.toString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                String timeNow = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(new Date());

                runOnUiThread(() -> {
                    btnManualPing.setEnabled(true);
                    if (code == 200 || code == 302) {
                        tvPingStatus.setText("🟢 Location synced with Google Sheet (" + timeNow + ")");
                        Toast.makeText(this, "✅ Live Location Ping Sent! Saved in Live_Tracking tab.", Toast.LENGTH_SHORT).show();
                    } else {
                        tvPingStatus.setText("⚠️ Synced (HTTP " + code + ")");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnManualPing.setEnabled(true);
                    tvPingStatus.setText("⚠️ Ping sync error: " + e.getMessage());
                });
            }
        }).start();
    }

    private int getBatteryLevel() {
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        return 100;
    }

    private void startLocationService() {
        Intent intent = new Intent(this, LocationTrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopLocationService() {
        Intent intent = new Intent(this, LocationTrackingService.class);
        stopService(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startContinuousLocationUpdates();
        syncLiveMetadataFromSheet(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopContinuousLocationUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (clockHandler != null && clockRunnable != null) {
            clockHandler.removeCallbacks(clockRunnable);
        }
    }

    private void startContinuousLocationUpdates() {
        if (!hasLocationPermissions()) return;

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000)
                .setMinUpdateIntervalMillis(2000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult.getLastLocation() != null) {
                    Location loc = locationResult.getLastLocation();
                    currentLat = loc.getLatitude();
                    currentLon = loc.getLongitude();
                    currentAccuracy = loc.getAccuracy();
                    calculateGeofenceDistance();
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException ignored) {}
    }

    private void stopContinuousLocationUpdates() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(
                                this,
                                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                1002
                        );
                    }
                }
                startContinuousLocationUpdates();
                Toast.makeText(this, "✅ GPS Permissions Granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ Location permission is required for attendance.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // =========================================================================
    // DYNAMIC AUTO-SYNC: Fetch latest Site_Master coordinates & Staff from Sheet
    // =========================================================================
    private void syncLiveMetadataFromSheet(boolean isManual) {
        new Thread(() -> {
            try {
                String webhookUrl = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL);
                String fetchUrl = webhookUrl + (webhookUrl.contains("?") ? "&" : "?") + "api=true";
                String jsonStr = httpGetWithRedirects(fetchUrl);
                if (jsonStr == null || jsonStr.trim().isEmpty()) {
                    if (isManual) {
                        runOnUiThread(() -> Toast.makeText(this, "⚠️ Empty response from Google Sheet", Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                JSONObject root = new JSONObject(jsonStr);
                JSONArray sitesArr = root.optJSONArray("sites");
                JSONArray staffArr = root.optJSONArray("staff");
                JSONObject settingsObj = root.optJSONObject("settings");

                List<ProjectSite> newSites = new ArrayList<>();
                if (sitesArr != null && sitesArr.length() > 0) {
                    for (int i = 0; i < sitesArr.length(); i++) {
                        JSONObject sObj = sitesArr.getJSONObject(i);
                        String sId = sObj.optString("id", "SITE-" + (i + 1));
                        String sName = sObj.optString("name", "Site " + (i + 1));
                        double sLat = sObj.optDouble("lat", 0.0);
                        double sLon = sObj.optDouble("lon", 0.0);
                        float sRadius = (float) sObj.optDouble("radius", 100.0);
                        newSites.add(new ProjectSite(sId, sName, sLat, sLon, sRadius));
                    }
                    prefs.edit().putString("cached_sites_json", sitesArr.toString()).apply();
                }

                List<StaffMember> newStaff = new ArrayList<>();
                if (staffArr != null && staffArr.length() > 0) {
                    for (int j = 0; j < staffArr.length(); j++) {
                        JSONObject stObj = staffArr.getJSONObject(j);
                        String stId = stObj.optString("id", "VP-" + (j + 1));
                        String stName = stObj.optString("name", "Staff");
                        String stRole = stObj.optString("role", "Staff");
                        String stDept = stObj.optString("dept", "Office");
                        newStaff.add(new StaffMember(stId, stName, stRole, stDept));
                    }
                    prefs.edit().putString("cached_staff_json", staffArr.toString()).apply();
                }

                final String shiftText = (settingsObj != null) ? settingsObj.optString("shiftText", null) : null;

                runOnUiThread(() -> {
                    if (!newSites.isEmpty()) {
                        int prevPos = spLocation.getSelectedItemPosition();
                        sitesList.clear();
                        sitesList.addAll(newSites);
                        if (siteAdapter != null) {
                            siteAdapter.notifyDataSetChanged();
                            if (prevPos >= 0 && prevPos < sitesList.size()) {
                                spLocation.setSelection(prevPos);
                            }
                        }
                        calculateGeofenceDistance();
                    }

                    if (!newStaff.isEmpty() && !prefs.getBoolean("is_device_locked", false)) {
                        int prevStaffPos = spStaff.getSelectedItemPosition();
                        staffList.clear();
                        staffList.addAll(newStaff);
                        if (staffAdapter != null) {
                            staffAdapter.notifyDataSetChanged();
                            if (prevStaffPos >= 0 && prevStaffPos < staffList.size()) {
                                spStaff.setSelection(prevStaffPos);
                            }
                        }
                    }

                    if (shiftText != null && !shiftText.isEmpty() && tvShiftTag != null) {
                        tvShiftTag.setText(shiftText);
                    }

                    if (isManual) {
                        Toast.makeText(this, "✅ " + sitesList.size() + " Sites Synced from Google Sheet!", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                if (isManual) {
                    runOnUiThread(() -> Toast.makeText(this, "❌ Sync error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    private String httpGetWithRedirects(String urlStr) throws Exception {
        int redirects = 0;
        while (redirects < 5) {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Vassa-Android-App/2.0");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_TEMP || 
                code == HttpURLConnection.HTTP_MOVED_PERM || 
                code == 307 || code == 308) {
                String newUrl = conn.getHeaderField("Location");
                conn.disconnect();
                if (newUrl != null && !newUrl.isEmpty()) {
                    urlStr = newUrl;
                    redirects++;
                    continue;
                }
            }

            if (code == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    conn.disconnect();
                    return sb.toString();
                }
            } else {
                conn.disconnect();
                return null;
            }
        }
        return null;
    }
}
