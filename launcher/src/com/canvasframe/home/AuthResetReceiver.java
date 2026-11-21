package com.canvasframe.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Lock-out recovery: removes every dashboard authenticator and session.
 * Protected by android.permission.DUMP, which ADB's shell holds and ordinary apps can't get:
 * {@code adb shell am broadcast -f 32 -n com.canvasframe.home/.AuthResetReceiver}
 */
public class AuthResetReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Auth.reset(context);
        Site.authReset(context);   // remove the shared authenticators on every TV too, or they'd come back
        setResultData("CanvasFrame dashboard sign-in reset: all authenticators removed");
    }
}
