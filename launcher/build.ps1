# Builds CanvasFrame.apk with the bare Android SDK tools (no Gradle).
$ErrorActionPreference = 'Stop'
$here  = $PSScriptRoot
$sdk   = "$env:LOCALAPPDATA\Android\Sdk"
$bt    = "$sdk\build-tools\35.0.0"
$jar   = "$sdk\platforms\android-31\android.jar"
$out   = "$here\build"

# Gate: the dashboard's (and LG pages') scripts must parse; one stray quote blanks the whole dashboard.
if (Get-Command node -ErrorAction SilentlyContinue) {
    node (Join-Path $here '..\tools\dash-check\dash-check.mjs') --static
    if ($LASTEXITCODE) { throw 'dashboard script check failed (see above)' }
} else { Write-Warning 'node not found: dashboard script check skipped' }

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\res","$out\classes","$out\dex" | Out-Null

# Assets: the dashboard etc., plus CanvasFrame Link for Windows (pc/), which the dashboard offers as a download.
# The PC app is built first when the .NET SDK is here; otherwise an existing pc\dist build is used, or none.
$pc = Join-Path $here '..\pc'
if (Get-Command dotnet -ErrorAction SilentlyContinue) { & (Join-Path $pc 'build.ps1') | Out-Null }
Copy-Item -Recurse "$here\assets" "$out\assets"
$pcExe = Join-Path $pc 'dist\CanvasFrameLink.exe'
if (Test-Path $pcExe) {
    Copy-Item $pcExe "$out\assets\CanvasFrameLink.exe"
    $ver = (Get-Item $pcExe).VersionInfo.ProductVersion -replace '\+.*$', ''
    [IO.File]::WriteAllText("$out\assets\CanvasFrameLink.json", (@{ version = $ver; name = 'CanvasFrame Link' } | ConvertTo-Json -Compress))
} else { Write-Warning 'pc\dist\CanvasFrameLink.exe not found: the APK will not offer CanvasFrame Link' }

& "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"
& "$bt\aapt2.exe" link -o "$out\unsigned.apk" -I $jar --manifest "$here\AndroidManifest.xml" `
    --java "$out\gen" "$out\res.zip" --auto-add-overlay -A "$out\assets"
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$srcs = Get-ChildItem -Recurse "$here\src","$out\gen" -Filter *.java | ForEach-Object FullName
& javac --release 8 -classpath $jar -d "$out\classes" -Xlint:-options @srcs
if ($LASTEXITCODE) { throw 'javac failed' }

& jar -cf "$out\classes.jar" -C "$out\classes" .
if ($LASTEXITCODE) { throw 'jar (classes) failed' }
& "$bt\d8.bat" --lib $jar --min-api 26 --output "$out\dex" "$out\classes.jar"
if ($LASTEXITCODE) { throw 'd8 failed' }

Copy-Item "$out\unsigned.apk" "$out\with-dex.apk"
# jar sometimes fails to move its temp file (OneDrive sync locks it); retry, and never sign an APK without code.
Push-Location "$out\dex"
for ($try = 1; $try -le 3; $try++) { & jar -uf "$out\with-dex.apk" classes.dex; if (-not $LASTEXITCODE) { break }; Start-Sleep 2 }
Pop-Location
if ($LASTEXITCODE) { throw 'adding classes.dex failed' }

& "$bt\zipalign.exe" -f 4 "$out\with-dex.apk" "$out\aligned.apk"

$ks = "$here\canvasframe.keystore"
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -storepass canvasframe -keypass canvasframe -alias cf `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=CanvasFrame" | Out-Null
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:canvasframe --out "$here\CanvasFrame.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }
Write-Host "Built $here\CanvasFrame.apk"
