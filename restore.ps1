# Undo debloat.ps1
$pkgs = 'com.vt.tv.evservice','com.vidaa.oemtabcontentprovider','net.vidaatv.hisense.android.tv',
        'android.autoinstalls.config.google.gtvpai','com.vt.storemode','tv.mopa.ginga',
        'com.vt.hishow','com.vt.alexa','com.vt.tv.b2b',
        'com.vt.arttv.artmode/com.vt.arttv.upgrade_image.service.UpgradeImageService'
foreach ($p in $pkgs) { adb shell pm enable $p }
# Give Home back to Google TV
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
