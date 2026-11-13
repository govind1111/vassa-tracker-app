@echo off
REM ============================================================
REM  Vassa Attendance - Android APK Auto-Build & GitHub Release
REM  Is file par double-click karein -> APK auto build ho jayega
REM ============================================================
setlocal
set "PATH=E:\Projects\Attendnce\tools\node;%PATH%"

echo.
echo  [1/2] Syncing Android files to govind1111/vassa-tracker-app...
node "E:\Projects\Attendnce\tools\sync_android_repo.js"

echo.
echo  [2/2] GitHub Actions Cloud APK Build Triggered!
echo  ============================================================
echo   APK Build & Release Status check karne ke liye:
echo   Repo    : https://github.com/govind1111/vassa-tracker-app
echo   Actions : https://github.com/govind1111/vassa-tracker-app/actions
echo   Releases: https://github.com/govind1111/vassa-tracker-app/releases
echo  ============================================================
echo.
pause
