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
import android.net.Uri;
import android.os.Environment;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;


import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    public static final String MASTER_ADMIN_PIN = "25955";

    // Shift Timings: Morning Check-in opens sharp at 06:00 AM. Check-in blocked before 06:00 AM & after Evening Cutoff.
    public static final int CHECKIN_OPEN_HOUR = 6;         // 06:00 AM Sharp
    public static final int CHECKIN_OPEN_MIN = 0;          // 00 Mins
    public static final int DEFAULT_SHIFT_START_HOUR = 10; // 10:00 AM (Official Duty Start)
    public static final int DEFAULT_SHIFT_START_MIN = 0;   // 00 Mins
    public static final int DEFAULT_CUTOFF_HOUR = 21;      // 09:00 PM (or 19:30 if set in sheet)
    public static final int DEFAULT_CUTOFF_MIN = 0;

    // Active Google Apps Script Deployment URL
        // GitHub / Remote Version Auto-Update Endpoint
    public static final String DEFAULT_GITHUB_UPDATE_URL = "https://raw.githubusercontent.com/govind1111/vassa-tracker-app/main/version.json";
    public static final int CURRENT_APP_VERSION_CODE = 2; // Incremented for Auto-Updater Release
    public static final String CURRENT_APP_VERSION_NAME = "1.1.0";

    public static final String DEFAULT_WEBHOOK_URL = "https://script.google.com/macros/s/AKfycby1bYzPfBAuC9niiJMBHTa4JPx5HjZog1yEnzj9oTzKbIv7EwDGX76dZ_jZHFUemQUR/exec";

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

    private Handler sheetPollHandler = new Handler(Looper.getMainLooper());
    private Runnable sheetPollRunnable = new Runnable() {
        @Override
        public void run() {
            syncLiveMetadataFromSheet(false);
            sheetPollHandler.postDelayed(this, 15000); // Auto-reconcile with Google Sheet every 15s
        }
    };

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
            if (id == null || id.isEmpty()) {
                return name; // Placeholder item: "-- Choose Your Name --"
            }
            return id + ": " + name + " (" + (role != null && !role.isEmpty() ? role : dept) + ")";
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
        String savedWebhook = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL);
        if (!savedWebhook.contains("AKfycby1bYzPfBAuC9niiJMBHTa4JPx5HjZog1yEnzj9oTzKbIv7EwDGX76dZ_jZHFUemQUR")) {
            prefs.edit().putString("webhook_url", DEFAULT_WEBHOOK_URL).apply();
        }
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Bind all UI Views FIRST to prevent any NullPointerException on startup
        bindViews();
        initData();
        setupClock();
        setupSpinners();
        setupListeners();
        checkDailyMidnightReset();

        checkAndRequestPermissions();
        updateUI();

        // Immediately sync live staff list and sites from Google Sheet
        syncLiveMetadataFromSheet(false);
        checkForAppUpdate(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkDailyMidnightReset();
        boolean hasCheckedIn = prefs.getBoolean("has_checked_in", false);
        boolean hasCheckedOut = prefs.getBoolean("has_checked_out", false);
        if (hasCheckedIn && !hasCheckedOut) {
            startLocationService();
        } else {
            stopLocationService();
        }
        updateUI();
        syncLiveMetadataFromSheet(false);
        checkForAppUpdate(false);
    }

    // ==============================================================================
    // 1. AUTOMATIC DAILY MIDNIGHT RESET (REFRESH AT 12:00 AM FOR FRESH DAY)
    // ==============================================================================
    private void checkDailyMidnightReset() {
        Date now = new Date();
        String todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now);
        String lastSavedDate = prefs.getString("last_active_date", "");

        if (lastSavedDate.isEmpty()) {
            // First time running on this device
            prefs.edit().putString("last_active_date", todayDate).apply();
            return;
        }

        if (!todayDate.equals(lastSavedDate)) {
            // A brand new day has begun (Midnight 12:00 AM passed)!
            android.util.Log.i("MainActivity", "🌅 Midnight Reset: New day detected (" + todayDate + " vs " + lastSavedDate + "). Automatically resetting attendance states.");
            prefs.edit()
                    .putString("last_active_date", todayDate)
                    .putBoolean("has_checked_in", false)
                    .putBoolean("has_checked_out", false)
                    .putString("checkin_time", "-")
                    .putString("checkout_time", "-")
                    .apply();

            stopLocationService();
            if (llProfileBox != null) {
                runOnUiThread(() -> {
                    updateUI();
                    syncLiveMetadataFromSheet(false);
                });
            }
        }
    }

    // ==============================================================================
    // 2. SHIFT TIMING RESTRICTIONS (06:00 AM START & EVENING CUTOFF)
    // ==============================================================================
    private boolean isBeforeMorningOpen() {
        Calendar cal = Calendar.getInstance();
        int curHour = cal.get(Calendar.HOUR_OF_DAY);
        int curMin = cal.get(Calendar.MINUTE);
        int curMins = curHour * 60 + curMin;

        int openMins = CHECKIN_OPEN_HOUR * 60 + CHECKIN_OPEN_MIN; // 06:00 AM
        return curMins < openMins;
    }

    private boolean isAfterEveningCutoff() {
        Calendar cal = Calendar.getInstance();
        int curHour = cal.get(Calendar.HOUR_OF_DAY);
        int curMin = cal.get(Calendar.MINUTE);
        int curMins = curHour * 60 + curMin;

        int cHour = prefs.getInt("cutoff_hour", DEFAULT_CUTOFF_HOUR);
        int cMin = prefs.getInt("cutoff_min", DEFAULT_CUTOFF_MIN);
        int cutoffMins = cHour * 60 + cMin;

        return curMins > cutoffMins;
    }

    // ==============================================================================
    // 3. INITIALIZE DATA (NO HARDCODED STAFF - FRESH SYNC FROM SHEET)
    // ==============================================================================
    private void initData() {
        staffList.clear();
        staffList.add(new StaffMember("", "-- Choose Your Name --", "Required", ""));

        // If previously cached staff exists from sheet, load it
        String cachedStaff = prefs.getString("cached_staff_json", null);
        if (cachedStaff != null) {
            try {
                JSONArray arr = new JSONArray(cachedStaff);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String id = o.optString("id", "");
                    String name = o.optString("name", "");
                    if (!id.isEmpty() && !name.isEmpty()) {
                        staffList.add(new StaffMember(id, name, o.optString("role"), o.optString("dept")));
                    }
                }
            } catch (Exception ignored) {}
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
                checkDailyMidnightReset();
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

        // Staff Selection Listener (DO NOT auto-lock on position 0!)
        spStaff.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0 && !prefs.getBoolean("is_device_locked", false)) {
                    StaffMember selected = staffList.get(position);
                    if (selected != null && selected.id != null && !selected.id.isEmpty()) {
                        promptConfirmDeviceLock(selected);
                    }
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

    private void promptConfirmDeviceLock(StaffMember staff) {
        new AlertDialog.Builder(this)
                .setTitle("🔒 Confirm Device Registration")
                .setMessage("Kya aap apna phone " + staff.name + " (" + staff.id + ") ke liye lock karna chahte hain?\n\nEk baar bind hone ke baad staff member name switch nahi kar sakte (Admin PIN required).")
                .setPositiveButton("Yes, Lock My Phone", (dialog, which) -> {
                    lockDeviceToStaff(staff);
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    spStaff.setSelection(0);
                })
                .setCancelable(false)
                .show();
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
            boolean isLocked = prefs.getBoolean("is_device_locked", false);
            if (!isLocked && spStaff.getSelectedItemPosition() <= 0) {
                Toast.makeText(this, "⚠️ Kripya pehle dropdown se apna name select karein!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (isBeforeMorningOpen()) {
                Toast.makeText(this, "⛔ Check-In Blocked: Office shift subah 06:00 AM se shuru hoti hai. 06:00 AM ke pehle check-in allowed nahi hai.", Toast.LENGTH_LONG).show();
                return;
            }

            if (isAfterEveningCutoff()) {
                Toast.makeText(this, "⛔ Check-In Blocked: Shift samapt ho chuki hai. Evening cutoff ke baad check-in strictly band hai.", Toast.LENGTH_LONG).show();
                return;
            }

            if (distanceToSite > 100f) {
                Toast.makeText(this, "⛔ Check-In Blocked! You are " + Math.round(distanceToSite) + "m away. 100m geofence required.", Toast.LENGTH_LONG).show();
                return;
            }

            recordAttendancePunch("Check-In");
        });

        // 3. Check-Out Button
        btnCheckOut.setOnClickListener(v -> recordAttendancePunch("Check-Out"));

        // 4. Manual Location Ping Button (Guaranteed Visible Label)
        btnManualPing.setText("📍 PING / MANUAL LOCATION SYNC");
        btnManualPing.setTextColor(0xFF0369A1);
        btnManualPing.setOnClickListener(v -> sendManualTelemetryPing());

        // 5. Admin Settings Dialog (Webhook Endpoint)
        tvAdminSettings.setOnClickListener(v -> promptAdminSettings());
    }

    private void reconcileStaffAttendanceFromCache(String empId, String empName) {
        String cachedStaff = prefs.getString("cached_staff_json", null);
        if (cachedStaff != null) {
            try {
                JSONArray arr = new JSONArray(cachedStaff);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String id = o.optString("id", "");
                    String name = o.optString("name", "");
                    if ((!empId.isEmpty() && id.equalsIgnoreCase(empId)) || (!empName.isEmpty() && name.equalsIgnoreCase(empName))) {
                        boolean hasIn = o.optBoolean("hasCheckedIn", false) || o.optBoolean("hasPunchedToday", false);
                        boolean hasOut = o.optBoolean("hasCheckedOut", false);
                        String inTime = o.optString("checkInTime", o.optString("punchTime", "-"));
                        String outTime = o.optString("checkOutTime", "-");

                        if (hasIn && hasOut) {
                            prefs.edit()
                                    .putBoolean("has_checked_in", true)
                                    .putBoolean("has_checked_out", true)
                                    .putString("checkin_time", inTime)
                                    .putString("checkout_time", outTime)
                                    .apply();
                            stopLocationService();
                        } else if (hasIn && !hasOut) {
                            prefs.edit()
                                    .putBoolean("has_checked_in", true)
                                    .putBoolean("has_checked_out", false)
                                    .putString("checkin_time", inTime)
                                    .apply();
                            startLocationService();
                        } else {
                            prefs.edit()
                                    .putBoolean("has_checked_in", false)
                                    .putBoolean("has_checked_out", false)
                                    .putString("checkin_time", "-")
                                    .putString("checkout_time", "-")
                                    .apply();
                            stopLocationService();
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void lockDeviceToStaff(StaffMember staff) {
        prefs.edit()
                .putBoolean("is_device_locked", true)
                .putString("locked_emp_id", staff.id)
                .putString("locked_emp_name", staff.name)
                .putString("locked_emp_role", staff.role)
                .putString("locked_emp_dept", staff.dept)
                .apply();

        // Immediately reconcile today's attendance state for this staff from cached sheet data!
        reconcileStaffAttendanceFromCache(staff.id, staff.name);

        updateUI();
        Toast.makeText(this, "🔒 Device successfully locked to " + staff.name, Toast.LENGTH_SHORT).show();

        // Immediately fetch latest live ground-truth from Google Sheet
        syncLiveMetadataFromSheet(false);
        checkForAppUpdate(false);
    }

    private void promptAdminPinReset() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🔐 Master Admin Security PIN");
        builder.setMessage("Staff members name switch nahi kar sakte. Master Admin PIN dalein device unlock / reset karne ke liye:");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Enter Secret Admin PIN");
        builder.setView(input);

        builder.setPositiveButton("Unlock", (dialog, which) -> {
            String enteredPin = input.getText().toString().trim();
            if (MASTER_ADMIN_PIN.equals(enteredPin)) {
                prefs.edit()
                        .putBoolean("is_device_locked", false)
                        .putString("locked_emp_id", "")
                        .putString("locked_emp_name", "")
                        .putString("locked_emp_role", "")
                        .putString("locked_emp_dept", "")
                        .apply();
                if (spStaff != null) {
                    spStaff.setSelection(0);
                }
                updateUI();
                Toast.makeText(this, "✅ Device successfully unlocked by Admin. Choose fresh name.", Toast.LENGTH_SHORT).show();
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
                syncLiveMetadataFromSheet(false);
        checkForAppUpdate(false);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void updateUI() {
        if (llProfileBox == null || llStaffSelectGroup == null) return;
        boolean isLocked = prefs.getBoolean("is_device_locked", false);
        String empId = prefs.getString("locked_emp_id", "");
        String empName = prefs.getString("locked_emp_name", "");
        String empRole = prefs.getString("locked_emp_role", "");

        if (isLocked && !empId.isEmpty()) {
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

        String shiftDisplay = prefs.getString("shift_display_text", "🕒 Shift: 10:00 AM — 07:00 PM");
        if (tvShiftTag != null) {
            tvShiftTag.setText(shiftDisplay);
        }

        if (hasCheckedIn && !hasCheckedOut) {
            // ON DUTY (Awaiting Check-Out)
            llBannerStatus.setVisibility(View.VISIBLE);
            llBannerStatus.setBackgroundResource(R.drawable.bg_radar_inside);
            tvBannerTitle.setText("🟢 Check-In Recorded (On Duty)");
            tvBannerTitle.setTextColor(0xFF047857);
            tvBannerTime.setText("🕒 Time: " + checkinTime + " (Present)");
            tvBannerWarn.setVisibility(View.GONE); // NIKAL DIYA (User Request)

            btnCheckIn.setVisibility(View.GONE);
            btnCheckOut.setVisibility(View.VISIBLE);
            btnCheckOut.setEnabled(true);
            btnManualPing.setVisibility(View.VISIBLE);
            tvPingStatus.setVisibility(View.VISIBLE);
        } else if (hasCheckedOut) {
            // DAY COMPLETED (Shift Completed - Both Check-In and Check-Out Done)
            llBannerStatus.setVisibility(View.VISIBLE);
            llBannerStatus.setBackgroundResource(R.drawable.bg_radar_locating);
            tvBannerTitle.setText("🏁 Shift Completed (Checked-Out)");
            tvBannerTitle.setTextColor(0xFF0369A1);
            String checkoutTime = prefs.getString("checkout_time", "-");
            tvBannerTime.setText("🕒 In: " + checkinTime + "  |  Out: " + checkoutTime);
            tvBannerWarn.setText("✅ Aaj ka din complete ho chuka hai. Agla check-in kal subah open hoga.");
            tvBannerWarn.setVisibility(View.VISIBLE);

            // BOTH BUTTONS OFF! (User Request)
            btnCheckIn.setVisibility(View.GONE);
            btnCheckOut.setVisibility(View.GONE);
            btnManualPing.setVisibility(View.GONE);
            tvPingStatus.setVisibility(View.GONE);
        } else {
            // READY TO CHECK-IN (Morning Shift Start)
            llBannerStatus.setVisibility(View.GONE);
            btnCheckIn.setVisibility(View.VISIBLE);
            btnCheckOut.setVisibility(View.GONE);
            btnManualPing.setVisibility(View.GONE);
            tvPingStatus.setVisibility(View.GONE);
        }

        calculateGeofenceDistance();
    }

    private void calculateGeofenceDistance() {
        if (spLocation == null || spLocation.getSelectedItem() == null || btnCheckIn == null) return;
        ProjectSite site = (ProjectSite) spLocation.getSelectedItem();

        boolean isLocked = prefs.getBoolean("is_device_locked", false);
        boolean hasCheckedIn = prefs.getBoolean("has_checked_in", false);

        // 1. If not checked in, enforce Shift Timing Restrictions first
        if (!hasCheckedIn) {
            if (!isLocked && spStaff.getSelectedItemPosition() <= 0) {
                btnCheckIn.setEnabled(false);
                btnCheckIn.setBackgroundResource(R.drawable.bg_btn_disabled);
                btnCheckIn.setText("🔒 Select Your Name to Check-In");
                return;
            }

            if (isBeforeMorningOpen()) {
                btnCheckIn.setEnabled(false);
                btnCheckIn.setBackgroundResource(R.drawable.bg_btn_disabled);
                btnCheckIn.setText("⛔ Check-In Opens at 06:00 AM");
                return;
            }

            if (isAfterEveningCutoff()) {
                btnCheckIn.setEnabled(false);
                btnCheckIn.setBackgroundResource(R.drawable.bg_btn_disabled);
                btnCheckIn.setText("⛔ Shift Over (Check-In Closed)");
                return;
            }
        }

        if (currentLat == 0.0 && currentLon == 0.0) {
            llRadarCard.setBackgroundResource(R.drawable.bg_radar_locating);
            tvRadarIcon.setText("📡");
            tvRadarTitle.setText("Detecting GPS Location...");
            tvRadarTitle.setTextColor(0xFF0369A1);
            tvRadarDesc.setText("Checking 100m geofence radius");
            tvRadarDesc.setTextColor(0xFF0284C7);

            if (!hasCheckedIn) {
                btnCheckIn.setEnabled(false);
                btnCheckIn.setBackgroundResource(R.drawable.bg_btn_disabled);
                btnCheckIn.setText("🔒 Check-In Blocked (Acquiring GPS)");
            }
            return;
        }

        float[] results = new float[1];
        Location.distanceBetween(currentLat, currentLon, site.lat, site.lon, results);
        distanceToSite = results[0];

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
                String empId = prefs.getString("locked_emp_id", "");
                String empName = prefs.getString("locked_emp_name", "");

                if (empId.isEmpty()) {
                    int pos = spStaff.getSelectedItemPosition();
                    if (pos > 0 && pos < staffList.size()) {
                        StaffMember sm = staffList.get(pos);
                        empId = sm.id;
                        empName = sm.name;
                    }
                }

                if (empId.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "⚠️ Kripya dropdown se apna name select karein!", Toast.LENGTH_LONG).show());
                    return;
                }

                if ("Check-In".equals(punchType)) {
                    if (isBeforeMorningOpen()) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "⛔ Check-In Blocked: Office shift subah 06:00 AM se shuru hoti hai.", Toast.LENGTH_LONG).show());
                        return;
                    }
                    if (isAfterEveningCutoff()) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "⛔ Check-In Blocked: Evening cutoff ke baad check-in strictly band hai.", Toast.LENGTH_LONG).show());
                        return;
                    }
                }

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
                    byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                final String finalEmpName = empName;

                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder respSb = new StringBuilder();
                if (is != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String l;
                        while ((l = reader.readLine()) != null) {
                            respSb.append(l);
                        }
                    }
                }
                String respStr = respSb.toString();
                android.util.Log.i("MainActivity", "Server Punch Response (" + code + "): " + respStr);

                JSONObject respObj = null;
                try {
                    respObj = new JSONObject(respStr);
                } catch (Exception ignored) {}

                if (respObj != null) {
                    boolean isDup = respObj.optBoolean("isDuplicate", false) || "duplicate".equalsIgnoreCase(respObj.optString("status"));
                    boolean isProxy = respObj.optBoolean("isProxyBlocked", false) || "proxy_blocked".equalsIgnoreCase(respObj.optString("status"));
                    String serverMsg = respObj.optString("message", "");

                    if (isProxy) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "⛔ " + serverMsg, Toast.LENGTH_LONG).show());
                        return;
                    }

                    if (isDup) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "⚠️ " + serverMsg, Toast.LENGTH_LONG).show();
                            // Force sync from sheet to correct local button states
                            syncLiveMetadataFromSheet(true);
                        });
                        return;
                    }

                    if ("error".equalsIgnoreCase(respObj.optString("status"))) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ " + serverMsg, Toast.LENGTH_LONG).show());
                        return;
                    }
                }

                if (code == 200 || code == 302) {
                    runOnUiThread(() -> {
                        String timeNow = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date());
                        if ("Check-In".equals(punchType)) {
                            prefs.edit()
                                    .putBoolean("has_checked_in", true)
                                    .putBoolean("has_checked_out", false)
                                    .putString("checkin_time", timeNow)
                                    .apply();
                            Toast.makeText(MainActivity.this, "✅ Check-In Recorded for " + finalEmpName + "!", Toast.LENGTH_SHORT).show();
                            startLocationService();
                        } else {
                            prefs.edit()
                                    .putBoolean("has_checked_in", true)
                                    .putBoolean("has_checked_out", true)
                                    .putString("checkout_time", timeNow)
                                    .apply();
                            Toast.makeText(MainActivity.this, "🏁 Check-Out Recorded! Have a good evening.", Toast.LENGTH_SHORT).show();
                            stopLocationService();
                        }
                        updateUI();
                        syncLiveMetadataFromSheet(false);
        checkForAppUpdate(false);
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "⚠️ Punch response HTTP " + code, Toast.LENGTH_LONG).show());
                }
                conn.disconnect();
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Error saving punch: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void sendManualTelemetryPing() {
        if (!hasLocationPermissions()) {
            requestLocationPermissions();
            return;
        }

        String empId = prefs.getString("locked_emp_id", "");
        if (empId.isEmpty()) {
            Toast.makeText(this, "⚠️ Kripya pehle apna name select karke lock karein!", Toast.LENGTH_SHORT).show();
            return;
        }

        btnManualPing.setEnabled(false);
        tvPingStatus.setText("📡 Acquiring high-accuracy GPS for ping...");

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this, location -> {
                        if (location != null) {
                            currentLat = location.getLatitude();
                            currentLon = location.getLongitude();
                            currentAccuracy = location.getAccuracy();
                            calculateGeofenceDistance();
                            dispatchManualPingHttpRequest(location);
                        } else {
                            tvPingStatus.setText("❌ Failed to get accurate GPS fix");
                            btnManualPing.setEnabled(true);
                        }
                    })
                    .addOnFailureListener(this, e -> {
                        tvPingStatus.setText("❌ GPS fix error: " + e.getMessage());
                        btnManualPing.setEnabled(true);
                    });
        } catch (SecurityException se) {
            tvPingStatus.setText("❌ Location permission missing");
            btnManualPing.setEnabled(true);
        }
    }

    private void dispatchManualPingHttpRequest(Location location) {
        new Thread(() -> {
            try {
                String empId = prefs.getString("locked_emp_id", "");
                String empName = prefs.getString("locked_emp_name", "");
                String webhookUrl = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL);

                ProjectSite site = (ProjectSite) spLocation.getSelectedItem();
                String siteName = (site != null) ? site.name : "Field Duty / On Route";

                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                int batteryPct = (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;

                JSONObject json = new JSONObject();
                json.put("action", "live_ping");
                json.put("empId", empId);
                json.put("empName", empName);
                json.put("lat", location.getLatitude());
                json.put("lon", location.getLongitude());
                json.put("accuracy", "±" + Math.round(location.getAccuracy()) + "m");
                json.put("targetSiteName", siteName);
                json.put("distanceMeters", Math.round(distanceToSite));
                json.put("battery", batteryPct > 0 ? (batteryPct + "%") : "-");
                json.put("remarks", "Manual User Location Ping (Native App)");

                URL url = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                runOnUiThread(() -> {
                    btnManualPing.setEnabled(true);
                    if (code == 200 || code == 302) {
                        String timeNow = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(new Date());
                        tvPingStatus.setText("🟢 Location synced with Google Sheet (" + timeNow + ")");
                        Toast.makeText(MainActivity.this, "✅ Live location coordinates pushed to Live_Tracking sheet!", Toast.LENGTH_SHORT).show();
                    } else {
                        tvPingStatus.setText("⚠️ Server returned HTTP " + code);
                    }
                });
                conn.disconnect();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnManualPing.setEnabled(true);
                    tvPingStatus.setText("❌ Ping sync failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void startLocationService() {
        try {
            if (!hasLocationPermissions()) {
                android.util.Log.w("MainActivity", "Location permission not granted, skipping foreground service start.");
                return;
            }
            Intent serviceIntent = new Intent(this, LocationTrackingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error starting LocationTrackingService: " + e.getMessage());
        }
    }

    private void stopLocationService() {
        Intent serviceIntent = new Intent(this, LocationTrackingService.class);
        stopService(serviceIntent);
    }

    private boolean hasLocationPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                PERMISSION_REQUEST_CODE
        );
    }

    private void checkAndRequestPermissions() {
        if (!hasLocationPermissions()) {
            requestLocationPermissions();
        } else {
            startLiveLocationUpdates();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1002);
            }
        }
    }

    private void startLiveLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(3000)
                .setMinUpdateDistanceMeters(2f)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location loc = locationResult.getLastLocation();
                if (loc != null) {
                    currentLat = loc.getLatitude();
                    currentLon = loc.getLongitude();
                    currentAccuracy = loc.getAccuracy();
                    calculateGeofenceDistance();
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (clockHandler != null && clockRunnable != null) {
            clockHandler.removeCallbacks(clockRunnable);
        }
        if (sheetPollHandler != null && sheetPollRunnable != null) {
            sheetPollHandler.removeCallbacks(sheetPollRunnable);
        }
    }

    // ==============================================================================
    // 4. DYNAMIC LIVE GOOGLE SHEET DATA SYNC (FRESH STAFF_MASTER & SITE_MASTER)
    // ==============================================================================
    private void syncLiveMetadataFromSheet(boolean isManual) {
        new Thread(() -> {
            try {
                String webhookUrl = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL);
                String getUrl = webhookUrl + (webhookUrl.contains("?") ? "&" : "?") + "api=true&_ts=" + System.currentTimeMillis();

                String jsonStr = httpGetWithRedirects(getUrl);
                if (jsonStr == null || jsonStr.isEmpty()) return;

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

                // Fresh staff list starting with clean placeholder
                List<StaffMember> newStaff = new ArrayList<>();
                newStaff.add(new StaffMember("", "-- Choose Your Name --", "Required", ""));

                boolean foundMyAttendanceInSheet = false;
                boolean sheetHasCheckedIn = false;
                boolean sheetHasCheckedOut = false;
                String sheetCheckInTime = "-";
                String sheetCheckOutTime = "-";

                String myEmpId = prefs.getString("locked_emp_id", "");
                String myEmpName = prefs.getString("locked_emp_name", "");

                if (staffArr != null && staffArr.length() > 0) {
                    for (int j = 0; j < staffArr.length(); j++) {
                        JSONObject stObj = staffArr.getJSONObject(j);
                        String stId = stObj.optString("id", "");
                        String stName = stObj.optString("name", "");
                        String stRole = stObj.optString("role", "");
                        String stDept = stObj.optString("dept", "");

                        if (!stId.isEmpty() && !stName.isEmpty()) {
                            newStaff.add(new StaffMember(stId, stName, stRole, stDept));

                            // Reconcile real-time today attendance status for current logged-in user
                            if (!myEmpId.isEmpty() && (stId.equalsIgnoreCase(myEmpId) || stName.equalsIgnoreCase(myEmpName))) {
                                foundMyAttendanceInSheet = true;
                                sheetHasCheckedIn = stObj.optBoolean("hasCheckedIn", false) || stObj.optBoolean("hasPunchedToday", false);
                                sheetHasCheckedOut = stObj.optBoolean("hasCheckedOut", false);
                                sheetCheckInTime = stObj.optString("checkInTime", stObj.optString("punchTime", "-"));
                                sheetCheckOutTime = stObj.optString("checkOutTime", "-");
                            }
                        }
                    }
                    prefs.edit().putString("cached_staff_json", staffArr.toString()).apply();
                }

                // If user is locked, ground truth reconcile with Google Sheet
                if (foundMyAttendanceInSheet) {
                    boolean localCheckedIn = prefs.getBoolean("has_checked_in", false);
                    boolean localCheckedOut = prefs.getBoolean("has_checked_out", false);

                    if (!sheetHasCheckedIn && !sheetHasCheckedOut) {
                        // Google Sheet has NO punches today (e.g. rows deleted or midnight reset)
                        if (localCheckedIn || localCheckedOut) {
                            android.util.Log.i("MainActivity", "🔄 Realtime Sheet Sync: Sheet has no punch records today! Resetting app to Check-In Ready.");
                            prefs.edit()
                                    .putBoolean("has_checked_in", false)
                                    .putBoolean("has_checked_out", false)
                                    .putString("checkin_time", "-")
                                    .putString("checkout_time", "-")
                                    .apply();
                            stopLocationService();
                        }
                    } else if (sheetHasCheckedIn && !sheetHasCheckedOut) {
                        // Sheet confirms staff is ON DUTY
                        prefs.edit()
                                .putBoolean("has_checked_in", true)
                                .putBoolean("has_checked_out", false)
                                .putString("checkin_time", sheetCheckInTime)
                                .apply();
                    } else if (sheetHasCheckedIn && sheetHasCheckedOut) {
                        // Sheet confirms staff has CHECKED OUT
                        prefs.edit()
                                .putBoolean("has_checked_in", true)
                                .putBoolean("has_checked_out", true)
                                .putString("checkin_time", sheetCheckInTime)
                                .putString("checkout_time", sheetCheckOutTime)
                                .apply();
                        stopLocationService();
                    }
                }

                if (settingsObj != null) {
                    int sHour = settingsObj.optInt("startHour", DEFAULT_SHIFT_START_HOUR);
                    int sMin = settingsObj.optInt("startMin", DEFAULT_SHIFT_START_MIN);
                    int cHour = settingsObj.optInt("cutoffHour", DEFAULT_CUTOFF_HOUR);
                    int cMin = settingsObj.optInt("cutoffMin", DEFAULT_CUTOFF_MIN);
                    prefs.edit()
                            .putInt("shift_start_hour", sHour)
                            .putInt("shift_start_min", sMin)
                            .putInt("cutoff_hour", cHour)
                            .putInt("cutoff_min", cMin)
                            .apply();
                }

                final String shiftText = (settingsObj != null && settingsObj.has("shiftText")) ? settingsObj.optString("shiftText") : prefs.getString("shift_display_text", "🕒 Shift: 10:00 AM — 07:00 PM");
                prefs.edit().putString("shift_display_text", shiftText).apply();

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

                    // Update staff dropdown from sheet only if device is not locked
                    if (newStaff.size() > 1 && !prefs.getBoolean("is_device_locked", false)) {
                        staffList.clear();
                        staffList.addAll(newStaff);
                        if (staffAdapter != null) {
                            staffAdapter.notifyDataSetChanged();
                            spStaff.setSelection(0); // Show "-- Choose Your Name --"
                        }
                    }

                    if (tvShiftTag != null) {
                        tvShiftTag.setText(shiftText);
                    }
                    updateUI();

                    // Refresh UI based on updated real-time Sheet state
                    updateUI();

                    if (isManual) {
                        Toast.makeText(this, "✅ Synced with Google Sheet! (" + (staffList.size() - 1) + " Staff Members)", Toast.LENGTH_SHORT).show();
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

    // ==============================================================================
    // IN-APP AUTO-UPDATE ENGINE (GITHUB VERSION CHECKER & APK INSTALLER)
    // ==============================================================================
    public void checkForAppUpdate(final boolean isManual) {
        new Thread(() -> {
            try {
                String updateUrl = prefs.getString("github_update_url", DEFAULT_GITHUB_UPDATE_URL);
                String jsonStr = httpGetWithRedirects(updateUrl);

                if (jsonStr == null || jsonStr.trim().isEmpty()) {
                    String cachedSettings = prefs.getString("cached_sheet_settings", null);
                    if (cachedSettings != null) {
                        JSONObject sObj = new JSONObject(cachedSettings);
                        if (sObj.has("versionCode")) {
                            jsonStr = cachedSettings;
                        }
                    }
                }

                if (jsonStr == null || jsonStr.trim().isEmpty()) {
                    if (isManual) {
                        runOnUiThread(() -> Toast.makeText(this, "⚠️ Update server unreachable. Please verify GitHub URL.", Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                JSONObject obj = new JSONObject(jsonStr);
                int remoteVersionCode = obj.optInt("versionCode", obj.optInt("latestVersion", 1));
                String remoteVersionName = obj.optString("versionName", "2.0.0");
                String apkUrl = obj.optString("apkUrl", obj.optString("downloadUrl", ""));
                boolean forceUpdate = obj.optBoolean("forceUpdate", false);
                String releaseNotes = obj.optString("releaseNotes", obj.optString("message", "वास्सा प्रॉपर्टीज ॲपचे नवीन अपडेट उपलब्ध आहे."));

                if (remoteVersionCode > CURRENT_APP_VERSION_CODE && apkUrl != null && !apkUrl.isEmpty()) {
                    runOnUiThread(() -> showUpdateDialog(remoteVersionCode, remoteVersionName, apkUrl, forceUpdate, releaseNotes));
                } else if (isManual) {
                    runOnUiThread(() -> Toast.makeText(this, "✅ ॲप आधीच अद्ययावत आहे (v" + CURRENT_APP_VERSION_NAME + ")", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                if (isManual) {
                    runOnUiThread(() -> Toast.makeText(this, "Update check error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    private void showUpdateDialog(int remoteVerCode, String remoteVerName, String apkUrl, boolean forceUpdate, String releaseNotes) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🚀 नवीन ॲप अपडेट उपलब्ध (v" + remoteVerName + ")");
        builder.setMessage(releaseNotes + "\n\nवास्सा प्रॉपर्टीजचे नवीन अपडेट स्थापित करण्यासाठी खालील बटण दाबा.");
        builder.setCancelable(!forceUpdate);

        builder.setPositiveButton("✅ Update Now (डाउनलोड करा)", (dialog, which) -> {
            downloadAndInstallApk(apkUrl, remoteVerName);
        });

        if (!forceUpdate) {
            builder.setNegativeButton("नंतर (Later)", (dialog, which) -> dialog.dismiss());
        }

        builder.show();
    }

    private void downloadAndInstallApk(final String apkUrl, final String targetVer) {
        final AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("⏳ नवीन ॲप डाउनलोड होत आहे...")
                .setMessage("कृपया प्रतीक्षा करा, नवीन ॲप डाउनलोड होत आहे...\n0%")
                .setCancelable(false)
                .create();
        progressDialog.show();

        new Thread(() -> {
            try {
                File targetDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (targetDir == null) {
                    targetDir = getFilesDir();
                }
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }

                File apkFile = new File(targetDir, "Vassa_Attendance_Update.apk");
                if (apkFile.exists()) {
                    apkFile.delete();
                }

                URL url = new URL(apkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(20000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int respCode = conn.getResponseCode();
                if (respCode == HttpURLConnection.HTTP_MOVED_TEMP || respCode == HttpURLConnection.HTTP_MOVED_PERM || respCode == 307 || respCode == 308) {
                    String redirUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    url = new URL(redirUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(20000);
                    conn.connect();
                }

                int fileLength = conn.getContentLength();
                InputStream input = conn.getInputStream();
                FileOutputStream output = new FileOutputStream(apkFile);

                byte[] data = new byte[8192];
                long total = 0;
                int count;
                long lastUpdateUI = System.currentTimeMillis();

                while ((count = input.read(data)) != -1) {
                    total += count;
                    output.write(data, 0, count);

                    if (fileLength > 0 && (System.currentTimeMillis() - lastUpdateUI > 300)) {
                        lastUpdateUI = System.currentTimeMillis();
                        final int progress = (int) (total * 100 / fileLength);
                        runOnUiThread(() -> progressDialog.setMessage("कृपया प्रतीक्षा करा, नवीन ॲप डाउनलोड होत आहे...\n" + progress + "%"));
                    }
                }

                output.flush();
                output.close();
                input.close();
                conn.disconnect();

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    promptInstallApk(apkFile);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "❌ डाउनलोड त्रुटी: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void promptInstallApk(File apkFile) {
        if (!apkFile.exists()) {
            Toast.makeText(this, "❌ APK फाईल सापडली नाही", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    new AlertDialog.Builder(this)
                            .setTitle("🔐 परमिशन आवश्यक (Install Permission)")
                            .setMessage("ॲप स्वयंचलितपणे अपडेट करण्यासाठी कृपया 'Allow from this source' सुरू करा.")
                            .setPositiveButton("Settings उघडा", (dialog, which) -> {
                                Intent settingsIntent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                                settingsIntent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(settingsIntent);
                            })
                            .setNegativeButton("रद्द करा", null)
                            .show();
                    return;
                }
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", apkFile);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);

        } catch (Exception e) {
            Toast.makeText(this, "इन्स्टॉलेशन एरर: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

}
