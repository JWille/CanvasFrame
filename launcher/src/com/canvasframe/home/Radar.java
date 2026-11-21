package com.canvasframe.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Presence from the Hisense art-mode radar, while any input is on screen.
 *
 * Hisense's ArtModeService (com.vt.arttv.artmode, exported, no permission) subscribes to the radar only while it has a
 * Messenger client: the art-mode activity normally, or us. We register with msg 1 (MSG_REGISTER_CLIENT, replyTo = ours),
 * ignore everything it sends back (3 = go silent, 4 = reboot request: the art-mode activity would act on them, we never do),
 * and leave with msg 8 (MSG_CANCEL_TASK, clears its power state + reboot flags) then msg 2 (unregister). The readings only
 * reach its log ("radarStatusP SOMEONE" / "radarStatusP UNMANNED", every ~2.4 s, tag ArtModeService at level I), so we read
 * them with logcat over our ADB shell after lifting that tag past the TV's E-only filter.
 *
 * Rules (from the decompiled service): one client slot, so never register while art mode itself is up (it would lose its
 * radar); registration is ignored while the screen is off; at 9:00-9:59 with uptime over 30 days its reboot check saves a
 * flag that makes art mode open at the next power-on, so we step out for that hour once uptime passes 29 days.
 */
final class Radar {
    interface Shell { String run(String cmd); }

    static final String PREF = "radar";
    private static final String ART = "com.vt.arttv.artmode";
    private static final String TAG = "CanvasFrame";

    // Shared state for siteEnv / the dashboard.
    static volatile String state = "unknown";   // someone | nobody | unknown
    static volatile long since, lastReport;      // ms: when the state last changed, newest radar report
    static volatile String mode = "off";         // off | starting | ours | artmode | paused | unavailable

    private final Context ctx;
    private final Shell sh;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private Messenger art;
    private boolean bound, registered;
    private long registeredAt, lastCheck, lastActivity;
    private double logFrom;                      // epoch seconds of the newest log line read
    private boolean tagSet;

    private final Messenger me = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message m) {
            Log.e(TAG, "radar: art-mode service sent " + m.what + " (ignored)");
        }
    });
    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            art = new Messenger(b);
            timer.execute(Radar.this::tick);
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            art = null;
            registered = false;
        }
    };

    Radar(Context c, Shell sh) { this.ctx = c.getApplicationContext(); this.sh = sh; }

    static boolean available(Context c) {
        if (!"hisense".equals(DashboardService.brand())) return false;
        try { c.getPackageManager().getPackageInfo(ART, 0); return true; } catch (Exception e) { return false; }
    }

    static boolean enabled(Context c) { return Config.prefs(c).getBoolean(PREF, true); }

    void start() {
        if (!available(ctx)) { mode = "unavailable"; return; }
        logFrom = System.currentTimeMillis() / 1000.0 - 5;
        timer.scheduleWithFixedDelay(() -> {
            try { tick(); } catch (Throwable t) { Log.e(TAG, "radar: " + t); }
        }, 10, 3, TimeUnit.SECONDS);
    }

    void stop() {
        timer.execute(this::leave);
        timer.shutdown();
    }

    /** Every 3 s: keep (or drop) our registration, then read the new radar lines. */
    private void tick() {
        long now = System.currentTimeMillis();
        boolean on = ctx.getSystemService(PowerManager.class).isInteractive();
        if (!enabled(ctx)) { leave(); mode = "off"; state = "unknown"; return; }
        if (!on) { registered = false; mode = "paused"; }   // the service drops registrations it gets with the screen off
        if (on && now - lastCheck > 15_000) {
            lastCheck = now;
            check(now);
        }
        read(now);
        if (now - lastReport > 30_000 && !"unknown".equals(state)) { state = "unknown"; since = now; }
    }

    /** Decide whether we should be the radar client, and (re)register if the radar has gone quiet. */
    private void check(long now) {
        if (!tagSet) {
            String v = sh.run("getprop log.tag.ArtModeService");
            if (v == null || !v.trim().equals("V")) sh.run("setprop log.tag.ArtModeService V");
            tagSet = v != null;
        }
        String top = sh.run("dumpsys activity activities | grep -m1 mResumedActivity");
        if (top != null && top.contains(ART + "/")) {   // art mode is up and is the client itself: never take its slot
            registered = false;
            mode = "artmode";
            return;
        }
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour == 9 && SystemClock.elapsedRealtime() > 29L * 24 * 3600_000) {
            leave();
            mode = "paused";
            return;
        }
        boolean quiet = now - lastReport > 12_000;
        if (registered && !(quiet && now - registeredAt > 12_000)) return;
        if (art == null) {
            if (!bound) {
                Intent i = new Intent("com.vt.arttv.artmode.action.ART_SERVICE").setPackage(ART);
                bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
                mode = "starting";
                if (!bound) mode = "unavailable";
            }
            return;   // onServiceConnected runs tick() again
        }
        if (send(1)) { registered = true; registeredAt = now; mode = "ours"; }
    }

    private boolean send(int what) {
        Messenger a = art;
        if (a == null) return false;
        try {
            Message m = Message.obtain(null, what);
            m.replyTo = me;
            a.send(m);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "radar: send " + what + " failed: " + e);
            return false;
        }
    }

    /** Step out cleanly: cancel (clears its power state and reboot flags), unregister, unbind. */
    private void leave() {
        if (registered) { send(8); send(2); }
        registered = false;
        if (bound) {
            try { ctx.unbindService(conn); } catch (Exception ignored) { }
            bound = false;
            art = null;
        }
    }

    /** New "radarStatusP" lines since the last read (also art mode's own, while it is up). */
    private void read(long now) {
        String out = sh.run(String.format(java.util.Locale.US,
                "logcat -d -v epoch -T %.3f -s ArtModeService:I | grep radarStatusP", logFrom));
        if (out == null) return;
        for (String line : out.split("\n")) {
            line = line.trim();
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            double t;
            try { t = Double.parseDouble(line.substring(0, sp)); } catch (NumberFormatException e) { continue; }
            if (t <= logFrom) continue;
            logFrom = t;
            String s = line.contains("SOMEONE") ? "someone" : line.contains("UNMANNED") ? "nobody" : null;
            if (s == null) continue;
            long at = (long) (t * 1000);
            lastReport = Math.max(lastReport, at);
            boolean changed = !s.equals(state);
            if (changed) {
                Log.e(TAG, "radar: " + s);
                state = s;
                since = at;
                Signals.publish(ctx, true);   // presence changes reach the other TVs (and their timelines) at once
            }
            // Someone in the room counts as activity for the shared site (nearby screen savers, room presence):
            // at once when someone arrives, then once a minute while they stay.
            if ("someone".equals(s) && (changed || now - lastActivity > 60_000)) {
                lastActivity = now;
                Site.activity(ctx, "presence");
            }
        }
    }

    /** For siteEnv and the dashboard: {state, since, at, src, mode}; null while the radar has nothing to say. */
    static JSONObject json() throws JSONException {
        if ("unavailable".equals(mode) || "off".equals(mode)) return null;
        JSONObject o = new JSONObject().put("state", state).put("src", "radar").put("mode", mode);
        if (since > 0) o.put("since", since);
        if (lastReport > 0) o.put("at", lastReport);
        return o;
    }
}
