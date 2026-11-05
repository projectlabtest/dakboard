@echo off
REM Event Board - double-click to start
cd /d "%~dp0"
if not defined EVENT_BOARD_ADMIN_PASSWORD if defined DAKBOARD_ADMIN_PASSWORD set "EVENT_BOARD_ADMIN_PASSWORD=%DAKBOARD_ADMIN_PASSWORD%"
if not defined EVENT_BOARD_ADMIN_USERNAME if defined DAKBOARD_ADMIN_USERNAME set "EVENT_BOARD_ADMIN_USERNAME=%DAKBOARD_ADMIN_USERNAME%"
if exist "admin-credential.json" (
  echo Using the password you set from the admin page (Change Password).
) else (
  if not defined EVENT_BOARD_ADMIN_PASSWORD set /p EVENT_BOARD_ADMIN_PASSWORD=Event Board admin password (12+ characters): 
  if not defined EVENT_BOARD_ADMIN_PASSWORD (
    echo Password is required.
    pause
    exit /b 1
  )
)
if not defined EVENT_BOARD_ADMIN_USERNAME set "EVENT_BOARD_ADMIN_USERNAME=admin"
javac EventBoard.java
java EventBoard 8080
pause
