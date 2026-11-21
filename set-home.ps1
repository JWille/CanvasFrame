# Make CanvasFrame the Home screen (reversible with restore.ps1).
# 1. Google TV's launcher has Home priority 2, so it must be disabled.
# 2. Google's setup app then claims Home with a "recovery" screen (priority 1) that pushes you back
#    to Google TV, so it is disabled too. It's only needed during first-time setup after a factory reset.
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity com.canvasframe.home/.HomeActivity
adb shell input keyevent KEYCODE_HOME
