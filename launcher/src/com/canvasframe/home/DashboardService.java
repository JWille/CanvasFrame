package com.canvasframe.home;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glasshouse-style dashboard for the Hisense CanvasTV, served from the TV at http://<tv-ip>:8080/.
 * LAN-only: requests from public IP addresses are refused.
 */
public class DashboardService extends Service {

    public static final int PORT = 8080;
    private static final String CHANNEL = "dashboard";

    static final String ART_PKG = "com.vt.arttv.artmode";
    static final String ART_UPDATE = "com.vt.arttv.artmode/com.vt.arttv.upgrade_image.service.UpgradeImageService";
    static final String ACR_PKG = "com.vt.tv.evservice";
    static final String GTV_LAUNCHER = "com.google.android.apps.tv.launcherx";
    static final String GTV_SETUP = "com.google.android.tungsten.setupwraith";

    /** Known Hisense / Google TV extras that are safe to disable, with a plain description. */
    static final Map<String, String> SUGGESTED = new HashMap<>();
    static {
        SUGGESTED.put("com.vt.tv.evservice", "ACR: reports what's on screen to Hisense");
        SUGGESTED.put("com.vidaa.oemtabcontentprovider", "Hisense promo tiles on the Google TV home screen");
        SUGGESTED.put("net.vidaatv.hisense.android.tv", "VIDAA free ad-supported channels");
        SUGGESTED.put("android.autoinstalls.config.google.gtvpai", "Silently installs promoted apps");
        SUGGESTED.put("com.vt.storemode", "Retail store demo mode");
        SUGGESTED.put("tv.mopa.ginga", "Brazilian broadcast interactive TV");
        SUGGESTED.put("com.vt.hishow", "Family Moment");
        SUGGESTED.put("com.vt.alexa", "Hisense Alexa");
        SUGGESTED.put("com.vt.tv.b2b", "Hotel / business mode");
    }

    /** Never offer to disable these: the TV, inputs, art mode, or this app would break. */
    static boolean isProtected(String pkg) {
        return pkg.equals("android") || pkg.startsWith("com.android.") || pkg.startsWith("com.mediatek.")
                || pkg.startsWith("com.vt.source.") || pkg.equals("com.vt.tv.tvcenter") || pkg.equals("com.vt.tvservice")
                || pkg.equals("com.vt.tvplayer") || pkg.equals("com.vt.tv.input") || pkg.equals("com.vt.tv.commonui")
                || pkg.equals("com.vt.avmiddleware") || pkg.equals(ART_PKG) || pkg.equals("com.google.android.gms")
                || pkg.equals("com.google.android.gsf") || pkg.equals("com.android.vending")
                || pkg.equals("com.google.android.webview") || pkg.startsWith("com.google.android.overlay")
                || pkg.equals("com.google.android.permissioncontroller") || pkg.equals("com.google.android.ext.services")
                || pkg.equals("com.canvasframe.home") || pkg.endsWith("rro_product__") || pkg.endsWith("rro_vendor__")
                || pkg.equals("com.vt.tv.ota") || pkg.equals("com.vt.eula") || pkg.equals("com.vt.fte");
    }

    private AdbClient adb;
    private Radar radar;
    private ServerSocket server;
    private volatile ServerSocket tlsServer;
    /** True once the HTTPS server is listening; until then plain HTTP keeps working for the LAN. */
    private volatile boolean tlsReady;
    /** Whether the request on this worker thread came in over HTTPS (session cookies get "Secure"). */
    private static final ThreadLocal<Boolean> overTls = new ThreadLocal<>();
    /** The current request came with a valid API key (another TV): only then may it get or give shared secrets. */
    private static final ThreadLocal<Boolean> viaKey = new ThreadLocal<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private volatile boolean running;
    private long[] lastCpu; // total, idle

