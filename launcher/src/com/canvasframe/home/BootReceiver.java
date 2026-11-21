package com.canvasframe.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the dashboard when the TV boots. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startForegroundService(new Intent(context, DashboardService.class));
    }
}
