# HTTP-level regression checks for the settings write path (issues #1 and #9).
# Run against a server started with DAKBOARD_ADMIN_PASSWORD and DAKBOARD_EDITOR_PASSWORD set.
# Only use a test instance: this script changes the platform settings it runs against.
#
#   powershell -ExecutionPolicy Bypass -File tests\settings-regression.ps1 `
#     -BaseUrl http://127.0.0.1:8080 -AdminPassword '...' -EditorPassword '...'

param(
  [Parameter(Mandatory = $true)][string]$BaseUrl,
  [Parameter(Mandatory = $true)][string]$AdminPassword,
  [Parameter(Mandatory = $true)][string]$EditorPassword
)

$script:failures = 0

function Check($name, $ok) {
  if ($ok) { Write-Host "PASS $name" } else { Write-Host "FAIL $name"; $script:failures++ }
}

function Login($user, $pass) {
  $r = Invoke-WebRequest -Uri "$BaseUrl/api/event-board/login" -Method POST `
    -Body "username=$user&password=$pass" -ContentType "application/x-www-form-urlencoded" `
    -UseBasicParsing -SessionVariable s
  $csrf = (Invoke-RestMethod -Uri "$BaseUrl/api/event-board/session" -WebSession $s).csrfToken
  return @{ Session = $s; Csrf = $csrf; Status = $r.StatusCode }
}

function PostRaw($sess, $rawJson) {
  try {
    $r = Invoke-WebRequest -Uri "$BaseUrl/api/event-board/settings" -Method POST -Body $rawJson `
      -ContentType "application/json" -Headers @{ "X-CSRF-Token" = $sess.Csrf } `
      -WebSession $sess.Session -UseBasicParsing
    return [int]$r.StatusCode
  } catch {
    return [int]$_.Exception.Response.StatusCode
  }
}

function StoredSettings($admin) {
  return Invoke-RestMethod -Uri "$BaseUrl/api/event-board/admin/settings" -WebSession $admin.Session
}

$admin = Login "admin" $AdminPassword
Check "admin login" ($admin.Status -eq 200)
$ORIGINAL_URL = "https://original.example/feed"
$setBase = @{ outlookIcsUrl = $ORIGINAL_URL; mainOutput = "board" } | ConvertTo-Json -Compress
Check "admin baseline save" ((PostRaw $admin $setBase) -eq 200)

$editor = Login "editor" $EditorPassword
Check "editor login" ($editor.Status -eq 200)

$dup = '{"outlookIcsUrl":"https://decoy.example/feed","outlookIcsUrl":"https://attacker.example/feed","mainOutput":"slides","mainOutput":"room"}'
$code = PostRaw $editor $dup
$stored = StoredSettings $admin
Check "editor duplicate-key save is rejected (400)" ($code -eq 400)
Check "duplicate-key save leaves calendar URL unchanged" ($stored.outlookIcsUrl -eq $ORIGINAL_URL)
Check "duplicate-key save leaves mainOutput unchanged" ($stored.mainOutput -eq "board")

$escaped = '{"outlook\u0049csUrl":"https://attacker.example/feed","mainOutput":"room"}'
$code = PostRaw $editor $escaped
$stored = StoredSettings $admin
Check "editor escaped-key save is rejected (400)" ($code -eq 400)
Check "escaped-key save leaves calendar URL unchanged" ($stored.outlookIcsUrl -eq $ORIGINAL_URL)
Check "escaped-key save leaves mainOutput unchanged" ($stored.mainOutput -eq "board")

$control = '{"ticker":"EDITOR REGRESSION TICKER","mainOutput":"room","outlookIcsUrl":"https://attacker.example/feed"}'
$code = PostRaw $editor $control
$stored = StoredSettings $admin
Check "editor normal save still accepted (200)" ($code -eq 200)
Check "editor ticker change still saved" ($stored.ticker -eq "EDITOR REGRESSION TICKER")
Check "editor single-key restricted fields still preserved" (($stored.mainOutput -eq "board") -and ($stored.outlookIcsUrl -eq $ORIGINAL_URL))

$adminEdit = '{"ticker":"ADMIN REGRESSION TICKER","mainOutput":"schedule"}'
$code = PostRaw $admin $adminEdit
$stored = StoredSettings $admin
Check "admin save still accepted (200)" ($code -eq 200)
Check "admin can change mainOutput" ($stored.mainOutput -eq "schedule")

if ($script:failures -gt 0) { Write-Host "$script:failures check(s) failed"; exit 1 }
Write-Host "all checks passed"
