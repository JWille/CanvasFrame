package com.canvasframe.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real backlight dimming on Hisense TVs, used by the CanvasArt screen saver. Android's own brightness does nothing on
 * these panels; the backlight is Hisense's picture setting, changed through Hsp (run in ADB's shell, see there).
 *
 * The Hisense keeps one backlight per input; while CanvasArt runs the apps' setting is the active one, and that is what
 * gets dimmed (the PC's HDMI setting is never touched). It is saved before the first change and put back afterwards.
 * The saved value stays in prefs until restored, so if the app was killed HomeActivity puts it back when it next opens.
 * Everything runs on one background thread, in order; each change takes about a second (a short-lived process).
 */
final class Backlight {
    private Backlight() { }

    private static final String SAVED = "backlight_saved";   // in canvasframe_state: the user's backlight while dimmed
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> new Thread(r, "backlight"));
    private static final AtomicInteger GEN = new AtomicInteger();   // bumped by restore(): stops a fade in progress
    private static AdbClient adb;
    private static int current = -1;
    /** null = not tried yet, true/false = whether this TV lets us change the backlight. */
    private static volatile Boolean supported;

    private static SharedPreferences state(Context c) {
        return c.getApplicationContext().getSharedPreferences("canvasframe_state", Context.MODE_PRIVATE);
    }

    /** Runs Hsp in ADB's shell; returns the backlight it reports, or -1. */
    private static int hsp(Context c, String args) {
        try {
            if (adb == null) adb = new AdbClient(c.getApplicationContext(), "127.0.0.1", 5555);
            String apk = c.getApplicationInfo().sourceDir.replace("'", "");
            String out = adb.shell("BOOTCLASSPATH=$BOOTCLASSPATH:/system/framework/com.hsp.rod.jar CLASSPATH='" + apk
                    + "' app_process /system/bin com.canvasframe.home.Hsp " + args + " 2>&1").trim();
            int i = out.lastIndexOf("OK ");
            if (i < 0) return -1;
            return Integer.parseInt(out.substring(i + 3).split("\\s")[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Whether a dimmed backlight is waiting to be put back. */
    static boolean pending(Context c) { return state(c).contains(SAVED); }

    /**
     * Starts dimming: remembers the user's backlight (unless an earlier one is still saved, which is then the real
     * value). Calls back on the background thread with whether the backlight can be changed on this TV.
     */
    static void begin(Context c, java.util.function.Consumer<Boolean> done) {
        final int gen = GEN.get();
        EXEC.execute(() -> {
            boolean ok = false;
            if (!Boolean.FALSE.equals(supported) && gen == GEN.get()) {
                int now = hsp(c, "get");
                supported = now >= 0;
                if (now >= 0) {
                    current = now;
                    if (!pending(c)) state(c).edit().putInt(SAVED, now).commit();
                    ok = true;
                }
            }
            done.accept(ok);
        });
    }

    /**
     * Moves the backlight to the user's level made darker by {@code dim} (0 = their level, 0.4 = 40 % lower),
     * in steps of at most 8 so it isn't a jump. Does nothing unless begin() has saved a level.
     */
    static void dimTo(Context c, float dim) {
        final int gen = GEN.get();
        EXEC.execute(() -> {
            SharedPreferences st = state(c);
            if (!st.contains(SAVED) || gen != GEN.get()) return;
            int base = st.getInt(SAVED, 50);
            int target = Math.max(0, Math.round(base * (1f - Math.max(0f, Math.min(1f, dim)))));
            if (current < 0) current = base;
            while (current != target && gen == GEN.get()) {
                int next = current < target ? Math.min(target, current + 8) : Math.max(target, current - 8);
                int got = hsp(c, "set " + next);
                if (got < 0) return;
                current = got;
            }
        });
    }

    /** Puts the user's backlight back (if a dimmed one is pending). Safe to call any time. */
    static void restore(Context c) {
        GEN.incrementAndGet();
        EXEC.execute(() -> {
            SharedPreferences st = state(c);
            if (!st.contains(SAVED)) return;
            int base = st.getInt(SAVED, 50);
            if (hsp(c, "set " + base) == base) {
                current = base;
                st.edit().remove(SAVED).commit();
            }
        });
    }
}
