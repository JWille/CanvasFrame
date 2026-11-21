# Hisense CanvasTV debloat (reversible). Run from PowerShell with the TV connected via adb.
# Undo everything with restore.ps1.
$pkgs = @(
  'com.vt.tv.evservice',                      # ACR viewing tracker
  'com.vidaa.oemtabcontentprovider',          # Hisense promo tiles on Google TV home
  'net.vidaatv.hisense.android.tv',           # VIDAA free ad channels
  'android.autoinstalls.config.google.gtvpai',# silent promoted-app installs
  'com.vt.storemode',                         # retail demo
  'tv.mopa.ginga',                            # Brazil broadcast interactive
  'com.vt.hishow',                            # Family Moment
  'com.vt.alexa',                             # HiAlexa
  'com.vt.tv.b2b'                             # hotel/business mode
)
foreach ($p in $pkgs) { Write-Host -NoNewline "$p : "; adb shell pm disable-user --user 0 $p }

# Stop art mode's "download new art" prompt by disabling only its update service.
$comp = 'com.vt.arttv.artmode/com.vt.arttv.upgrade_image.service.UpgradeImageService'
Write-Host "--- $comp"
adb shell pm disable $comp
