# Build and install CanvasFrame on a rooted LG webOS TV (Homebrew Channel + SSH as root).
#   ./webos/deploy.ps1 -Tv 192.168.1.50
#   ./webos/deploy.ps1                    # the TV in .secrets/default-lg.txt (local, not in git), if that file exists
# Installs the app package, the boot hook (/var/lib/webosbrew/init.d/60-canvasframe) and restarts the server.
param([string]$Tv = "")
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Tv) {
    $default = Join-Path (Split-Path -Parent $here) ".secrets/default-lg.txt"
    if (Test-Path $default) { $Tv = (Get-Content $default -Raw).Trim() }
    if (-not $Tv) { throw "Give the TV's address: ./webos/deploy.ps1 -Tv <address>" }
}

python "$here/build-ipk.py"
if ($LASTEXITCODE) { throw "build failed" }
$ver = (Get-Content "$here/app/appinfo.json" -Raw | ConvertFrom-Json).version
$ipk = Get-Item "$here/build/com.canvasframe.home_${ver}_all.ipk"
$door = Get-Item "$here/build/com.canvasframe.door_${ver}_all.ipk"   # the doorbell card over other apps (overlay window)

Copy-Item $door.FullName "$here/build/tmp/canvasframe-door.ipk" -Force
scp -q $ipk.FullName "$here/build/tmp/canvasframe-door.ipk" "$here/tools/remote-install.js" "$here/server/boot-hook.sh" "root@${Tv}:/tmp/"
if ($LASTEXITCODE) { throw "copy failed" }

$remote = @'
set -e
mv /tmp/com.canvasframe.home_*.ipk /tmp/canvasframe.ipk 2>/dev/null || true
node /tmp/remote-install.js /tmp/canvasframe.ipk
node /tmp/remote-install.js /tmp/canvasframe-door.ipk com.canvasframe.door
cp /tmp/boot-hook.sh /var/lib/webosbrew/init.d/60-canvasframe
chmod 755 /var/lib/webosbrew/init.d/60-canvasframe
rm -f /tmp/canvasframe.ipk /tmp/canvasframe-door.ipk /tmp/remote-install.js /tmp/boot-hook.sh
APP=/media/developer/apps/usr/palm/applications/com.canvasframe.home
alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }
OLD=$(cat /tmp/canvasframe.pid 2>/dev/null || true)
[ -n "$OLD" ] && kill "$OLD" 2>/dev/null || true
# The watchdog restarts the server (with the new code) within ~20 s; start the watchdog if it isn't running.
# Never start a second copy here: a new server replaces the old one, cutting off requests in flight.
alive /tmp/canvasframe-watch.pid || /var/lib/webosbrew/init.d/60-canvasframe
for i in $(seq 1 45); do
  NEW=$(cat /tmp/canvasframe.pid 2>/dev/null || true)
  [ -n "$NEW" ] && [ "$NEW" != "$OLD" ] && alive /tmp/canvasframe.pid && break
  sleep 1
done
echo "server pid $(cat /tmp/canvasframe.pid 2>/dev/null)"
'@
# Sent as base64: piping text to ssh from Windows PowerShell 5.1 adds a byte-order mark (sh then fails on line 1).
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($remote -replace "`r", "")))
ssh "root@$Tv" "echo $b64 | base64 -d | sh"
if ($LASTEXITCODE) { throw "install failed" }
Write-Host "Deployed to $Tv (dashboard at https://${Tv}:9443, or :9444 while Glasshouse is active; plain http://${Tv}:8080 redirects there)"
