# Vassa Properties Android Native App — Changelog & Fixes

### 1. Compile Error Fix (`MainActivity.java:579`)
- **Root Cause:** A syntax typo with unescaped double quotes `""isCutoff":true"` caused `error: ';' expected` in GitHub Actions task `:app:compileDebugJavaWithJavac`.
- **Solution:** Replaced with clean token checks:
  ```java
  if (serverResp.contains("isCutoff") || serverResp.contains("isProxyBlocked")) {
  ```
- **Outcome:** Clean Java compilation with zero syntax errors.

### 2. Live Dynamic `Site_Master` GPS Auto-Sync
- Added `syncLiveMetadataFromSheet(boolean isManual)` via HTTP background fetch to Apps Script endpoint (`?api=true`).
- Caches sites in `cached_sites_json` (SharedPreferences) and populates the location spinner dynamically.
- When you update site coordinates or add new project sites in Google Sheet `Site_Master`, the app updates automatically on launch, resume, and on tapping the GPS Radar card.

### 3. Dashboard Shift Timings (up to 11:59 PM)
- Extended Shift End dropdown options up to `11:59 PM (Late Night / Testing)`.
- Cutoff inputs allow up to `23:59` to test check-in without hitting evening cutoff barriers.