    @Override
    public void onCreate() {
        super.onCreate();
        adb = new AdbClient(this, "127.0.0.1", 5555);
        startForeground(1, notification());
        running = true;
        new Thread(this::serve, "dashboard-http").start();
        new Thread(this::serveTls, "dashboard-https").start();
        // Activity for the shared site: screen turned on, or a screen saver dismissed. Nearby TVs' screen savers react to it.
        android.content.IntentFilter af = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        af.addAction(Intent.ACTION_DREAMING_STOPPED);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String kind = Intent.ACTION_SCREEN_ON.equals(i.getAction()) ? "screen on" : "screen saver dismissed";
                pool.execute(() -> Site.activity(DashboardService.this, kind));
            }
        }, af);
        // Presence from the Hisense art-mode radar (Radar.java), also while the PC input is on.
        radar = new Radar(this, this::shellOrNull);
        radar.start();
        // Integrations feed signals (Signals.java: sig:<id> in the site); RoomLog turns every TV's signals into history.
        Signals.register("radar", c -> {
            JSONArray a = new JSONArray();
            if ("someone".equals(Radar.state) || "nobody".equals(Radar.state))
                a.put(new JSONObject().put("key", "radar/" + Site.id(c)).put("name", deviceName(c) + " radar").put("kind", "Presence radar")
                        .put("presence", Radar.state).put("since", Radar.since).put("at", Radar.lastReport));
            return a;
        });
        Signals.register("lightsensor", c -> {
            JSONArray a = new JSONArray();
            if (RoomLight.ownLight >= 0 && System.currentTimeMillis() - RoomLight.ownAt < 180_000)
                a.put(new JSONObject().put("key", "light/" + Site.id(c)).put("name", deviceName(c) + " light sensor").put("kind", "TV light sensor")
                        .put("light", Math.min(100, RoomLight.ownLight * 5)).put("at", RoomLight.ownAt));
            return a;
        });
        weatherTimer.scheduleWithFixedDelay(() -> {
            try { Signals.publish(this, false); } catch (Exception ignored) { }
            RoomLog.tick(this);
        }, 20, 30, java.util.concurrent.TimeUnit.SECONDS);
        fastTimer.scheduleWithFixedDelay(() -> { try { Site.fastTick(this); } catch (Exception ignored) { } }, 10, 3, java.util.concurrent.TimeUnit.SECONDS);
        // The shared site (Site.java): identity, rooms and live state of every TV, replicated over the Sync peers.
        weatherTimer.scheduleWithFixedDelay(this::siteTick, 5, 15, java.util.concurrent.TimeUnit.SECONDS);
        weatherTimer.scheduleWithFixedDelay(() -> {
            try { Config.fetchWeather(this); } catch (Exception ignored) { }
            try { Config.fetchCalendars(this); } catch (Exception ignored) { }
        }, 0, 15, java.util.concurrent.TimeUnit.MINUTES);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        running = false;
        if (radar != null) radar.stop();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try { if (tlsServer != null) tlsServer.close(); } catch (IOException ignored) { }
        adb.close();
        pool.shutdownNow();
        weatherTimer.shutdownNow();
        fastTimer.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Dashboard", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("CanvasFrame dashboard")
                .setContentText(dashboardUrl())
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();
    }

    // ================================================================== HTTP

    private void serve() {
        while (running) {
            try (ServerSocket ss = new ServerSocket(PORT)) {
                ss.setReuseAddress(true);
                server = ss;
                while (running) {
                    Socket s = ss.accept();
                    pool.execute(() -> handle(s, false));
                }
            } catch (IOException e) {
                if (running) SystemClock.sleep(2000);
            }
        }
    }

    /** The dashboard address people should use (shown on the home screen and in the notification). */
    static String dashboardUrl() { return "https://" + lanIp() + ":" + Tls.PORT; }

    /** HTTPS on Tls.PORT with this TV's own certificate (made on first start, kept in files/tls). */
    private void serveTls() {
        while (running) {
            try {
                javax.net.ssl.SSLServerSocketFactory f = Tls.serverFactory(tlsDir(this), "CanvasFrame " + deviceName(this), lanIp());
                try (ServerSocket ss = f.createServerSocket(Tls.PORT)) {
                    tlsServer = ss;
                    tlsReady = true;
                    while (running) {
                        Socket s = ss.accept();
                        pool.execute(() -> handle(s, true));
                    }
                }
            } catch (Exception e) {
                tlsReady = false;
                if (running) SystemClock.sleep(5000);
            }
        }
    }

    static java.io.File tlsDir(android.content.Context c) { return new java.io.File(c.getFilesDir(), "tls"); }

    /** Public: lets browsers and other TVs find the HTTPS port and compare this TV's certificate fingerprint. */
    private JSONObject tlsInfo() throws JSONException {
        JSONObject o = new JSONObject().put("port", Tls.PORT).put("ready", tlsReady);
        try { o.put("fingerprint", Tls.fingerprint(Tls.certDer(tlsDir(this)))); } catch (IOException ignored) { }
        return o;
    }

    private void handle(Socket s, boolean tls) {
        overTls.set(tls);
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            InetAddress peer = sock.getInetAddress();
            if (!(peer.isLoopbackAddress() || peer.isSiteLocalAddress() || peer.isLinkLocalAddress())) {
                respond(sock.getOutputStream(), 403, "text/plain", "LAN only".getBytes());
                return;
            }
            InputStream is = sock.getInputStream();
            String requestLine = readLine(is);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], path = parts[1];
            int contentLength = 0;
            String fileName = null, tagHeader = null, cookies = null, ua = null, apiKey = null, host = null;
            boolean xcf = false;
            String line;
            while ((line = readLine(is)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String h = line.substring(0, c).trim(), v = line.substring(c + 1).trim();
                if (h.equalsIgnoreCase("content-length")) contentLength = Integer.parseInt(v);
                if (h.equalsIgnoreCase("x-filename")) fileName = v;
                if (h.equalsIgnoreCase("x-tags")) tagHeader = v;
                if (h.equalsIgnoreCase("cookie")) cookies = v;
                if (h.equalsIgnoreCase("user-agent")) ua = v;
                if (h.equalsIgnoreCase("x-canvasframe")) xcf = true;
                if (h.equalsIgnoreCase("x-canvasframe-key")) apiKey = v;
                if (h.equalsIgnoreCase("host")) host = v;
            }
            String bare = path.indexOf('?') >= 0 ? path.substring(0, path.indexOf('?')) : path;
            if (bare.startsWith("/api/doorbell/hook/") && (method.equals("POST") || method.equals("GET"))) {
                byte[] hb = new byte[Math.min(Math.max(contentLength, 0), 16 * 1024)];
                int off = 0;
                while (off < hb.length) { int r = is.read(hb, off, hb.length - off); if (r < 0) break; off += r; }
                drain(is, contentLength - off);
                String q = path.indexOf('?') >= 0 ? path.substring(path.indexOf('?') + 1) : "";
                String res = doorHook(bare.substring("/api/doorbell/hook/".length()), q, new String(hb, 0, off, StandardCharsets.UTF_8), peer.getHostAddress());
                respond(sock.getOutputStream(), res == null ? 404 : 200, "application/json",
                        (res == null ? "{\"error\":\"not found\"}" : "{\"ok\":true,\"kind\":" + JSONObject.quote(res) + "}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            // Plain HTTP from another device: send browsers to HTTPS and refuse keys, so neither keys nor sign-in
            // cookies cross the network unencrypted. The TV's own loopback stays on HTTP; /api/tls stays reachable
            // so other TVs can find the HTTPS port. If HTTPS couldn't start, HTTP keeps working as before.
            if (!tls && tlsReady && !peer.isLoopbackAddress() && !bare.equals("/api/tls")) {
                drain(is, contentLength);
                OutputStream os = sock.getOutputStream();
                String to = "https://" + hostOnly(host) + ":" + Tls.PORT;
                if (apiKey != null && !apiKey.isEmpty()) {
                    respond(os, 403, "application/json", ("{\"error\":" + JSONObject.quote("API keys are only accepted over HTTPS ("
                            + to + ")") + ",\"https\":" + JSONObject.quote(to) + "}").getBytes(StandardCharsets.UTF_8));
                } else if (method.equals("GET") || method.equals("HEAD")) {
                    respond(os, 302, "text/plain", ("Moved to " + to + path).getBytes(StandardCharsets.UTF_8),
                            "Location: " + to + path + "\r\n");
                } else {
                    respond(os, 403, "application/json", ("{\"error\":" + JSONObject.quote("Use " + to)
                            + ",\"https\":" + JSONObject.quote(to) + "}").getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            // Other web pages can't send custom headers here without a CORS preflight (which is never answered),
            // so requiring one on every change stops cross-site requests.
            if (method.equals("POST") && !xcf) {
                drain(is, contentLength);
                respond(sock.getOutputStream(), 403, "application/json", "{\"error\":\"Missing X-CanvasFrame header\"}".getBytes());
                return;
            }
            String token = cookie(cookies, Auth.COOKIE);
            // An API key (another CanvasFrame TV, Home Assistant) counts as signed in.
            boolean keyOk = Auth.keyValid(this, apiKey);
            viaKey.set(keyOk);
            boolean signedIn = Auth.session(this, token) != null || keyOk;
            // A key this TV doesn't know is refused even while sign-in is off, so the sending TV finds out now.
            if (apiKey != null && !apiKey.isEmpty() && !keyOk) {
                drain(is, contentLength);
                respond(sock.getOutputStream(), 401, "application/json", "{\"error\":\"API key not recognised\",\"badKey\":true}".getBytes());
                return;
            }
            if (!signedIn && !Auth.isPublic(method, bare) && Auth.enabled(this)) {
                drain(is, contentLength);
                respond(sock.getOutputStream(), 401, "application/json", "{\"error\":\"Sign in required\",\"signin\":true}".getBytes());
                return;
            }
            // Photo upload and image serving stream binary data, so they bypass the JSON router.
            if (method.equals("POST") && path.startsWith("/api/upload")) {
                OutputStream os = sock.getOutputStream();
                try {
                    JSONObject r = upload(is, contentLength, fileName);
                    if (tagHeader != null && !tagHeader.isEmpty()) {
                        Config.setTags(this, r.getString("name"), java.util.Arrays.asList(tagHeader.toLowerCase(Locale.US).split("\\s*,\\s*")));
                        r.put("tags", new JSONArray(Config.tagsFor(this, r.getString("name"))));
                    }
                    { String added = r.getString("name"); pool.execute(() -> Site.photoAdded(this, added)); }   // into the mesh
                    respond(os, 200, "application/json", r.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    respond(os, 400, "application/json", ("{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}")
                            .getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (method.equals("GET") && path.startsWith("/api/screenshot")) {
                OutputStream os = sock.getOutputStream();
                try {
                    byte[] png = adb.shellBytes("screencap -p");
                    if (png.length == 0) {
                        // The TV returns nothing while an HDMI input (or art mode) is on screen: it's a protected layer.
                        throw new IOException("the TV won't capture an HDMI input or art mode (protected picture). "
                                + "Screenshots work for the home screen, apps and menus.");
                    }
                    // A valid PNG starts with 0x89 'P' 'N' 'G'; anything else is an error message from screencap.
                    if (png.length < 8 || (png[0] & 0xff) != 0x89 || png[1] != 'P') {
                        throw new IOException(new String(png, StandardCharsets.UTF_8).trim());
                    }
                    respond(os, 200, "image/png", png);
                } catch (IOException e) {
                    respond(os, 503, "application/json", ("{\"error\":" + JSONObject.quote(
                            "Screenshot failed: " + e.getMessage()) + "}").getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (method.equals("GET") && (path.equals("/api/pcapp") || path.equals("/api/pcapp/CanvasFrameLink.exe"))) {
                // CanvasFrame Link for Windows, bundled in the APK; the download carries this TV's address (see pcApp).
                OutputStream os = sock.getOutputStream();
                try {
                    byte[] exe = pcApp(host);
                    if (path.endsWith(".exe")) {
                        respond(os, 200, "application/octet-stream", exe, "Content-Disposition: attachment; filename=\"CanvasFrameLink.exe\"\r\n");
                    } else {
                        JSONObject info = new JSONObject(new String(asset("CanvasFrameLink.json"), StandardCharsets.UTF_8));
                        info.put("available", true).put("size", exe.length).put("sha256", Tls.fingerprint(exe).replace(":", "").toLowerCase(java.util.Locale.ROOT))
                                .put("url", "/api/pcapp/CanvasFrameLink.exe");
                        respond(os, 200, "application/json", info.toString().getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    respond(os, 200, "application/json", "{\"available\":false}".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (method.equals("GET") && path.startsWith("/api/photo?")) {
                servePhoto(sock.getOutputStream(), path.substring(path.indexOf('?') + 1));
                return;
            }
            byte[] body = new byte[Math.min(contentLength, 64 * 1024)];
            int off = 0;
            while (off < body.length) {
                int r = is.read(body, off, body.length - off);
                if (r < 0) break;
                off += r;
            }
            JSONObject req = body.length > 0 ? new JSONObject(new String(body, StandardCharsets.UTF_8)) : new JSONObject();
            if (bare.startsWith("/api/auth")) {
                authRoute(sock.getOutputStream(), method, bare, req, peer.getHostAddress(), token, signedIn, ua);
                return;
            }
            route(sock.getOutputStream(), method, path, req);
        } catch (Exception ignored) {
            // includes TLS handshakes refused by browsers that haven't accepted the certificate yet
        } finally {
            overTls.remove();
            viaKey.remove();
        }
    }

    /** The host part of a Host header ("10.0.0.5:8080" -> "10.0.0.5", "[fe80::1]:8080" -> "[fe80::1]"). */
    private static String hostOnly(String hostHeader) {
        String h = hostHeader == null ? "" : hostHeader.trim();
        if (h.isEmpty() || !h.matches("[A-Za-z0-9.:%\\[\\]-]+")) return lanIp();
        if (h.startsWith("[")) { int e = h.indexOf(']'); return e > 0 ? h.substring(0, e + 1) : lanIp(); }
        int c = h.indexOf(':');
        return c >= 0 ? h.substring(0, c) : h;
    }

    private void route(OutputStream os, String method, String path, JSONObject req) throws IOException {
        int q = path.indexOf('?');
        String query = "";
        if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
        try {
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                respond(os, 200, "text/html; charset=utf-8", asset("dashboard.html"));
                return;
            }
            JSONObject res;
            switch (method + " " + path) {
                case "GET /api/status":   res = status(); break;
                case "GET /api/tls":      res = tlsInfo(); break;
                case "GET /api/inputs":   res = inputs(); break;
                case "GET /api/apps":     res = apps(); break;
                case "GET /api/privacy":  res = privacy(); break;
                case "GET /api/network":  res = network(); break;
                case "GET /api/screensaver":  res = screensaver(); break;
                case "GET /api/roomlight":  res = RoomLight.status(this); break;
                case "GET /api/site":  res = Site.view(this, Boolean.TRUE.equals(viaKey.get())).put("peers", Site.peerStates(this)); break;
                case "POST /api/site":
                    if (req.has("merge")) {   // pushed by another TV
                        if (Site.merge(this, req.optJSONObject("merge"), Boolean.TRUE.equals(viaKey.get()))) ArtDream.nudge();
                        pool.execute(() -> Site.commands(this));
                        res = new JSONObject().put("ok", true);
                    }
                    else { Site.edit(this, req); Site.now(this); res = Site.view(this).put("peers", Site.peerStates(this)); }   // from this dashboard
                    break;
                case "POST /api/screensaver": res = setScreensaver(req); break;
                case "POST /api/network": res = setNetwork(req); break;
                case "POST /api/key":     res = key(req.getString("key")); break;
                case "POST /api/input":   res = switchInput(req.getString("id")); break;
                case "POST /api/wol":     res = wol(req); break;
                case "GET /api/doorbell":  res = doorStatus(); break;
                case "POST /api/doorbell": res = doorSet(req); break;
                case "POST /api/doorbell/test": res = doorTest(req); break;
                case "GET /api/rooms": res = RoomLog.get(this); break;
                case "POST /api/signals": res = Signals.post(this, req, "api"); break;   // from outside: Home Assistant, scripts, sensors
                case "GET /api/integrations": res = new JSONObject().put("integrations", integrations()); break;
                case "POST /api/integrations": res = integrationSet(req); break;
                case "GET /api/doorbell/cameras": {   // Protect's cameras, for the alert editor's camera picker
                    JSONObject d = Site.door(this);
                    if (d == null || d.optString("apiKey").isEmpty()) throw new IllegalArgumentException("Add a Protect API key first");
                    res = new JSONObject().put("cameras", Doorbell.cameras(d));
                    break;
                }
                case "GET /api/pc":       res = pcStatus(); break;
                case "POST /api/pc":      res = req.has("event") ? pcEvent(req) : pcSet(req); break;
                case "POST /api/art":     res = startUi(new Intent(Intent.ACTION_MAIN)
                        .setClassName(ART_PKG, ART_PKG + ".ArtModeActivity")); break;
                case "POST /api/home": {
                    // Optional message shown on the home screen once open (sync uses it for on-TV feedback).
                    Intent home = new Intent(this, HomeActivity.class);
                    String msg = req.optString("message", "").trim();
                    if (!msg.isEmpty()) home.putExtra(HomeActivity.EXTRA_NOTICE, msg.length() > 200 ? msg.substring(0, 200) : msg);
                    res = startUi(home);
                    break;
                }
                case "POST /api/launch":  res = launch(req.getString("pkg")); break;
                case "POST /api/app":     res = setApp(req.getString("pkg"), req.getBoolean("enabled")); break;
                case "POST /api/privacy": res = setPrivacy(req); break;
                case "POST /api/volume":  res = volume(req); break;
                case "GET /api/settings": res = settings(); break;
                case "POST /api/settings":
                    if (Config.update(this, req)) refreshWeatherAsync();
                    if (req.has(Config.LIGHT_DIM) || req.has(Config.LIGHT_SRC) || req.has(Config.LIGHT_OFFSET) || req.has(Config.ART_DIM)) ArtDream.nudge();
                    for (String k : Site.MESH_SETTINGS) if (req.has(k)) { pool.execute(() -> Site.settingsChanged(this)); break; }   // shared
                    res = settings();
                    break;
                case "GET /api/geocode":
                    res = new JSONObject().put("results", Config.geocode(
                            java.net.URLDecoder.decode(param(query, "q"), "UTF-8")));
                    break;
                case "GET /api/photos":   res = listPhotos(); break;
                case "GET /api/peers":    res = new JSONObject().put("peers", query.contains("check=") ? Sync.check(this) : Sync.list(this)); break;
                case "POST /api/peers":
                    if ("remove".equals(req.optString("action"))) Sync.remove(this, req.optString("id"));
                    else Sync.add(this, req.optString("url"), req.optString("key"));
                    res = new JSONObject().put("peers", Sync.list(this));
                    break;
                case "POST /api/sync": {
                    JSONArray ids = req.optJSONArray("ids");
                    JSONObject what = req.optJSONObject("what");
                    res = new JSONObject().put("results", Sync.run(this, ids == null ? new JSONArray() : ids, what == null ? new JSONObject() : what))
                            .put("peers", Sync.list(this));
                    break;
                }
                case "POST /api/photos/delete": {
                    File f = photoFile(req.getString("name"));
                    if (!f.delete()) throw new IOException("Couldn't delete " + f.getName());
                    Config.setTags(this, f.getName(), null);
                    //noinspection ResultOfMethodCallIgnored
                    previewFile(f.getName()).delete();
                    bumpPhotos();
                    { String gone = f.getName(); pool.execute(() -> Site.photoDeleted(this, gone)); }   // deleted on every TV in the mesh
                    res = listPhotos();
                    break;
                }
                case "POST /api/photos/preview":
                    res = capturePreview(photoFile(req.getString("name")));
                    break;
                case "POST /api/photos/tags": {
                    File f = photoFile(req.getString("name"));
                    List<String> tags = new ArrayList<>();
                    JSONArray ta = req.optJSONArray("tags");
                    if (ta != null) for (int i = 0; i < ta.length(); i++) tags.add(ta.getString(i));
                    Config.setTags(this, f.getName(), tags);
                    { String tagged = f.getName(); pool.execute(() -> Site.tagsChanged(this, tagged)); }   // shared across the mesh
                    res = listPhotos();
                    break;
                }
                case "POST /api/photos/show": {
                    String name = photoFile(req.getString("name")).getName();
                    Config.prefs(this).edit().putString(Config.PHOTO_SHOW, name + "|" + System.currentTimeMillis()).apply();
                    res = new JSONObject().put("ok", true);
                    break;
                }
                case "POST /api/calendars":
                    Config.updateCalendars(this, req);
                    if (!"refresh".equals(req.optString("action"))) pool.execute(() -> Site.calendarsChanged(this));   // shared
                    Config.fetchCalendars(this);
                    res = settings();
                    break;
                case "POST /api/weather":
                    Config.fetchWeather(this);
                    res = settings();
                    break;
                default:
                    respond(os, 404, "application/json", "{\"error\":\"not found\"}".getBytes());
                    return;
            }
            respond(os, 200, "application/json", res.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            String msg = JSONObject.quote(e.getMessage() == null ? e.toString() : e.getMessage());
            respond(os, 500, "application/json", ("{\"error\":" + msg + "}").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Sign-in endpoints; these set or clear the session cookie. */
    private void authRoute(OutputStream os, String method, String path, JSONObject req, String ip,
                           String token, boolean signedIn, String ua) throws IOException {
        String setCookie = null;
        JSONObject res;
        try {
            switch (method + " " + path) {
                case "GET /api/auth":
                    res = Auth.status(this, token);
                    break;
                case "POST /api/auth/login": {
                    String t = Auth.login(this, ip, req.optString("code"), ua);
                    setCookie = sessionCookie(t);
                    res = Auth.status(this, t);
                    break;
                }
                case "POST /api/auth/pass": {
                    // A one-time pass that signs this browser (or the PC app's window) in on another TV of the mesh.
                    if (Auth.enabled(this) && !signedIn) throw new Auth.AuthException("Sign in first");
                    String to = req.optString("to");
                    JSONObject dev = Site.view(this, false).getJSONObject("items").optJSONObject("dev:" + to);
                    JSONObject dd = dev == null || dev.optBoolean("x") ? null : dev.optJSONObject("d");
                    if (dd == null) throw new IllegalArgumentException("Unknown TV");
                    JSONArray urls = dd.optJSONArray("urls");
                    res = new JSONObject().put("url", urls != null && urls.length() > 0 ? urls.getString(0) : "");
                    String secret = Site.ssoSecret(this);
                    if (secret != null) {
                        String[] who = Auth.passIdentity(this, token);
                        res.put("pass", Auth.makePass(secret, Site.id(this), to, who[0], who[1], deviceName(this)));
                    }
                    break;
                }
                case "POST /api/auth/pass-login": {
                    String t = Auth.passLogin(this, ip, req.optString("pass"), Site.ssoSecret(this), Site.id(this), ua);
                    setCookie = sessionCookie(t);
                    res = Auth.status(this, t);
                    break;
                }
                case "POST /api/auth/logout":
                    if (req.optBoolean("everywhere") && token != null) {   // also on every other TV: the sessions of this sign-in chain
                        String chain = Auth.passIdentity(this, token)[1];
                        Auth.revokeChain(this, chain);
                        Site.edit(this, new JSONObject().put("signout", chain));
                        Site.now(this);
                    }
                    Auth.logout(this, token);
                    setCookie = Auth.COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" + secureFlag();
                    res = new JSONObject().put("ok", true);
                    break;
                case "POST /api/auth/enroll":
                    res = Auth.enrollStart(req.optString("name"));
                    break;
                case "POST /api/auth/confirm": {
                    String t = Auth.enrollConfirm(this, req.getString("id"), req.optString("code"), signedIn, ua);
                    pool.execute(() -> Site.authChanged(this));   // share the new authenticator with the other TVs
                    if (t != null) { setCookie = sessionCookie(t); token = t; }
                    res = Auth.status(this, token);
                    break;
                }
                case "POST /api/auth/remove":
                    Auth.remove(this, req.getString("id"));
                    { String rid = req.getString("id"); pool.execute(() -> Site.authRemoved(this, rid)); }   // removed on every TV
                    res = Auth.status(this, token);
                    break;
                case "POST /api/auth/revoke":
                    Auth.revoke(this, req.getString("id"), token);
                    res = Auth.status(this, token);
                    break;
                case "POST /api/auth/key-create": {
                    if (Auth.enabled(this) && !signedIn) throw new Auth.AuthException("Sign in first");
                    JSONObject k = Auth.createKey(this, req.optString("name"));
                    res = Auth.status(this, token).put("newKey", k);
                    break;
                }
                case "POST /api/auth/key-revoke":
                    if (Auth.enabled(this) && !signedIn) throw new Auth.AuthException("Sign in first");
                    Auth.revokeKey(this, req.optString("id"));
                    res = Auth.status(this, token);
                    break;
                default:
                    respond(os, 404, "application/json", "{\"error\":\"not found\"}".getBytes());
                    return;
            }
        } catch (Auth.AuthException e) {
            respond(os, 403, "application/json", ("{\"error\":" + JSONObject.quote(e.getMessage()) + "}").getBytes(StandardCharsets.UTF_8));
            return;
        } catch (Exception e) {
            String msg = JSONObject.quote(e.getMessage() == null ? e.toString() : e.getMessage());
            respond(os, 500, "application/json", ("{\"error\":" + msg + "}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(os, 200, "application/json", res.toString().getBytes(StandardCharsets.UTF_8),
                setCookie == null ? "" : "Set-Cookie: " + setCookie + "\r\n");
    }

    private static String sessionCookie(String token) {
        return Auth.COOKIE + "=" + token + "; Path=/; Max-Age=" + (Auth.SESSION_MS / 1000) + "; HttpOnly; SameSite=Strict" + secureFlag();
    }

    /** Cookies set over HTTPS are marked Secure so a browser never sends them over plain HTTP. */
    private static String secureFlag() { return Boolean.TRUE.equals(overTls.get()) ? "; Secure" : "";
    }

    /** Reads (up to 1 MB of) an unwanted request body so closing the socket doesn't reset the connection. */
    private static void drain(InputStream is, int len) {
        try {
            byte[] buf = new byte[8192];
            for (int left = Math.min(len, 1 << 20); left > 0; ) {
                int r = is.read(buf, 0, Math.min(buf.length, left));
                if (r < 0) break;
                left -= r;
            }
        } catch (IOException ignored) { }
    }

    private static String cookie(String header, String name) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
        }
        return null;
    }

    private static void respond(OutputStream os, int code, String type, byte[] body) throws IOException {
        respond(os, code, type, body, "");
    }

    private static void respond(OutputStream os, int code, String type, byte[] body, String extraHeaders) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n" + extraHeaders
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        os.write(head.getBytes(StandardCharsets.US_ASCII));
        os.write(body);
        os.flush();
    }

    private static String readLine(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = is.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("header too long");
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    private volatile byte[] pcExe;

    /**
     * CanvasFrame Link (pc/, built into the APK's assets by build.ps1) with a hint appended: JSON {url, name, fp} of this TV
     * (the address the browser used, its HTTPS port, the certificate fingerprint), its length (4 bytes, little-endian) and
     * "CFLINK01". Windows ignores bytes after the program image; the app pre-fills its Add dialog with it and checks the TV's
     * certificate against it.
     */
    private byte[] pcApp(String hostHeader) throws Exception {
        if (pcExe == null) pcExe = asset("CanvasFrameLink.exe");
        String fp = "";
        try { fp = Tls.fingerprint(Tls.certDer(tlsDir(this))); } catch (IOException ignored) { }
        byte[] js = new JSONObject().put("url", "https://" + hostOnly(hostHeader) + ":" + Tls.PORT).put("name", deviceName(this))
                .put("fp", fp).toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcExe.length + js.length + 12);
        out.write(pcExe);
        out.write(js);
        int n = js.length;
        out.write(new byte[]{(byte) n, (byte) (n >> 8), (byte) (n >> 16), (byte) (n >> 24)});
        out.write("CFLINK01".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream is = getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }

    // ================================================================== API

    private String shellOrNull(String cmd) {
        try { return adb.shell(cmd); } catch (IOException e) { return null; }
    }

    private JSONObject sh(String cmd) throws IOException, JSONException {
        String out = adb.shell(cmd);
        return new JSONObject().put("ok", true).put("output", out.trim());
    }

    /** This TV's name: the TV name setting, or the model. */
    static String deviceName(android.content.Context c) {
        String nm = Config.prefs(c).getString(Config.DEVICE_NAME, "");
        return nm.isEmpty() ? model() : nm;
    }

    /** "valerion", "hisense" or "android". Both Hisense TVs and Valerion projectors report manufacturer "HVCL",
     *  so the brand decides (Valerion VisionMaster: brand "VALERION", model "VisionMaster"). */
    static String brand() {
        String b = Build.BRAND.toLowerCase(Locale.US), mf = (Build.MANUFACTURER + " " + b).toLowerCase(Locale.US);
        if (b.contains("valerion")) return "valerion";
        return mf.contains("hisense") || mf.contains("vidaa") ? "hisense" : "android";
    }

    /** Human model name: the original Hisense TV keeps its full name; others come from the build. */
    static String model() {
        switch (brand()) {
            case "hisense": return "Hisense 65S7NUA CanvasTV";
            case "valerion": return "Valerion " + Build.MODEL;
            default: {
                String mf = Build.MANUFACTURER, md = Build.MODEL;
                return md.toLowerCase(Locale.US).startsWith(mf.toLowerCase(Locale.US)) ? md : mf + " " + md;
            }
        }
    }

    private JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("model", model());
        o.put("brand", brand());
        o.put("name", deviceName(this));
        o.put("android", Build.VERSION.RELEASE);
        o.put("build", Build.DISPLAY);
        o.put("ip", lanIp());
        o.put("uptimeSec", SystemClock.elapsedRealtime() / 1000);
        o.put("screenOn", getSystemService(PowerManager.class).isInteractive());
        o.put("features", new JSONObject().put("screensaver", true).put("site", true).put("pcLink", true));

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mi);
        o.put("memTotal", mi.totalMem).put("memAvail", mi.availMem);

        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        o.put("diskTotal", fs.getTotalBytes()).put("diskFree", fs.getAvailableBytes());

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
        if (wi != null && wi.getRssi() > -127 && wi.getLinkSpeed() > 0) {
            o.put("wifiRssi", wi.getRssi()).put("wifiMbps", wi.getLinkSpeed())
                    .put("wifiFreq", wi.getFrequency());
        }

        AudioManager am = getSystemService(AudioManager.class);
        o.put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("volumeMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC));

        // Shell-level details in one round trip.
        String out = shellOrNull("cat /proc/loadavg; echo @@; head -1 /proc/stat; echo @@;"
                + " dumpsys thermalservice | grep -A3 'Current temperatures' | grep -m1 'mName=cpu'; echo @@;"
                + " dumpsys activity activities | grep -m1 mResumedActivity; echo @@;"
                + " dumpsys tv_input | grep -m1 -E 'mCurrentInputId|currentInput' ");
        o.put("adb", adb.state().name());
        if (!adb.lastError().isEmpty()) o.put("adbError", adb.lastError());
        if (out != null) {
            String[] sec = out.split("@@");
            if (sec.length > 0) o.put("loadavg", sec[0].trim().split(" ")[0]);
            if (sec.length > 1) {
                long[] cpu = parseCpu(sec[1]);
                if (cpu != null && lastCpu != null && cpu[0] > lastCpu[0]) {
                    double busy = 1.0 - (double) (cpu[1] - lastCpu[1]) / (cpu[0] - lastCpu[0]);
                    o.put("cpuPct", Math.round(busy * 100));
                }
                if (cpu != null) lastCpu = cpu;
            }
            if (sec.length > 2) {
                Matcher t = Pattern.compile("mValue=([0-9.]+)").matcher(sec[2]);
                if (t.find()) o.put("tempC", Double.parseDouble(t.group(1)));
            }
            if (sec.length > 3) {
                Matcher m = Pattern.compile("u0 ([\\w.]+)/([\\w.$]+)").matcher(sec[3]);
                if (m.find()) {
                    o.put("foregroundPkg", m.group(1));
                    o.put("foreground", label(m.group(1)));
                }
            }
        }
        return o;
    }

    private static long[] parseCpu(String line) {
        String[] f = line.trim().split("\\s+");
        if (f.length < 5 || !f[0].equals("cpu")) return null;
        long total = 0;
        for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
        long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
        return new long[]{total, idle};
    }

    private JSONObject inputs() throws JSONException {
        TvInputManager tim = getSystemService(TvInputManager.class);
        JSONArray arr = new JSONArray();
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
            CharSequence custom = info.loadCustomLabel(this);
            String label = custom != null && custom.length() > 0 ? custom.toString().trim() : info.loadLabel(this).toString();
            arr.put(new JSONObject().put("id", info.getId()).put("label", label)
                    .put("connected", tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED));
        }
        return new JSONObject().put("inputs", arr);
    }

    private JSONObject switchInput(String id) throws IOException, JSONException {
        return startUi(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)));
    }

    /**
     * Opens a screen. Android blocks background services from starting activities unless the app may
     * "display over other apps"; with that granted this works without ADB. Otherwise fall back to ADB's am start.
     */
    private JSONObject startUi(Intent i) throws IOException, JSONException {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (android.provider.Settings.canDrawOverlays(this)) {
            startActivity(i);
            return new JSONObject().put("ok", true).put("via", "app");
        }
        String uri = i.toUri(Intent.URI_INTENT_SCHEME).replace("'", "");
        return sh("am start '" + uri + "'").put("via", "adb");
    }

    // ---------------------------------------------------------------- UniFi Protect doorbell (Doorbell.java)

    /** The webhook. Returns the kind on success, null for a wrong token (404). */
    private String doorHook(String hook, String query, String body, String from) {
        JSONObject door = Site.door(this);
        if (door == null || door.optString("hook").isEmpty()
                || !java.security.MessageDigest.isEqual(door.optString("hook").getBytes(StandardCharsets.UTF_8), hook.getBytes(StandardCharsets.UTF_8))) return null;
        String kind = Doorbell.kindOf(door, query, body);
        Doorbell.remember(this, kind, from, body);
        doorAlert(kind);
        return kind;
    }

    /** Shows it here and tells every other TV. */
    private void doorAlert(String kind) {
        String event = Long.toString(System.currentTimeMillis(), 36) + Integer.toHexString(new java.util.Random().nextInt(0xffff));
        doorHere(kind, event);
        pool.execute(() -> {
            try { Site.edit(this, new JSONObject().put("doorbell", new JSONObject().put("kind", kind).put("event", event))); Site.now(this); }
            catch (Exception e) { android.util.Log.e("CanvasFrame", "doorbell: " + e); }
        });
    }

    private void doorHere(String kind, String event) {
        pool.execute(() -> {
            try {
                JSONObject now = screenNow();
                if ("off".equals(now.optString("screen"))) return;   // the TV is asleep
                boolean ours = "saver".equals(now.optString("screen")) || getPackageName().equals(now.optString("pkg"));
                Doorbell.show(this, kind, event, Site.door(this), !ours);
            } catch (Exception e) { android.util.Log.e("CanvasFrame", "doorbell: " + e); }
        });
    }

    private JSONObject doorStatus() throws JSONException {
        JSONObject d = Site.door(this);
        android.content.SharedPreferences p = Config.prefs(this);
        JSONObject o = new JSONObject().put("configured", d != null).put("show", p.getBoolean(Doorbell.SHOW, false))
                .put("over", p.getBoolean(Doorbell.OVER, false)).put("canOver", true).put("overPanel", true)
                .put("secs", p.getInt(Doorbell.SECS, 45)).put("secsOver", p.getInt(Doorbell.SECS_OVER, 15))
                .put("corner", p.getString(Doorbell.CORNER, "br")).put("size", p.getString(Doorbell.SIZE, "m"));
        String hide = Doorbell.hidden(this);
        o.put("hide", new JSONArray(hide.isEmpty() ? new java.util.ArrayList<String>() : java.util.Arrays.asList(hide.split(","))));
        // The alerts for the browser: no stream tokens, only whether each has one (its own or the older fallback).
        JSONArray al = Doorbell.alerts(d), shown = new JSONArray();
        for (int i = 0; i < al.length(); i++) {
            JSONObject a = al.getJSONObject(i);
            shown.put(new JSONObject().put("id", a.optString("id")).put("name", a.optString("name"))
                    .put("camera", a.optString("camera")).put("quiet", a.optBoolean("quiet"))
                    .put("stream", d != null && !Doorbell.stream(d, a).isEmpty()).put("ownStream", !a.optString("stream").isEmpty()));
        }
        o.put("alerts", shown);
        if (d != null) {
            o.put("console", d.optString("console")).put("apiKey", !d.optString("apiKey").isEmpty())
                    .put("live", !d.optString("live").isEmpty()).put("pkg", !d.optString("pkg").isEmpty())
                    .put("hookPath", "/api/doorbell/hook/" + d.optString("hook"));
        }
        return o.put("recent", Doorbell.recent(this).getJSONArray("events"));
    }

    /** POST /api/doorbell: per TV {show, over}; shared {console, apiKey, live, pkg} (blank = keep; "-" = clear); {remove:true}. */
    private JSONObject doorSet(JSONObject req) throws Exception {
        android.content.SharedPreferences.Editor e = Config.prefs(this).edit();
        if (req.has("show")) e.putBoolean(Doorbell.SHOW, req.optBoolean("show"));
        if (req.has("over")) e.putBoolean(Doorbell.OVER, req.optBoolean("over"));
        if (req.has("secs")) e.putInt(Doorbell.SECS, Math.max(5, Math.min(600, req.optInt("secs", 45))));
        if (req.has("secsOver")) e.putInt(Doorbell.SECS_OVER, Math.max(5, Math.min(600, req.optInt("secsOver", 15))));
        if (req.optString("corner").matches("br|bl|tr|tl")) e.putString(Doorbell.CORNER, req.optString("corner"));
        if (req.optString("size").matches("[sml]")) e.putString(Doorbell.SIZE, req.optString("size"));
        if (req.has("hide")) {   // alert ids not shown on this TV
            JSONArray hs = req.optJSONArray("hide");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; hs != null && i < hs.length(); i++) {
                String k = hs.optString(i);
                if (k.matches("[A-Za-z0-9_-]{1,24}")) sb.append(sb.length() > 0 ? "," : "").append(k);
            }
            e.putString(Doorbell.HIDE, sb.toString());
        }
        e.apply();
        if (req.optBoolean("close")) { new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> Doorbell.close(this)); return doorStatus(); }
        if (req.optBoolean("remove")) { Site.setDoor(this, null); return doorStatus(); }
        if (req.has("alerts")) {   // the shared alert list: [{id?, name, camera, quiet, stream ("" keep, "-" clear, rtsps://… or a token)}]
            JSONObject cur = Site.door(this);
            if (cur == null) throw new IllegalArgumentException("Set up the Protect console first");
            JSONObject d = new JSONObject(cur.toString());
            JSONArray in = req.optJSONArray("alerts"), out = new JSONArray(), old = Doorbell.alerts(cur);
            java.util.Set<String> ids = new java.util.HashSet<>(), keep = new java.util.HashSet<>();
            // Existing alerts keep their ids (their webhook addresses): reserve them before naming new ones.
            for (int i = 0; in != null && i < in.length(); i++) {
                String id = in.optJSONObject(i) == null ? "" : in.optJSONObject(i).optString("id");
                for (int j = 0; j < old.length(); j++) if (id.equals(old.optJSONObject(j).optString("id"))) keep.add(id);
            }
            for (int i = 0; in != null && i < in.length() && out.length() < 20; i++) {
                JSONObject a = in.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name").trim();
                if (name.isEmpty()) continue;
                if (name.length() > 40) name = name.substring(0, 40);
                String id = a.optString("id");
                if (!keep.contains(id) || ids.contains(id)) {   // a new alert: an id from its name, clear of the others
                    String base = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
                    if (base.isEmpty()) base = "alert";
                    if (base.length() > 20) base = base.substring(0, 20);
                    id = base;
                    for (int n = 2; ids.contains(id) || keep.contains(id); n++) id = base + "-" + n;
                }
                ids.add(id);
                JSONObject before = null;
                for (int j = 0; j < old.length(); j++) if (id.equals(old.optJSONObject(j).optString("id"))) before = old.optJSONObject(j);
                String stream = a.optString("stream").trim();
                boolean noStream = stream.equals("-") || (stream.isEmpty() && before != null && before.optBoolean("noStream"));
                java.util.regex.Matcher m = Pattern.compile("^rtsps?://([0-9A-Za-z.-]+):\\d+/([A-Za-z0-9]+)").matcher(stream);
                if (m.find()) stream = m.group(2);
                else if (stream.equals("-")) stream = "";
                else if (stream.isEmpty()) stream = before == null ? "" : Doorbell.stream(cur, before);   // keep (incl. the older fallback)
                else if (!stream.matches("[A-Za-z0-9]{8,64}")) throw new IllegalArgumentException("That stream for " + name + " isn't an rtsps:// address from Protect");
                String cam = a.optString("camera");
                if (!cam.matches("[A-Za-z0-9]{0,40}")) cam = "";
                JSONObject o = new JSONObject().put("id", id).put("name", name);
                if (!cam.isEmpty()) o.put("camera", cam);
                if (!stream.isEmpty()) o.put("stream", stream);
                else if (noStream) o.put("noStream", true);   // removed on purpose: no fallback to the older doorbell stream
                if (a.optBoolean("quiet")) o.put("quiet", true);
                out.put(o);
            }
            if (out.length() == 0) throw new IllegalArgumentException("Keep at least one alert");
            d.put("alerts", out);
            Site.setDoor(this, d);
            return doorStatus();
        }
        if (req.has("console") || req.has("apiKey") || req.has("live") || req.has("pkg")) {
            JSONObject cur = Site.door(this);
            JSONObject d = cur == null ? new JSONObject() : new JSONObject(cur.toString());
            for (String k : new String[]{"console", "apiKey", "live", "pkg"}) {
                if (!req.has(k)) continue;
                String v = req.optString(k).trim();
                if (v.equals("-")) d.remove(k);
                else if (!v.isEmpty()) d.put(k, v);
            }
            // A pasted stream address (rtsps://host:7441/TOKEN?enableSrtp) gives the console and the token.
            for (String k : new String[]{"live", "pkg"}) {
                java.util.regex.Matcher m = Pattern.compile("^rtsps?://([0-9A-Za-z.-]+):\\d+/([A-Za-z0-9]+)").matcher(d.optString(k));
                if (m.find()) { if (d.optString("console").isEmpty()) d.put("console", m.group(1)); d.put(k, m.group(2)); }
            }
            String console = d.optString("console").replaceAll("^https?://", "").replaceAll("/.*$", "");
            if (console.isEmpty()) throw new IllegalArgumentException("Enter the Protect console's address");
            if (!console.equals(cur == null ? "" : cur.optString("console")) || d.optString("fp").isEmpty()) {
                // Record the console's certificate on first contact; afterwards only that one is accepted.
                String[] seen = new String[1];
                try {
                    javax.net.ssl.SSLSocket sk = (javax.net.ssl.SSLSocket) Tls.clientFactory(null, seen).createSocket(console, 443);
                    sk.setSoTimeout(5000); sk.startHandshake(); sk.close();
                } catch (Exception ex) { throw new IllegalArgumentException("The Protect console at " + console + " didn't answer (" + ex.getMessage() + ")"); }
                d.put("fp", seen[0] == null ? "" : seen[0]);
            }
            d.put("console", console);
            if (d.optString("hook").isEmpty()) d.put("hook", Auth.sha256(Long.toString(System.nanoTime()) + Math.random()).substring(0, 32));
            Doorbell.forgetCamera();
            Site.setDoor(this, d);
        }
        return doorStatus();
    }

    /** POST /api/doorbell/test {kind, here}: like a ring, on every TV (or only this one). Also checks a snapshot. */
    private JSONObject doorTest(JSONObject req) throws Exception {
        String kind = req.optString("kind", "ring");
        JSONObject o = new JSONObject().put("ok", true);
        JSONObject d = Site.door(this);
        if (d != null && !d.optString("apiKey").isEmpty()) {
            try { o.put("snapshot", Doorbell.snapshot(d, Doorbell.alert(d, kind).optString("camera")).length); }
            catch (Exception e) { o.put("snapshotError", e.getMessage()); }
        }
        if (req.optBoolean("here")) doorHere(kind, "test" + System.currentTimeMillis()); else doorAlert(kind);
        return o;
    }

    // ---------------------------------------------------------------- integrations (the dashboard's Integrations tab)

    private String tempText(Object t) {
        if (!(t instanceof Number)) return "";
        double c = ((Number) t).doubleValue();
        return "C".equals(Config.prefs(this).getString(Config.UNITS, "F")) ? String.format(java.util.Locale.US, "%.1f °C", c)
                : String.format(java.util.Locale.US, "%.1f °F", c * 9 / 5 + 32);
    }

    private static String ago(long at) {
        long m = Math.round((System.currentTimeMillis() - at) / 60000.0);
        return m < 1 ? "just now" : m < 60 ? m + " min ago" : Math.round(m / 60.0) + " h ago";
    }

    private static JSONObject status(String state, String text) throws JSONException { return new JSONObject().put("state", state).put("text", text); }

    /** [{id, name, desc, status {state ok|idle|off|warn, text}, settings [...], readings [{label, text}], panel?, link?, info?}] */
    private JSONArray integrations() throws JSONException {
        JSONArray out = new JSONArray();
        if (Radar.available(this)) {
            boolean on = Radar.enabled(this);
            JSONObject r = Radar.json();
            String st = r == null ? "" : r.optString("state");
            out.put(new JSONObject().put("id", "radar").put("name", "Presence radar").put("icon", "radar")
                    .put("desc", "The radar built into this TV (the one Hisense art mode uses) tells whether someone is in the room, also while the computer or another input is on screen. Only someone / nobody is used; it is shared with your other TVs and never recorded elsewhere.")
                    .put("status", !on ? status("off", "Off") : "someone".equals(st) ? status("ok", "Someone in the room") : "nobody".equals(st) ? status("ok", "Nobody in the room")
                            : status("idle", r == null ? "Starting" : "No reading yet (" + r.optString("mode") + ")"))
                    .put("settings", new JSONArray().put(new JSONObject().put("key", "radar").put("label", "Use the presence radar").put("type", "switch").put("value", on)))
                    .put("readings", r != null && r.has("since") && !"unknown".equals(st)
                            ? new JSONArray().put(new JSONObject().put("label", "someone".equals(st) ? "Someone since" : "Nobody since")
                                    .put("text", new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(new java.util.Date(r.optLong("since"))))) : new JSONArray()));
        }
        boolean hsp = RoomLight.ownLight >= 0;
        out.put(new JSONObject().put("id", "lightsensor").put("name", "TV light sensor").put("icon", "sun")
                .put("desc", "This TV's own ambient light sensor (read through Hisense's picture service). Its reading is shared with the other TVs in the room, and CanvasArt can dim by it (Home screen → Screen saver → Dim by room light).")
                .put("status", hsp ? status("ok", "Reading " + RoomLight.ownLight + " (0-20)") : status("idle", "No reading yet"))
                .put("readings", hsp ? new JSONArray().put(new JSONObject().put("label", "Room light").put("text", RoomLight.ownLight + " of 20 · " + ago(RoomLight.ownAt))) : new JSONArray())
                .put("link", new JSONObject().put("tab", "homescreen").put("label", "Dimming settings")));
        // SwitchBot: this TV doesn't scan; it shows what the LG TVs hear.
        JSONArray clim = RoomLog.get(this).getJSONArray("climate"), readings = new JSONArray();
        for (int i = 0; i < clim.length(); i++) {
            JSONObject c = clim.getJSONObject(i);
            if (!"switchbot".equals(c.optString("src")) || !c.optBoolean("fresh")) continue;
            StringBuilder t = new StringBuilder(tempText(c.opt("t")));
            if (c.has("h")) t.append(" · ").append(c.optInt("h")).append(" %");
            if (c.has("light")) t.append(" · light ").append(c.optInt("light")).append(" %");
            readings.put(new JSONObject().put("label", c.optString("name")).put("text", t.append(" · ").append(ago(c.optLong("at"))).toString()));
        }
        out.put(new JSONObject().put("id", "switchbot").put("name", "Bluetooth sensors").put("icon", "bt")
                .put("desc", "Temperature, humidity and light from SwitchBot sensors, read from their Bluetooth broadcasts by your LG TVs (this TV doesn't listen itself) and shared with every TV. Add the ones you want below (Find sensors); only those are kept.")
                .put("status", readings.length() > 0 ? status("ok", readings.length() + " sensor" + (readings.length() == 1 ? "" : "s") + " (via your LG TVs)") : status("idle", "None of your sensors heard right now"))
                .put("panel", "sensors")
                .put("readings", readings));
        JSONObject d = Site.door(this);
        int alerts = Doorbell.alerts(d).length();
        out.put(new JSONObject().put("id", "protect").put("name", "UniFi Protect").put("icon", "camera")
                .put("desc", "Alerts from your UniFi Protect cameras and doorbell (Alarm Manager webhooks), shown on your TVs with the camera picture.")
                .put("status", d != null ? status("ok", alerts + " alert" + (alerts == 1 ? "" : "s") + " · " + d.optString("console")) : status("idle", "Not set up"))
                .put("panel", "protect"));
        JSONArray posted = Signals.posted(), pr = new JSONArray();
        for (int i = 0; i < posted.length(); i++) {
            JSONObject p = posted.getJSONObject(i);
            String t = (p.optString("presence") + " " + tempText(p.opt("t")) + (p.has("h") ? " · " + p.optInt("h") + " %" : "")).trim();
            pr.put(new JSONObject().put("label", p.optString("name")).put("text", (t.isEmpty() ? "" : t + " · ") + ago(p.optLong("at"))));
        }
        out.put(new JSONObject().put("id", "api").put("name", "Signals API").put("icon", "api")
                .put("desc", "Anything on your network (Home Assistant, a script, an ESP32 sensor) can add presence or readings to your TVs: POST /api/signals with an API key (Privacy → API keys) and the header X-CanvasFrame: 1. Posted signals count for 15 minutes after the last post.")
                .put("status", posted.length() > 0 ? status("ok", posted.length() + " signal" + (posted.length() == 1 ? "" : "s") + " posted here") : status("idle", "Nothing posted here yet"))
                .put("readings", pr)
                .put("info", new JSONObject().put("endpoint", "/api/signals")
                        .put("example", "{\"signals\":[{\"key\":\"hall-motion\",\"name\":\"Hall motion\",\"room\":\"<room id>\",\"presence\":\"someone\"}]}")));
        return out;
    }

    private JSONObject integrationSet(JSONObject req) throws JSONException {
        JSONObject set = req.optJSONObject("set");
        if ("radar".equals(req.optString("id")) && set != null && set.has("radar")) Config.prefs(this).edit().putBoolean(Radar.PREF, set.optBoolean("radar")).apply();
        else throw new IllegalArgumentException("Nothing to change there");
        return new JSONObject().put("integrations", integrations());
    }

    // ---------------------------------------------------------------- Wake-on-LAN

    private volatile JSONObject macCache;
    private volatile long macAt;

    /** This TV's MAC addresses {wifi, eth, using}. Android hides them from apps, so they come from ADB's shell (the Wi-Fi
     *  MAC from dumpsys wifi, the Ethernet one from sysfs); refreshed every 10 minutes. */
    private JSONObject macs() throws JSONException {
        if (macCache != null && System.currentTimeMillis() - macAt < 600_000) return macCache;
        JSONObject o = new JSONObject();
        String out = shellOrNull("dumpsys wifi | grep -m1 'mWifiInfo'; echo @@; cat /sys/class/net/eth0/address /sys/class/net/eth0/operstate 2>/dev/null");
        if (out != null) {
            String[] sec = out.split("@@");
            Matcher w = Pattern.compile("MAC: ([0-9a-fA-F:]{17})").matcher(sec[0]);
            boolean wifiOn = sec[0].contains("Supplicant state: COMPLETED");
            if (w.find() && !w.group(1).startsWith("02:00:00")) o.put("wifi", w.group(1).toLowerCase(java.util.Locale.ROOT));
            if (sec.length > 1) {
                Matcher e = Pattern.compile("([0-9a-fA-F:]{17})\\s+(\\w+)").matcher(sec[1].trim());
                if (e.find()) { o.put("eth", e.group(1).toLowerCase(java.util.Locale.ROOT)); if ("up".equals(e.group(2))) o.put("using", "eth"); }
            }
            if (!o.has("using") && wifiOn && o.has("wifi")) o.put("using", "wifi");
        }
        macCache = o; macAt = System.currentTimeMillis();
        return o;
    }

    /** POST /api/wol {id} (a TV in the shared site) or {mac, ip}: sends a Wake-on-LAN packet from this TV. */
    private JSONObject wol(JSONObject req) throws Exception {
        String mac = req.optString("mac"), ip = req.optString("ip"), name = "";
        if (req.has("id")) {
            JSONObject d = Site.view(this, false).getJSONObject("items").optJSONObject("dev:" + req.getString("id"));
            JSONObject dd = d == null || d.optBoolean("x") ? null : d.optJSONObject("d");
            if (dd == null) throw new IllegalArgumentException("Unknown TV");
            mac = dd.optString("mac"); name = dd.optString("name");
            JSONArray urls = dd.optJSONArray("urls");
            if (urls != null && urls.length() > 0) ip = android.net.Uri.parse(urls.getString(0)).getHost();
            if (mac.isEmpty()) throw new IllegalArgumentException(name + " hasn't shared its network address yet (it needs a CanvasFrame update)");
        }
        java.util.List<String> sent = Wol.send(mac, ip);
        return new JSONObject().put("ok", true).put("sent", new JSONArray(sent))
                .put("result", "Wake-up sent" + (name.isEmpty() ? "" : " to " + name));
    }

    // ---------------------------------------------------------------- PC link (pc/CanvasFrameLink.exe on the computer)
    // The computer this TV is a monitor for reports its display: "on" (woke / display on / unlocked) -> wake the TV, switch to
    // the PC's input and set its picture mode; "off" (display off / sleep / shutdown) -> if the PC's input is on screen,
    // open CanvasFrame (or start the screen saver). Settings per TV: pc_input (input id, "" = the input labelled computer/PC),
    // pc_pic (picture mode number, -1 = leave it), pc_off (home | saver | none), pc_takeover (idle = only from CanvasFrame,
    // the screen saver, standby or the PC's own input; always = also over other apps and inputs).

    static final String PC_INPUT = "pc_input", PC_PIC = "pc_pic", PC_OFF = "pc_off", PC_TAKE = "pc_takeover";
    /** Picture mode numbers (Hsp "pic") and their SDR names on this model. */
    private static final String[] PIC_NAMES = {"Vivid", "Standard", "Energy Saving", "Theater Day", "PC/Game", "Sports",
            "Theater Night", "Filmmaker Mode"};
    private static final String LIVE_TV = "com.vt.tv.tvcenter";   // the activity showing an HDMI input
    private volatile JSONObject pcLast;
    private final java.util.concurrent.atomic.AtomicInteger pcGen = new java.util.concurrent.atomic.AtomicInteger();

    private String hspCmd(String args) {
        return "BOOTCLASSPATH=$BOOTCLASSPATH:/system/framework/com.hsp.rod.jar CLASSPATH='" + getApplicationInfo().sourceDir.replace("'", "")
                + "' app_process /system/bin com.canvasframe.home.Hsp " + args + " 2>&1";
    }

    /** The picture mode of the input on screen (setting it first when set >= 0), or -1. */
    private int picMode(int set) {
        String out = shellOrNull(hspCmd(set >= 0 ? "pic " + set : "pic"));
        int i = out == null ? -1 : out.lastIndexOf("OK ");
        if (i < 0) return -1;
        try { return Integer.parseInt(out.substring(i + 3).trim().split("\\s")[0]); } catch (NumberFormatException e) { return -1; }
    }

    /** The PC's input: the chosen one, else the HDMI input whose custom label says computer / PC. */
    private String pcInput() {
        String id = Config.prefs(this).getString(PC_INPUT, "");
        if (!id.isEmpty()) return id;
        for (TvInputInfo info : getSystemService(TvInputManager.class).getTvInputList()) {
            if (!info.isPassthroughInput()) continue;
            CharSequence l = info.loadCustomLabel(this);
            if (l != null && l.toString().trim().matches("(?i).*\\b(computer|pc|desktop|laptop)\\b.*")) return info.getId();
        }
        return "";
    }

    private String inputLabel(String id) {
        try {
            TvInputInfo info = getSystemService(TvInputManager.class).getTvInputInfo(id);
            if (info == null) return id;
            CharSequence l = info.loadCustomLabel(this);
            return l != null && l.length() > 0 ? l.toString().trim() : info.loadLabel(this).toString();
        } catch (Exception e) { return id; }
    }

    /** What's on screen: {screen on|saver|off, pkg (resumed app), input (TV input of the live TV session)}. */
    private JSONObject screenNow() throws JSONException {
        boolean on = getSystemService(PowerManager.class).isInteractive();
        String out = shellOrNull("dumpsys dreams | grep -m1 mCurrentDreamName; echo @@; dumpsys activity activities | grep -m1 mResumedActivity;"
                + " echo @@; dumpsys tv_input | grep -m1 'inputId:'");
        String[] sec = out == null ? new String[0] : out.split("@@");
        JSONObject o = new JSONObject().put("screen", !on ? "off" : sec.length > 0 && sec[0].contains("ComponentInfo") ? "saver" : "on");
        if (sec.length > 1) { Matcher m = Pattern.compile("u0 ([\\w.]+)/").matcher(sec[1]); if (m.find()) o.put("pkg", m.group(1)); }
        if (sec.length > 2) { Matcher m = Pattern.compile("inputId:\\s*(\\S+)").matcher(sec[2]); if (m.find()) o.put("input", m.group(1)); }
        return o;
    }

    private static boolean onInput(JSONObject now, String id) {
        return "on".equals(now.optString("screen")) && LIVE_TV.equals(now.optString("pkg")) && id.equals(now.optString("input"));
    }

    private JSONObject pcStatus() throws JSONException {
        android.content.SharedPreferences p = Config.prefs(this);
        String in = pcInput();
        JSONArray modes = new JSONArray();
        for (int i = 0; i < PIC_NAMES.length; i++) modes.put(new JSONObject().put("id", i).put("name", PIC_NAMES[i]));
        JSONObject now = screenNow();
        JSONObject o = new JSONObject().put("input", in).put("inputSet", p.getString(PC_INPUT, ""))
                .put("inputLabel", in.isEmpty() ? "" : inputLabel(in))
                .put("pic", p.getInt(PC_PIC, -1)).put("off", p.getString(PC_OFF, "home")).put("takeover", p.getString(PC_TAKE, "idle"))
                .put("modes", modes).put("now", now).put("last", pcLast == null ? JSONObject.NULL : pcLast);
        if (!in.isEmpty() && onInput(now, in)) o.put("picNow", picMode(-1));   // per input: only meaningful while the PC is on screen
        return o;
    }

    private JSONObject pcSet(JSONObject req) throws JSONException {
        android.content.SharedPreferences.Editor e = Config.prefs(this).edit();
        if (req.has("input")) e.putString(PC_INPUT, req.optString("input", "").replaceAll("[^A-Za-z0-9_./%-]", ""));
        if (req.has("pic")) e.putInt(PC_PIC, Math.max(-1, Math.min(PIC_NAMES.length - 1, req.optInt("pic", -1))));
        if (req.has("off")) e.putString(PC_OFF, req.optString("off").matches("home|saver|none") ? req.optString("off") : "home");
        if (req.has("takeover")) e.putString(PC_TAKE, "always".equals(req.optString("takeover")) ? "always" : "idle");
        e.apply();
        return pcStatus();
    }

    /** An event from the computer. Answers at once; the switching runs in the background (a newer event cancels it). */
    private JSONObject pcEvent(JSONObject req) throws JSONException {
        String ev = req.optString("event"), from = req.optString("from", "computer").replaceAll("[^\\w .-]", "");
        if (from.length() > 40) from = from.substring(0, 40);
        if (!ev.equals("on") && !ev.equals("off")) throw new IllegalArgumentException("event must be on or off");
        android.content.SharedPreferences p = Config.prefs(this);
        String in = pcInput();
        JSONObject now = screenNow();
        String result;
        int gen = pcGen.incrementAndGet();
        if (in.isEmpty()) result = "No input is set for the computer";
        else if (ev.equals("on")) {
            boolean idle = !"on".equals(now.optString("screen")) || getPackageName().equals(now.optString("pkg"))
                    || LIVE_TV.equals(now.optString("pkg")) && in.equals(now.optString("input"));
            if (!idle && !"always".equals(p.getString(PC_TAKE, "idle")))
                result = "Left alone: something else is on screen (" + label(now.optString("pkg")) + ")";
            else {
                final int pic = p.getInt(PC_PIC, -1);
                final boolean wake = !"on".equals(now.optString("screen")), there = onInput(now, in);
                pool.execute(() -> pcSwitch(gen, in, pic, wake, there));
                result = there ? "Already on " + inputLabel(in) + (pic >= 0 ? ", checking the picture mode" : "") : "Switching to " + inputLabel(in);
            }
        } else {
            String off = p.getString(PC_OFF, "home");
            if (off.equals("none")) result = "Nothing to do (set to stay on the computer's input)";
            else if (!onInput(now, in)) result = "Left alone: the computer's input isn't on screen";
            else {
                pool.execute(() -> {
                    try {
                        if (pcGen.get() != gen) return;
                        if (off.equals("saver")) setScreensaver(new JSONObject().put("preview", true));
                        else startUi(new Intent(this, HomeActivity.class));
                        // As the PC's signal goes, the TV sometimes jumps back to that input by itself (seen once, 2026-11-20):
                        // for 30 s, unless the PC reports "on" meanwhile, put CanvasFrame back.
                        for (int i = 0, again = 0; i < 15 && again < 3 && pcGen.get() == gen; i++) {
                            Thread.sleep(2000);
                            if (pcGen.get() == gen && onInput(screenNow(), in)) { again++; startUi(new Intent(this, HomeActivity.class)); }
                        }
                    } catch (Exception e) { android.util.Log.e("CanvasFrame", "pc off: " + e); }
                });
                result = off.equals("saver") ? "Starting the screen saver" : "Opening CanvasFrame";
            }
        }
        pcLast = new JSONObject().put("event", ev).put("from", from).put("at", System.currentTimeMillis()).put("result", result);
        return new JSONObject().put("ok", true).put("result", result);
    }

    /** Wake if needed, switch to the PC's input, then hold the picture mode for a few seconds (the signal settling, or the
     *  TV's own game-mode detection, can change it right after the switch). Never writes while another input is on screen. */
    private void pcSwitch(int gen, String in, int pic, boolean wake, boolean there) {
        try {
            if (wake) { shellOrNull("input keyevent KEYCODE_WAKEUP"); Thread.sleep(1200); }
            if ("saver".equals(screenNow().optString("screen"))) { shellOrNull("input keyevent KEYCODE_WAKEUP"); Thread.sleep(800); }
            if (pcGen.get() != gen) return;
            if (!there || !onInput(screenNow(), in)) { switchInput(in); Thread.sleep(2500); }
            if (pic < 0) return;
            boolean ok = false;
            for (int i = 0; i < 5 && pcGen.get() == gen; i++) {
                if (onInput(screenNow(), in)) {
                    int cur = picMode(-1);
                    if (cur == pic) { if (ok) return; ok = true; }   // right twice in a row: settled
                    else if (cur >= 0) { ok = false; android.util.Log.e("CanvasFrame", "pc: picture mode " + cur + " -> " + pic); picMode(pic); }
                }
                Thread.sleep(2000);
            }
        } catch (Exception e) { android.util.Log.e("CanvasFrame", "pc on: " + e); }
    }

    // ---------------------------------------------------------------- screen saver (Android "dreams")

    private static final String DREAM_ACTION = "android.service.dreams.DreamService";
    private static final Pattern SAFE_COMPONENT = Pattern.compile("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+");

    /** The installed screen savers, CanvasArt first, then "Off". Same shape as the webOS server's list. */
    private JSONArray saverModes() throws JSONException {
        PackageManager pm = getPackageManager();
        JSONArray ours = new JSONArray(), others = new JSONArray();
        int mins = Config.prefs(this).getInt(Config.ART_MIN, 10);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentServices(new Intent(DREAM_ACTION), 0)) {
            String pkg = ri.serviceInfo.packageName;
            String id = new android.content.ComponentName(pkg, ri.serviceInfo.name).flattenToShortString();
            String label = String.valueOf(ri.loadLabel(pm)), desc;
            if (pkg.equals(getPackageName())) {
                desc = "Your CanvasFrame background photos, like art mode: a new photo every " + (mins >= 60 && mins % 60 == 0 ? (mins == 60 ? "hour" : (mins / 60) + " hours") : mins == 1 ? "minute" : mins + " minutes")
                        + ", slow cross-fades, an optional mat, the time and the weather drifting slowly across, and dimmer at night (settings below).";
                ours.put(new JSONObject().put("id", id).put("label", label).put("desc", desc));
                continue;
            }
            if (pkg.equals("com.google.android.apps.tv.dreamx")) { label = "Google Ambient mode"; desc = "Google TV's own screen saver (Google Photos, art gallery, weather). The TV came with this."; }
            else if (pkg.equals("com.android.dreams.basic")) desc = "Android's slowly changing colours.";
            else desc = "From " + label(pkg) + ".";
            others.put(new JSONObject().put("id", id).put("label", label).put("desc", desc));
        }
        for (int i = 0; i < others.length(); i++) ours.put(others.get(i));
        ours.put(new JSONObject().put("id", "off").put("label", "Off")
                .put("desc", "No screen saver: the picture stays as it is until the TV goes to sleep."));
        return ours;
    }

    private JSONObject screensaver() throws JSONException {
        android.content.ContentResolver cr = getContentResolver();
        boolean on = android.provider.Settings.Secure.getInt(cr, "screensaver_enabled", 1) != 0;
        String comps = android.provider.Settings.Secure.getString(cr, "screensaver_components");
        if (comps == null || comps.isEmpty()) comps = android.provider.Settings.Secure.getString(cr, "screensaver_default_component");
        android.content.ComponentName cn = comps == null || comps.isEmpty() ? null
                : android.content.ComponentName.unflattenFromString(comps.split(",")[0]);
        String mode = !on ? "off" : cn == null ? "" : cn.flattenToShortString();
        JSONArray modes = saverModes();
        boolean listed = false;
        for (int i = 0; i < modes.length(); i++) listed |= modes.getJSONObject(i).getString("id").equals(mode);
        if (!listed && !mode.isEmpty()) {
            modes.put(new JSONObject().put("id", mode).put("label", label(cn.getPackageName()))
                    .put("desc", "Set on the TV (" + mode + ")."));
        }
        int photos = Config.allPhotos(this).size();
        boolean ours = cn != null && on && cn.getPackageName().equals(getPackageName());
        int start = android.provider.Settings.System.getInt(cr, "screen_off_timeout", 0) / 60000;
        int sleep = android.provider.Settings.Secure.getInt(cr, "sleep_timeout", 0) / 60000;
        String note = ours && photos == 0 ? "Add photos under Background photos first; until then CanvasArt shows a short message."
                : "Starts after " + (start > 0 ? start + " minutes" : "a while") + " with no remote use on CanvasFrame, an app or a menu"
                  + " (not over the computer or another HDMI input)" + (sleep > 0 ? "; the TV goes to sleep after " + sleep + " minutes" : "")
                  + ". Any key ends it. The times are set in the TV's own settings.";
        return new JSONObject().put("supported", true).put("mode", mode).put("modes", modes)
                .put("photos", photos).put("note", note).put("previewEach", true);
    }

    private JSONObject setScreensaver(JSONObject req) throws IOException, JSONException {
        if (req.optBoolean("preview")) {
            android.content.ContentResolver cr = getContentResolver();
            String id = req.optString("id", "");
            String oldComps = android.provider.Settings.Secure.getString(cr, "screensaver_components");
            String oldOn = String.valueOf(android.provider.Settings.Secure.getInt(cr, "screensaver_enabled", 1));
            String want = null;
            if (!id.isEmpty()) {
                // Preview a particular screen saver: switch to it just long enough to start it, then put back the one
                // in use (the running screen saver keeps going; Android only reads the setting when one starts).
                if (id.equals("off") || !knownSaver(id) || !SAFE_COMPONENT.matcher(id).matches())
                    throw new IllegalArgumentException("That screen saver isn't installed");
                want = android.content.ComponentName.unflattenFromString(id).flattenToString();
                if (want.equals(oldComps) && oldOn.equals("1")) want = null;   // already the one in use
            } else if (oldOn.equals("0")) {
                throw new IllegalStateException("The screen saver is off: choose one first");
            }
            // Android won't start a screen saver over an HDMI input (Live TV keeps the screen awake), so show
            // CanvasFrame first; the preview then ends on the home screen.
            startUi(new Intent(this, HomeActivity.class));
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            if (want != null) { putSecure("screensaver_components", want); putSecure("screensaver_enabled", "1"); }
            try {
                // SystemUI's "Somnambulator" starts the chosen screen saver right away (what Settings' "Start now" does).
                // Started by the app itself it opens but no screen saver follows on the Hisense, so go through ADB's shell.
                if (shellOrNull("am start -n com.android.systemui/.Somnambulator") == null)
                    startUi(new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"));
                if (want != null) try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            } finally {
                if (want != null) {
                    // Only if the setting is still the preview's: a choice made meanwhile (dashboard "Use") must win. A preview
                    // that finished late once put Google's screen saver back over the user's new CanvasArt (2026-11-20).
                    String nowComps = android.provider.Settings.Secure.getString(cr, "screensaver_components");
                    if (want.equals(nowComps)) {
                        if (oldComps != null && !oldComps.isEmpty()) putSecure("screensaver_components", oldComps);
                        putSecure("screensaver_enabled", oldOn);
                    }
                }
            }
            return new JSONObject().put("ok", true);
        }
        String mode = req.getString("mode");
        if (!knownSaver(mode)) throw new IllegalArgumentException("That screen saver isn't installed");
        if (mode.equals("off")) {
            putSecure("screensaver_enabled", "0");
        } else {
            if (!SAFE_COMPONENT.matcher(mode).matches()) throw new IllegalArgumentException("bad screen saver id");
            String full = android.content.ComponentName.unflattenFromString(mode).flattenToString();
            putSecure("screensaver_components", full);
            putSecure("screensaver_enabled", "1");
        }
        return screensaver();
    }

    private boolean knownSaver(String id) throws JSONException {
        JSONArray modes = saverModes();
        for (int i = 0; i < modes.length(); i++) if (modes.getJSONObject(i).getString("id").equals(id)) return true;
        return false;
    }

    /** Writes a secure setting directly (WRITE_SECURE_SETTINGS) or, without that grant, through ADB. */
    private void putSecure(String key, String value) throws IOException, JSONException {
        if (canWriteSecure()) {
            android.provider.Settings.Secure.putString(getContentResolver(), key, value);
        } else {
            sh("settings put secure " + key + " '" + value.replace("'", "") + "'");
        }
    }

    // ---------------------------------------------------------------- shared site

    private void siteTick() {
        try {
            JSONObject identity = new JSONObject().put("name", deviceName(this)).put("brand", brand()).put("model", model())
                    .put("platform", "android").put("saver", true)   // can run CanvasArt
                    .put("photosIn", Config.prefs(this).getBoolean(Config.MESH_PHOTOS, true))   // takes photos from the mesh
                    .put("urls", new JSONArray().put(tlsReady ? "https://" + lanIp() + ":" + Tls.PORT : "http://" + lanIp() + ":" + PORT));
            // Sensors this TV has, for the Sync tab's chips: on = shared with the mesh now, off = switched off, unused = never used.
            JSONObject sensors = new JSONObject();
            if (Radar.available(this)) sensors.put("radar", Radar.enabled(this) ? "on" : "off");
            if ("hisense".equals(brand())) sensors.put("light", "on").put("mic", "unused");   // HSP light sensor; built-in mic
            identity.put("sensors", sensors);
            JSONObject m = macs();   // for Wake-on-LAN from the PC, the other TVs or Home Assistant
            if (m.length() > 0) identity.put("mac", m.optString("wifi".equals(m.optString("using")) ? "wifi" : "eth", m.optString("wifi"))).put("macs", m);
            Site.tick(this, identity, new Site.Hooks() {
                @Override public JSONObject env() throws Exception { return siteEnv(); }
                @Override public void command(JSONObject cmd, JSONObject view) { siteCommand(cmd, view); }
                @Override public void deletePhoto(String name) { meshDeletePhoto(name); }
                @Override public void applySettings(JSONObject values) { meshApplySettings(values); }
                @Override public void applyCalendars(JSONArray cals) { meshApplyCalendars(cals); }
            });
        } catch (Exception ignored) { }
    }

    // ---- the mesh changed something here (Site.reconcileMesh)
    private void meshDeletePhoto(String name) {
        File f = new File(Config.photosDir(this), new File(name).getName());
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        Config.setTags(this, f.getName(), null);
        //noinspection ResultOfMethodCallIgnored
        previewFile(f.getName()).delete();
        bumpPhotos();
    }

    private void meshApplySettings(JSONObject values) {
        try {
            JSONObject in = new JSONObject();
            for (String k : Site.MESH_SETTINGS) if (values.has(k)) in.put(k, values.get(k));
            if (in.optString(Config.WEATHER_LAT).isEmpty()) { in.remove(Config.WEATHER_LAT); in.remove(Config.WEATHER_LON); in.remove(Config.WEATHER_PLACE); }
            if (Config.update(this, in)) refreshWeatherAsync();
        } catch (Exception ignored) { }
    }

    private void meshApplyCalendars(JSONArray cals) {
        try {
            Config.updateCalendars(this, new JSONObject().put("action", "replace").put("calendars", cals));
            pool.execute(() -> { try { Config.fetchCalendars(this); } catch (Exception ignored) { } });
        } catch (Exception ignored) { }
    }

    /** This TV's live state for the site: screen on / screen saver / off, app in front, backlight and light (Hisense). */
    private JSONObject siteEnv() throws JSONException {
        JSONObject e = new JSONObject();
        boolean on = getSystemService(PowerManager.class).isInteractive();
        String apk = getApplicationInfo().sourceDir.replace("'", "");
        String out = shellOrNull("dumpsys dreams | grep -m1 mCurrentDreamName; echo @@; dumpsys activity activities | grep -m1 mResumedActivity;"
                + " echo @@; BOOTCLASSPATH=$BOOTCLASSPATH:/system/framework/com.hsp.rod.jar CLASSPATH='" + apk
                + "' app_process /system/bin com.canvasframe.home.Hsp env 2>&1; echo @@; dumpsys tv_input | grep -m1 'inputId:'");
        String[] sec = out == null ? new String[0] : out.split("@@");
        boolean dreaming = sec.length > 0 && sec[0].contains("ComponentInfo");
        e.put("screen", !on ? "off" : dreaming ? "saver" : "on");
        if (sec.length > 1) {
            Matcher m = Pattern.compile("u0 ([\\w.]+)/").matcher(sec[1]);
            if (m.find()) e.put("app", m.group(1));
        }
        if (sec.length > 2) {
            Matcher m = Pattern.compile("OK (\\d+) (-?\\d+)").matcher(sec[2]);
            if (m.find()) {
                e.put("backlight", Integer.parseInt(m.group(1))).put("light", Integer.parseInt(m.group(2)));
                RoomLight.own(Integer.parseInt(m.group(2)));   // CanvasArt's "Dim by room light" can use it
            }
        }
        String comps = android.provider.Settings.Secure.getString(getContentResolver(), "screensaver_components");
        e.put("saver", comps != null && comps.startsWith(getPackageName()) ? "canvasart" : comps == null ? "" : comps.split("/")[0]);
        // What's on screen, for the dashboards' "what each TV shows" wall.
        JSONObject shows = new JSONObject();
        String app = e.optString("app");
        boolean sync = Site.syncPlan(this) != null;
        if (!on) shows.put("kind", "off");
        else if (dreaming) {
            if (sec[0].contains(getPackageName())) {
                String ph = ArtDream.showing;
                shows.put("kind", "photo").put("sync", sync);
                if (ph != null) shows.put("photo", ph);
            } else shows.put("kind", "saver").put("label", sec[0].contains("dreamx") ? "Google Ambient mode" : "Screen saver");
        } else if (app.equals(getPackageName())) {
            shows.put("kind", "home").put("photo", HomeActivity.showing == null ? "" : HomeActivity.showing).put("sync", sync);
        } else if (app.equals("com.vt.tv.tvcenter") || (sec.length > 3 && sec[3].contains("inputId:"))) {
            String label = "TV input";
            Matcher m = Pattern.compile("([\\w.]+/[\\w.$]+/\\w+)").matcher(sec.length > 3 ? sec[3] : "");
            if (m.find()) {
                for (TvInputInfo info : getSystemService(TvInputManager.class).getTvInputList()) {
                    if (!info.getId().equals(m.group(1))) continue;
                    CharSequence custom = info.loadCustomLabel(this);
                    label = custom != null && custom.length() > 0 ? custom.toString().trim() : info.loadLabel(this).toString();
                }
            }
            shows.put("kind", "input").put("label", label);
        } else shows.put("kind", "app").put("label", app.isEmpty() ? "an app" : label(app));
        e.put("shows", shows);
        JSONObject presence = Radar.json();   // {state: someone|nobody|unknown, since, at, src: "radar", mode}
        if (presence != null) e.put("presence", presence);
        JSONObject st = photoStorage();   // room for photos from the mesh, and the storage bars on other TVs' dashboards
        e.put("free", st.optLong("free")).put("total", st.optLong("total")).put("photoBytes", st.optLong("used"));
        return e;
    }

    /** A command from the site. "identify": a note over whatever is on screen (also over the PC input) for 8 seconds. */
    private void siteCommand(JSONObject cmd, JSONObject view) {
        if ("copyphotos".equals(cmd.optString("action"))) {   // copy the photos TV cmd.to is missing, over this TV's link to it
            String pid = Site.peerFor(cmd.optString("to"));
            if (pid == null) return;
            try { Sync.run(this, new JSONArray().put(pid), new JSONObject().put("photos", true).put("show", false)); }
            catch (JSONException ignored) { }
            return;
        }
        if ("doorbell".equals(cmd.optString("action"))) { doorHere(cmd.optString("kind", "ring"), cmd.optString("event")); return; }
        if ("blescan".equals(cmd.optString("action"))) {   // a sensor search: this TV doesn't scan, but syncs fast so results arrive
            Site.fast(Math.min(cmd.optLong("until"), System.currentTimeMillis() + 130_000));
            return;
        }
        if ("signout".equals(cmd.optString("action"))) {   // "Sign out on all TVs" from another TV
            int n = Auth.revokeChain(this, cmd.optString("chain"));
            if (n > 0) android.util.Log.e("CanvasFrame", "signed out " + n + " session(s) (sign out on all TVs)");
            return;
        }
        if (!"identify".equals(cmd.optString("action"))) return;
        JSONObject items = view.optJSONObject("items");
        String me = Site.id(this);
        String name = deviceName(this), room = "";
        if (items != null) {
            JSONObject dev = items.optJSONObject("dev:" + me), pl = items.optJSONObject("place:" + me);
            if (dev != null && dev.optJSONObject("d") != null) name = dev.optJSONObject("d").optString("name", name);
            String rid = pl != null && pl.optJSONObject("d") != null ? pl.optJSONObject("d").optString("room") : "";
            JSONObject r = rid.isEmpty() ? null : items.optJSONObject("room:" + rid);
            if (r != null && r.optJSONObject("d") != null && !r.optBoolean("x")) room = r.optJSONObject("d").optString("name");
        }
        showNote(name + (room.isEmpty() ? " · no room set" : " · " + room));
    }

    private void showNote(String text) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> {
            try {
                android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
                float dp = getResources().getDisplayMetrics().density;
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setText(text);
                tv.setTextColor(0xFFF6F1EA);
                tv.setTextSize(22);
                tv.setPadding(Math.round(28 * dp), Math.round(14 * dp), Math.round(28 * dp), Math.round(14 * dp));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xE6181818);
                bg.setCornerRadius(40 * dp);
                bg.setStroke(Math.round(2 * dp), 0xCCE8C99A);
                tv.setBackground(bg);
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                lp.y = Math.round(36 * dp);
                wm.addView(tv, lp);
                main.postDelayed(() -> { try { wm.removeView(tv); } catch (Exception ignored) { } }, 8000);
            } catch (Exception ignored) { /* no "display over other apps" permission */ }
        });
    }

    private boolean canWriteSecure() {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Z0-9_]+");

    private JSONObject key(String key) throws IOException, JSONException {
        if (!SAFE_KEY.matcher(key).matches()) throw new IllegalArgumentException("bad key");
        return sh("input keyevent KEYCODE_" + key);
    }

    private JSONObject volume(JSONObject req) throws JSONException {
        AudioManager am = getSystemService(AudioManager.class);
        if (req.has("level")) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, req.getInt("level"), AudioManager.FLAG_SHOW_UI);
        } else if (req.has("mute")) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    req.getBoolean("mute") ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
        }
        return new JSONObject().put("ok", true).put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    private static final Pattern SAFE_PKG = Pattern.compile("[A-Za-z0-9_.]+(/[A-Za-z0-9_.$]+)?");

    private JSONObject launch(String pkg) throws IOException, JSONException {
        if (!SAFE_PKG.matcher(pkg).matches()) throw new IllegalArgumentException("bad package");
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null || i.getComponent() == null) throw new IllegalArgumentException("not launchable");
        return startUi(i);
    }

    private JSONObject apps() throws JSONException {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> list = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
        List<JSONObject> rows = new ArrayList<>();
        for (ApplicationInfo ai : list) {
            if (ai.packageName.contains("auto_generated_rro") || ai.packageName.contains(".overlay")) continue;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            JSONObject r = new JSONObject()
                    .put("pkg", ai.packageName)
                    .put("label", ai.loadLabel(pm).toString())
                    .put("enabled", ai.enabled)
                    .put("system", system)
                    .put("protected", isProtected(ai.packageName))
                    .put("launchable", pm.getLeanbackLaunchIntentForPackage(ai.packageName) != null
                            || pm.getLaunchIntentForPackage(ai.packageName) != null);
            if (SUGGESTED.containsKey(ai.packageName)) r.put("suggested", SUGGESTED.get(ai.packageName));
            if (ai.packageName.equals(GTV_LAUNCHER)) r.put("note", "Disabling makes CanvasFrame the Home screen");
            rows.add(r);
        }
        Collections.sort(rows, (a, b) -> {
            int sa = a.has("suggested") ? 0 : 1, sb = b.has("suggested") ? 0 : 1;
            if (sa != sb) return sa - sb;
            return a.optString("label").compareToIgnoreCase(b.optString("label"));
        });
        return new JSONObject().put("apps", new JSONArray(rows));
    }

    private JSONObject setApp(String pkg, boolean enabled) throws IOException, JSONException {
        if (!SAFE_PKG.matcher(pkg).matches()) throw new IllegalArgumentException("bad package");
        if (isProtected(pkg)) throw new IllegalArgumentException(pkg + " is protected");
        String out = adb.shell(enabled ? "pm enable " + pkg : "pm disable-user --user 0 " + pkg);
        if (pkg.equals(GTV_LAUNCHER)) {
            // With launcherx gone, Google's setup app claims Home with a recovery screen; toggle it alongside.
            out += adb.shell((enabled ? "pm enable " : "pm disable-user --user 0 ") + GTV_SETUP);
            if (!enabled) adb.shell("cmd package set-home-activity com.canvasframe.home/.HomeActivity");
        }
        return new JSONObject().put("ok", out.contains("new state")).put("output", out.trim());
    }

    private JSONObject privacy() throws JSONException {
        JSONObject o = new JSONObject();
        String mode = android.provider.Settings.Global.getString(getContentResolver(), "private_dns_mode");
        String host = android.provider.Settings.Global.getString(getContentResolver(), "private_dns_specifier");
        o.put("dnsMode", mode == null ? "null" : mode);
        o.put("dnsHost", host == null ? "" : host);
        o.put("dnsWritable", canWriteSecure());
        String out = shellOrNull("pm dump " + ART_PKG + " | grep -A8 'disabledComponents' | grep -c UpgradeImageService");
        o.put("adb", adb.state().name());
        if (out != null) o.put("artUpdateBlocked", !out.trim().equals("0"));
        try {
            o.put("acrDisabled", !getPackageManager().getApplicationInfo(ACR_PKG, PackageManager.MATCH_DISABLED_COMPONENTS).enabled);
        } catch (PackageManager.NameNotFoundException e) {
            o.put("acrDisabled", true);
        }
        return o;
    }

    private static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9.-]{3,253}");

    /** Network tab: connection, DNS (servers from the router, read-only; Private DNS is on the Privacy tab) and NTP. */
    private JSONObject network() throws JSONException {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        android.net.Network n = cm.getActiveNetwork();
        android.net.LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        boolean wifi = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        boolean eth = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET);
        JSONObject conn = new JSONObject().put("type", wifi ? "wifi" : eth ? "wired" : "other").put("ip", lanIp());
        if (wifi) {
            WifiInfo wi = getSystemService(WifiManager.class).getConnectionInfo();
            String ssid = wi == null ? "" : String.valueOf(wi.getSSID()).replaceAll("^\"|\"$", "");
            conn.put("name", ssid.equals("<unknown ssid>") ? "" : ssid);   // the network name needs location access
        } else conn.put("name", eth ? "Ethernet" : "");
        JSONArray servers = new JSONArray();
        if (lp != null) {
            for (java.net.InetAddress a : lp.getDnsServers()) servers.put(a.getHostAddress());
            for (android.net.RouteInfo ri : lp.getRoutes()) if (ri.isDefaultRoute() && ri.getGateway() != null) { conn.put("gateway", ri.getGateway().getHostAddress()); break; }
        }
        android.content.ContentResolver cr = getContentResolver();
        String pmode = android.provider.Settings.Global.getString(cr, "private_dns_mode");
        String phost = android.provider.Settings.Global.getString(cr, "private_dns_specifier");
        JSONObject dns = new JSONObject().put("servers", servers).put("manual", JSONObject.NULL).put("editable", false)
                .put("privateDns", "hostname".equals(pmode) && phost != null ? phost : "")
                .put("privateDnsActive", lp != null && lp.isPrivateDnsActive())
                .put("note", "Android sets these from your router; changing them needs root. Private DNS (Privacy tab) sends lookups to a DNS server you choose instead.");
        String ntp = android.provider.Settings.Global.getString(cr, "ntp_server");
        int auto = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.AUTO_TIME, 1);
        JSONObject time = new JSONObject().put("source", auto == 1 ? "ntp" : "manual")
                .put("sourceLabel", auto == 1 ? "Network time (NTP)" : "Set by hand")
                .put("timezone", java.util.TimeZone.getDefault().getID()).put("valid", true)
                .put("ntpEditable", true).put("ntpServer", ntp == null ? "" : ntp).put("ntpDefault", "time.android.com")
                .put("note", auto == 1 ? "" : "Automatic date & time is off in the TV's settings, so the time server isn't used.");
        return new JSONObject().put("connection", conn).put("dns", dns).put("time", time);
    }

    private JSONObject setNetwork(JSONObject req) throws IOException, JSONException {
        if (req.has("dns")) throw new IllegalArgumentException("Android TVs take DNS servers from the router; use Private DNS on the Privacy tab");
        if (req.has("ntp")) {
            String host = req.optString("ntp", "").trim();
            if (!host.isEmpty() && !SAFE_HOST.matcher(host).matches()) throw new IllegalArgumentException("That isn't a valid server name");
            if (canWriteSecure()) android.provider.Settings.Global.putString(getContentResolver(), "ntp_server", host.isEmpty() ? null : host);
            else adb.shell(host.isEmpty() ? "settings delete global ntp_server" : "settings put global ntp_server " + host);
            // Ask Android to fetch the time now (not on every version; harmless if missing).
            try { adb.shell("cmd network_time_update_service force_refresh 2>/dev/null"); } catch (IOException ignored) { }
        }
        return network();
    }

    private JSONObject setPrivacy(JSONObject req) throws IOException, JSONException {
        StringBuilder log = new StringBuilder();
        if (req.has("dnsHost")) {
            String host = req.getString("dnsHost").trim();
            if (!host.isEmpty() && !SAFE_HOST.matcher(host).matches()) throw new IllegalArgumentException("bad host");
            if (canWriteSecure()) {
                android.content.ContentResolver cr = getContentResolver();
                if (host.isEmpty()) {
                    android.provider.Settings.Global.putString(cr, "private_dns_mode", "opportunistic");
                } else {
                    android.provider.Settings.Global.putString(cr, "private_dns_specifier", host);
                    android.provider.Settings.Global.putString(cr, "private_dns_mode", "hostname");
                }
            } else if (host.isEmpty()) {
                log.append(adb.shell("settings put global private_dns_mode opportunistic; settings delete global private_dns_specifier"));
            } else {
                log.append(adb.shell("settings put global private_dns_specifier " + host
                        + "; settings put global private_dns_mode hostname"));
            }
        }
        if (req.has("acr")) {
            log.append(adb.shell(req.getBoolean("acr") ? "pm enable " + ACR_PKG : "pm disable-user --user 0 " + ACR_PKG));
        }
        if (req.has("artUpdate")) {
            String r = adb.shell((req.getBoolean("artUpdate") ? "pm enable " : "pm disable ") + ART_UPDATE + " 2>&1");
            if (r.contains("SecurityException")) {
                throw new IllegalStateException("Android 12 doesn't let ADB switch off part of a system app, so this "
                        + "can't be blocked on the TV. Press 'Update now' once on the TV, or block hismarttv.com "
                        + "and downloand.hisense.com on your router.");
            }
            log.append(r);
        }
        return privacy().put("output", log.toString().trim());
    }

    // ================================================================== background photos

    private static final long MAX_UPLOAD = 40L * 1024 * 1024;
    private static final int MAX_W = 3840, MAX_H = 2160;

    /** Only plain file names inside the uploads folder; no paths. */
    private File photoFile(String name) {
        if (name == null || !name.matches("[A-Za-z0-9._ -]{1,120}") || name.startsWith(".")) {
            throw new IllegalArgumentException("bad file name");
        }
        File f = new File(Config.photosDir(this), name);
        if (!f.exists()) throw new IllegalArgumentException("no such photo");
        return f;
    }

    private void bumpPhotos() {
        Config.prefs(this).edit().putLong(Config.PHOTOS_VER, System.currentTimeMillis()).apply();
    }

    /** Streams the request body to a temp file, then stores it as a JPEG no larger than 4K. */
    private JSONObject upload(InputStream is, long length, String rawName) throws IOException, JSONException {
        if (length <= 0) throw new IllegalArgumentException("empty upload");
        if (length > MAX_UPLOAD) throw new IllegalArgumentException("photo is larger than 40 MB");
        String original = rawName == null ? "photo" : java.net.URLDecoder.decode(rawName, "UTF-8");
        File dir = Config.photosDir(this);
        File tmp = new File(getCacheDir(), "upload-" + System.nanoTime());
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long left = length;
            while (left > 0) {
                int r = is.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) break;
                fo.write(buf, 0, r);
                left -= r;
            }
        }
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(tmp.getPath(), o);
            if (o.outWidth <= 0) throw new IllegalArgumentException("not an image this TV can read");
            int sample = 1;
            while (o.outWidth / (sample * 2) >= MAX_W || o.outHeight / (sample * 2) >= MAX_H) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(tmp.getPath(), o);
            if (bmp == null) throw new IllegalArgumentException("couldn't decode image");
            bmp = applyExifRotation(tmp, bmp);
            // Exact resize so the long edge is at most 3840 and the short edge at most 2160 (4K).
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            double scale = Math.min(1.0, Math.min((double) MAX_W / Math.max(bw, bh), (double) MAX_H / Math.min(bw, bh)));
            if (scale < 1.0) {
                android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(bmp,
                        (int) Math.round(bw * scale), (int) Math.round(bh * scale), true);
                if (s != bmp) bmp.recycle();
                bmp = s;
            }
            String base = original.replaceAll("\\.[A-Za-z0-9]+$", "").replaceAll("[^A-Za-z0-9 _-]", "").trim();
            if (base.isEmpty()) base = "photo";
            if (base.length() > 60) base = base.substring(0, 60);
            File out = new File(dir, base + ".jpg");
            for (int i = 2; out.exists(); i++) out = new File(dir, base + " " + i + ".jpg");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fo);
            }
            int w = bmp.getWidth(), h = bmp.getHeight();
            bmp.recycle();
            bumpPhotos();
            return new JSONObject().put("ok", true).put("name", out.getName()).put("width", w).put("height", h);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Phone photos are often stored sideways with an EXIF rotation tag; bake the rotation in. */
    private static android.graphics.Bitmap applyExifRotation(File f, android.graphics.Bitmap bmp) {
        try {
            android.media.ExifInterface ex = new android.media.ExifInterface(f.getPath());
            int rot;
            switch (ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
                default: return bmp;
            }
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(rot);
            android.graphics.Bitmap r = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            return r;
        } catch (Exception e) {
            return bmp;
        }
    }

    private JSONObject listPhotos() throws JSONException {
        JSONArray arr = new JSONArray();
        File[] files = Config.photosDir(this).listFiles((d, n) -> Config.isImageName(n));
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) {
                File pv = previewFile(f.getName());
                java.util.Set<String> tags = Config.tagsFor(this, f.getName());
                arr.put(new JSONObject().put("name", f.getName()).put("size", f.length())
                        .put("tags", new JSONArray(tags))
                        .put("fitsNow", Config.fitsNow(this, tags))
                        .put("holidayPick", Config.isHolidayPick(tags))
                        .put("preview", pv.isFile() ? pv.lastModified() : 0));
            }
        }
        File[] legacy = new File("/sdcard/Pictures/CanvasFrame").listFiles((d, n) -> Config.isImageName(n));
        return new JSONObject().put("photos", arr).put("sdcardCount", legacy == null ? 0 : legacy.length)
                .put("period", Config.currentPeriod(this))
                .put("season", Config.currentSeason())
                .put("holidays", new JSONArray(Config.currentHolidays()))
                .put("storage", photoStorage());
    }

    /** Space the photos take (with their capture previews) and what's left on that storage, for the dashboard. */
    private JSONObject photoStorage() throws JSONException {
        File dir = Config.photosDir(this);
        long used = 0;
        for (File d : new File[]{dir, new File(dir, "previews")}) {
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) if (f.isFile()) used += f.length();
        }
        StatFs fs = new StatFs(dir.getPath());
        return new JSONObject().put("used", used).put("free", fs.getAvailableBytes()).put("total", fs.getTotalBytes());
    }

    /** Captured home-screen previews live beside the photos, one PNG per photo. */
    private File previewFile(String photoName) {
        File d = new File(Config.photosDir(this), "previews");
        if (!d.exists()) d.mkdirs();
        return new File(d, photoName + ".png");
    }

    private final Object captureLock = new Object();

    /**
     * Shows the photo on the CanvasFrame home screen, waits for it to fade in, captures the screen
     * (with the clock, weather and tiles), then switches back to the computer input if that's where we were.
     */
    private JSONObject capturePreview(File photo) throws IOException, JSONException {
        synchronized (captureLock) {
            String before = shellOrNull("dumpsys activity activities | grep -m1 mResumedActivity");
            boolean wasHome = before != null && before.contains(getPackageName());

            Intent home = new Intent(this, HomeActivity.class)
                    .putExtra(HomeActivity.EXTRA_SHOW_PHOTO, photo.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            SystemClock.sleep(3500); // activity resume + decode + 1.2 s fade-in

            byte[] png = adb.shellBytes("screencap -p");
            String back = null;
            if (!wasHome) back = returnToComputer();
            if (png.length < 8 || (png[0] & 0xff) != 0x89) {
                throw new IOException("capture failed" + (png.length == 0 ? " (screen is protected)" : ""));
            }
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(previewFile(photo.getName()))) {
                fo.write(png);
            }
            return new JSONObject().put("ok", true).put("name", photo.getName())
                    .put("preview", previewFile(photo.getName()).lastModified())
                    .put("returnedTo", back == null ? JSONObject.NULL : back);
        }
    }

    /** Switches to the input labelled "computer" (or the first connected HDMI input). Returns its label. */
    private String returnToComputer() {
        TvInputManager tim = getSystemService(TvInputManager.class);
        TvInputInfo pick = null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
            CharSequence custom = info.loadCustomLabel(this);
            if (custom != null && custom.toString().trim().equalsIgnoreCase("computer")) { pick = info; break; }
            if (pick == null && tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) pick = info;
        }
        if (pick == null) return null;
        startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(pick.getId()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CharSequence l = pick.loadCustomLabel(this);
        return l != null && l.length() > 0 ? l.toString() : pick.loadLabel(this).toString();
    }

    /** Serves an uploaded photo; thumb=1 returns a small JPEG for the dashboard grid. */
    private void servePhoto(OutputStream os, String query) throws IOException {
        try {
            File f = photoFile(java.net.URLDecoder.decode(param(query, "name"), "UTF-8"));
            if ("1".equals(param(query, "preview"))) {
                f = previewFile(f.getName());
                if (!f.isFile()) throw new IllegalArgumentException("no preview");
            }
            if ("1".equals(param(query, "thumb"))) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 480) sample *= 2;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = sample;
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bo);
                b.recycle();
                respond(os, 200, "image/jpeg", bo.toByteArray());
            } else {
                byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                respond(os, 200, f.getName().endsWith(".png") ? "image/png" : "image/jpeg", data);
            }
        } catch (IllegalArgumentException e) {
            respond(os, 404, "text/plain", "not found".getBytes());
        }
    }

    // ================================================================== settings & weather

    private final java.util.concurrent.ScheduledExecutorService weatherTimer =
            Executors.newSingleThreadScheduledExecutor();
    /** Sync rounds every 3 s while a sensor search runs (Site.fastUntil). */
    private final java.util.concurrent.ScheduledExecutorService fastTimer = Executors.newSingleThreadScheduledExecutor();

    private void refreshWeatherAsync() {
        weatherTimer.execute(() -> {
            try { Config.fetchWeather(this); } catch (Exception ignored) { }
        });
    }

    private JSONObject settings() throws JSONException {
        JSONObject o = Config.toJson(this);
        if (Radar.available(this)) {   // only TVs with Hisense's art-mode radar get the switch
            o.put(Radar.PREF, Radar.enabled(this));
            JSONObject r = Radar.json();
            if (r != null) o.put("radar_now", r);
        }
        // Hidden apps with labels, so the dashboard can show names.
        JSONArray hidden = new JSONArray();
        JSONArray pk = o.getJSONArray(Config.HIDDEN);
        for (int i = 0; i < pk.length(); i++) {
            hidden.put(new JSONObject().put("pkg", pk.getString(i)).put("label", label(pk.getString(i))));
        }
        o.put(Config.HIDDEN, hidden);
        String w = Config.prefs(this).getString(Config.WEATHER_CACHE, null);
        if (w != null) o.put("weather", new JSONObject(w));
        // Calendars: links are private, so only a shortened form goes back to the browser.
        JSONArray cals = Config.calendars(this), shown = new JSONArray();
        JSONObject status;
        try { status = new JSONObject(Config.prefs(this).getString(Config.CAL_STATUS, "{}")); } catch (JSONException e) { status = new JSONObject(); }
        for (int i = 0; i < cals.length(); i++) {
            JSONObject cal = cals.getJSONObject(i);
            String url = cal.optString("url");
            String host = url.replaceFirst("^https?://", "");
            int slash = host.indexOf('/');
            String shortUrl = (slash > 0 ? host.substring(0, slash) : host) + "/…" + (url.length() > 8 ? url.substring(url.length() - 8) : "");
            shown.put(new JSONObject().put("id", cal.optString("id")).put("name", cal.optString("name"))
                    .put("color", cal.optString("color")).put("on", cal.optBoolean("on", true))
                    .put("link", shortUrl).put("status", status.optJSONObject(cal.optString("id"))));
        }
        o.put("calendars", shown);
        JSONArray evs = Config.cachedEvents(this), upcoming = new JSONArray();
        long now = System.currentTimeMillis();
        if (evs != null) {
            for (int i = 0; i < evs.length() && upcoming.length() < 8; i++) {
                JSONObject ev = evs.getJSONObject(i);
                if (ev.getLong("e") > now || (ev.getBoolean("a") && ev.getLong("s") + 86_400_000L > now)) upcoming.put(ev);
            }
        }
        o.put("upcoming", upcoming);
        return o;
    }

    private static String param(String query, String name) {
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) return kv.substring(e + 1);
        }
        return "";
    }

    // ================================================================== helpers

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "tv";
    }
}
