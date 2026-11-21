package com.canvasframe.home;

/**
 * Command-line helper that talks to Hisense's HSP service ("vt_hs_av_service") to read or set the panel backlight.
 * It runs in ADB's shell, not in the app: SELinux lets the shell reach the service but blocks ordinary apps.
 * Backlight starts it over its ADB connection:
 *
 *   BOOTCLASSPATH=$BOOTCLASSPATH:/system/framework/com.hsp.rod.jar CLASSPATH=<this APK> \
 *       app_process /system/bin com.canvasframe.home.Hsp get | set <0-100> | light | pic [<mode>]
 *
 * pic: the picture mode of the input on screen (GetItem/SetItem "pic_mode"; per input, like the backlight). SDR numbers
 * on this US model, from TV Settings' ModeEntryProvider: 0 Vivid, 1 Standard, 2 Energy Saving, 3 Theater Day, 4 PC/Game,
 * 5 Sports, 6 Theater Night, 7 Filmmaker Mode (HDR / Dolby Vision signals use their own titles for the same numbers).
 *
 * Prints "OK <backlight>" (light: "OK <light sensor reading>") or "ERR <reason>". Hisense's library is used by reflection, so the app builds without it.
 * Only java.* classes here: no Android context exists in that process.
 */
public final class Hsp {
    private Hsp() { }

    private static Class<?> rod, pool, arch;

    private static Object archive() throws Exception {
        Object a = pool.getMethod("getArchive").invoke(pool.getMethod("getInstance").invoke(null));
        arch.getMethod("renew").invoke(a);
        return a;
    }

    /** Rod.callWithArchive(3, name, in, out, 2000) with an optional JSON string in; returns the answer as a string. */
    private static String call(String name, String json) throws Exception {
        Object in = archive(), out = archive();
        if (json != null) arch.getMethod("put", String.class).invoke(in, json);
        int rc = (Integer) rod.getMethod("callWithArchive", int.class, String.class, arch, arch, int.class)
                .invoke(null, 3, name, in, out, 2000);
        if (rc != 0) throw new IllegalStateException(name + " returned " + rc);
        return name.startsWith("Get") ? (String) arch.getMethod("getString").invoke(out)
                : String.valueOf(arch.getMethod("getInt").invoke(out));
    }

    private static int readBacklight() throws Exception {
        String s = call("GetItem.vt_hs_av_service", "{\"get_value\":\"backlight\"}");   // {"backlight":50}
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"backlight\"\\s*:\\s*(\\d+)").matcher(s == null ? "" : s);
        if (!m.find()) throw new IllegalStateException("unexpected answer " + s);
        return Integer.parseInt(m.group(1));
    }

    public static void main(String[] args) {
        try {
            rod = Class.forName("com.hsp.rod.Rod");
            pool = Class.forName("com.hsp.rod.ArchivePool");
            arch = Class.forName("com.hsp.rod.Archive");
            Class<?> listener = Class.forName("com.hsp.rod.IRodListener");
            // What art mode does first: registers the call natives, declares the calls, then connects.
            rod.getMethod("commonInterface", String.class, String.class, int.class, int.class, Class.forName("com.hsp.rod.HSPBuffer"))
                    .invoke(null, "hsp.opt.v1", "", 0, 0, null);
            rod.getMethod("subscribe", String[].class).invoke(null,
                    (Object) new String[]{"GetItem.vt_hs_av_service", "SetItem.vt_hs_av_service", "getLightSensorValue.vt_hs_av_service"});
            int rc = (Integer) rod.getMethod("run", String.class, listener).invoke(null, "canvasframe", listener.getConstructor().newInstance());
            if (rc != 0) throw new IllegalStateException("run returned " + rc);
            Thread.sleep(300);
            if (args.length >= 1 && args[0].equals("env")) {
                // For the shared site: "OK <backlight> <light sensor>".
                System.out.println("OK " + readBacklight() + " " + call("getLightSensorValue.vt_hs_av_service", null));
                System.out.flush();
                System.exit(0);
            }
            if (args.length >= 1 && args[0].equals("light")) {
                // The ambient light sensor art mode polls (about once a second) to dim itself.
                System.out.println("OK " + call("getLightSensorValue.vt_hs_av_service", null));
                System.out.flush();
                System.exit(0);
            }
            if (args.length >= 1 && args[0].equals("pic")) {
                if (args.length >= 2) {
                    int v = Math.max(0, Math.min(20, Integer.parseInt(args[1])));
                    call("SetItem.vt_hs_av_service", "{\"pic_mode\":" + v + "}");
                }
                String s = call("GetItem.vt_hs_av_service", "{\"get_value\":\"pic_mode\"}");   // {"pic_mode":3}
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"pic_mode\"\\s*:\\s*(\\d+)").matcher(s == null ? "" : s);
                if (!m.find()) throw new IllegalStateException("unexpected answer " + s);
                System.out.println("OK " + m.group(1));
                System.out.flush();
                System.exit(0);
            }
            if (args.length >= 2 && args[0].equals("set")) {
                int v = Math.max(0, Math.min(100, Integer.parseInt(args[1])));
                call("SetItem.vt_hs_av_service", "{\"backlight\":" + v + "}");
            } else if (args.length < 1 || !args[0].equals("get")) {
                throw new IllegalArgumentException("usage: get | set <0-100> | light | env | pic [<mode>]");
            }
            System.out.println("OK " + readBacklight());
            System.out.flush();
            System.exit(0);
        } catch (Throwable t) {
            Throwable e = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            System.out.println("ERR " + e);
            System.out.flush();
            System.exit(1);
        }
    }
}
