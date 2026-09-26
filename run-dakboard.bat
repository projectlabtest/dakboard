@echo off
REM LIFE HOPE CENTER DAKboard (Java) - double-click to start
cd /d "%~dp0"
if exist "admin-credential.json" (
  echo Using the password you set from the admin page (Change Password).
) else (
  if not defined DAKBOARD_ADMIN_PASSWORD set /p DAKBOARD_ADMIN_PASSWORD=DAKboard admin password (12+ characters): 
  if not defined DAKBOARD_ADMIN_PASSWORD (
    echo Password is required.
    pause
    exit /b 1
  )
)
if not defined DAKBOARD_ADMIN_USERNAME set "DAKBOARD_ADMIN_USERNAME=admin"
javac DakBoard.java
java DakBoard 8080
pause
