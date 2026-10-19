# Vassa Properties — Native Android Real-Time Attendance & Live GPS Tracking App

## Overview
This is the complete **Native Android Studio Project** with **24/7 Background Foreground Service** for real-time field duty tracking.

### Key Capabilities:
1. **Screen-Off / Pocket Background Tracking:** Unlike browser PWAs, Android Native Services run continuously even when the screen is locked or the phone is in the staff member's pocket.
2. **Google Play Services FusedLocationProvider:** High-precision battery-optimized GPS updates every 5 minutes.
3. **Automatic Reboot Recovery:** If the phone is restarted, the `BootCompletedReceiver` automatically revives the tracking service.
4. **Direct Google Sheet Webhook Sync:** No external paid cloud subscription required. Pushes directly to the `Live_Tracking` sheet tab.

---

## 2-Step Build Instructions (How to generate APK):

1. **Open in Android Studio:**
   - Download and unzip `Vassa_Attendance_Android_Native_App.zip`.
   - Open Android Studio -> Click **Open Existing Project** -> Select the unzipped folder.

2. **Build APK:**
   - In Android Studio top menu: Click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
   - Android Studio will generate `app-debug.apk` in `app/build/outputs/apk/debug/`.
   - Send this `.apk` to staff phones via WhatsApp or Google Drive.
   - Staff installs it, enters their Name & Emp ID, and taps **"Check-In & Start Duty"**!
