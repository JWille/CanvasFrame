using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

namespace CanvasFrameLink
{
    /// <summary>
    /// The tray app: polls the TVs (one added TV's shared site gives every TV's state; each added TV's /api/status gives its
    /// details, e.g. whether CanvasFrame may use the TV's debugging), shows them in the tray menu and the window, and, when
    /// "Monitor for this PC" is set and the service doesn't already do it, reports this PC's display to that TV.
    /// </summary>
    sealed class TrayApp : ApplicationContext
    {
        public static TrayApp I;
        readonly NotifyIcon icon;
        readonly ContextMenuStrip menu;
        readonly Control ui;            // for marshalling onto the UI thread
        readonly PowerWindow power;
        readonly Sender sender;
        readonly AutoResetEvent kick = new AutoResetEvent(false);
        MainForm form;

        // Poll results (guarded by sync)
        readonly object sync = new object();
        Dictionary<string, object> site;      // the shared site, from the first added TV that answered
        string siteFrom = "";
        long siteSkew;                         // PC clock - TV clock (ms)
        readonly Dictionary<string, Live> live = new Dictionary<string, Live>(StringComparer.OrdinalIgnoreCase);   // by url
        readonly HashSet<string> adbWarned = new HashSet<string>();
        (bool installed, bool running, string host, string tv) svc;

        class Live { public bool Reachable; public string Error = ""; public Dictionary<string, object> Status; public DateTime At; }

        public TrayApp(EventWaitHandle show, EventWaitHandle quit, bool showNow)
        {
            I = this;
            Store.Load();
            ui = new Control(); ui.CreateControl(); var h = ui.Handle;

            menu = new ContextMenuStrip();
            menu.Opening += (s, e) => { BuildMenu(); e.Cancel = false; };
            icon = new NotifyIcon
            {
                Icon = AppIcon(),
                Text = "CanvasFrame",
                ContextMenuStrip = menu,
                Visible = true,
            };
            icon.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) ShowWindow(); };
            icon.BalloonTipClicked += (s, e) => ShowWindow();

            ThreadPool.RegisterWaitForSingleObject(show, (o, t) => Ui(() => ShowWindow()), null, -1, false);
            ThreadPool.RegisterWaitForSingleObject(quit, (o, t) => Ui(Exit), null, -1, true);

            sender = new Sender(() => MonitorTv()?.Url ?? "", () => MonitorTv()?.Key ?? "", () => MonitorTv()?.Mac ?? "", Log);
            power = new PowerWindow(OnDisplay);
            SystemEvents.SessionSwitch += (s, e) =>
            {
                if (e.Reason == SessionSwitchReason.SessionUnlock || e.Reason == SessionSwitchReason.SessionLogon) MonitorEvent("on", "unlock");
            };
            SystemEvents.SessionEnding += (s, e) => { if (TrayMonitors()) sender.SendNow("off", "signing out / shutting down"); };

            ApplyWithWindows();
            new Thread(PollLoop) { IsBackground = true, Name = "poll" }.Start();
            if (showNow || Store.AddedTvs().Count == 0) ShowWindow();
        }

        public void Ui(Action a) { if (ui.IsHandleCreated) ui.BeginInvoke(a); }

        static Icon AppIcon()
        {
            try { return Icon.ExtractAssociatedIcon(System.Diagnostics.Process.GetCurrentProcess().MainModule.FileName); }
            catch { return SystemIcons.Application; }
        }

        public static void Log(string msg)
        {
            try
            {
                Directory.CreateDirectory(Store.Dir);
                string p = Path.Combine(Store.Dir, "app.log");
                FileInfo f = new FileInfo(p);
                if (f.Exists && f.Length > 512 * 1024) { File.Delete(p + ".old"); File.Move(p, p + ".old"); }
                File.AppendAllText(p, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + msg + Environment.NewLine);
            }
            catch { }
        }

        public void ShowWindow()
        {
            if (form == null || form.IsDisposed) form = new MainForm();
            form.ShowAndActivate();
        }

        public void Exit()
        {
            icon.Visible = false;
            power.DestroyHandle();
            if (form != null && !form.IsDisposed) { form.ReallyClose = true; form.Close(); }
            ExitThread();
        }

        // ------------------------------------------------------------------ monitor for this PC

        public TvEntry MonitorTv() { return Store.Find(Store.S.Monitor); }

        /// <summary>The service does it when it's running for the same TV (it also covers the login screen).</summary>
        public bool ServiceMonitors()
        {
            var m = MonitorTv();
            return svc.running && m != null && string.Equals(svc.host.TrimEnd('/'), m.Url.TrimEnd('/'), StringComparison.OrdinalIgnoreCase);
        }
        bool TrayMonitors() { return MonitorTv() != null && !ServiceMonitors(); }

        void OnDisplay(int state, string why)
        {
            if (state == 1) MonitorEvent("on", why);
            else if (state == 0) MonitorEvent("off", why);
            else if (state == -2 && TrayMonitors()) sender.SendNow("off", why);   // sleep: right away
        }

        void MonitorEvent(string ev, string why) { if (TrayMonitors()) sender.Queue(ev, why); }

        public Dictionary<string, object> MonitorState()
        {
            var m = MonitorTv();
            return new Dictionary<string, object>
            {
                { "id", m == null ? "" : (m.Id != "" ? m.Id : m.Url) },
                { "name", m == null ? "" : m.Name },
                { "by", m == null ? "none" : ServiceMonitors() ? "service" : "app" },
                { "service", new Dictionary<string, object> { { "installed", svc.installed }, { "running", svc.running }, { "host", svc.host }, { "tv", svc.tv } } },
                { "last", sender.LastResult },
                { "lastAt", sender.LastResult == "" ? "" : sender.LastResultAt.ToString("g") },
            };
        }

        public void SetMonitor(string id)
        {
            var t = Store.Find(id);
            Store.S.Monitor = t == null ? "" : (t.Id != "" ? t.Id : t.Url);
            Store.Save();
            if (t != null) sender.Queue("on", "chosen as this PC's monitor");   // the PC's display is on right now
        }

        /// <summary>Installs (on) or removes (off) the service for the monitor TV; asks for admin rights (UAC).</summary>
        public string SetService(bool on)
        {
            string res;
            if (on)
            {
                var m = MonitorTv();
                if (m == null) throw new Exception("Choose the TV this PC uses as its monitor first");
                string tmp = Path.Combine(Path.GetTempPath(), "cfl-" + Guid.NewGuid().ToString("N") + ".txt");
                File.WriteAllText(tmp, m.Key);
                var r = Service.Elevated("install \"" + m.Url + "\" --keyfile \"" + tmp + "\"");
                try { File.Delete(tmp); } catch { }
                if (r.code != 0) throw new Exception(r.output == "" ? "Installing the service failed (" + r.code + ")" : r.output);
                res = r.output;
            }
            else
            {
                var r = Service.Elevated("uninstall");
                if (r.code != 0) throw new Exception(r.output == "" ? "Removing the service failed (" + r.code + ")" : r.output);
                res = r.output;
            }
            svc = Service.State();
            return res;
        }

        // ------------------------------------------------------------------ start with Windows

        const string RUN = @"Software\Microsoft\Windows\CurrentVersion\Run";
        public void ApplyWithWindows()
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.CreateSubKey(RUN))
                {
                    if (Store.S.WithWindows) k.SetValue("CanvasFrame Link", "\"" + Program.ExePath + "\"");
                    else k.DeleteValue("CanvasFrame Link", false);
                }
            }
            catch (Exception e) { Log("Start with Windows: " + e.Message); }
        }

        // ------------------------------------------------------------------ adding TVs

        /// <summary>Adds a TV by address (+ API key, or none: then one is made for this PC, which works while the TV has no
        /// sign-in). Returns the entry. Throws with a message for the user.</summary>
        public TvEntry AddTv(string address, string key)
        {
            string url = Net.BaseUrl(address);
            string fp;
            try { fp = Net.FirstContact(url); }
            catch (Exception e) { throw new Exception("No CanvasFrame TV answered at " + url + " (" + (e.InnerException?.Message ?? e.Message) + "). Check the address and that the TV is on."); }
            var hint = Setup.ReadHint();
            if (hint != null && hint.Fp != "" && string.Equals(Net.Authority(hint.Url), Net.Authority(url), StringComparison.OrdinalIgnoreCase) && hint.Fp != fp)
                throw new Exception("The TV at " + url + " shows a different certificate than the one this download came with. If the TV was reset, " +
                    "download CanvasFrame Link from its dashboard again; otherwise something on the network may be intercepting the connection.");
            Net.Pins[Net.Authority(url)] = fp;
            key = (key ?? "").Trim();
            if (key != "" && !key.StartsWith("cfk_")) throw new Exception("An API key starts with cfk_ (make one on the TV's dashboard: Privacy → API keys)");
            if (key == "")
            {
                try
                {
                    string name = "CanvasFrame Link (" + Environment.MachineName + ")";
                    var d = Net.Json(url + "/api/auth/key-create", null, 8000, "POST", new Dictionary<string, object> { { "name", name.Length > 40 ? name.Substring(0, 40) : name } });
                    key = Net.Str(Net.Obj(d, "newKey"), "key");
                }
                catch (TvException e) when (e.Status == 401 || e.Status == 403)
                {
                    throw new Exception("This TV's dashboard has sign-in on, so it needs an API key: make one on its dashboard (Privacy → API keys) and paste it here.");
                }
                if (!key.StartsWith("cfk_")) throw new Exception("The TV didn't make an API key. Make one on its dashboard (Privacy → API keys) and paste it here.");
            }
            Dictionary<string, object> st;
            try { st = Net.Json(url + "/api/status", key, 8000); }
            catch (TvException e) when (e.Status == 401 || e.Status == 403) { throw new Exception("The TV didn't accept that API key."); }
            string id = "";
            try { id = Net.Str(Net.Json(url + "/api/site", key, 8000), "self"); } catch { }
            var t = Store.Find(id) ?? Store.Find(url) ?? new TvEntry();
            t.Id = id; t.Url = url; t.Pin = fp; t.Key = key; t.Added = true;
            t.Name = Net.Str(st, "name"); t.Platform = Net.Str(st, "platform"); t.Brand = Net.Str(st, "brand");
            Store.Put(t);
            Store.Save();
            lock (sync) live[url] = new Live { Reachable = true, Status = st, At = DateTime.UtcNow };
            kick.Set();
            return t;
        }

        public void RemoveTv(string id)
        {
            var t = Store.Find(id);
            if (t == null) return;
            if (MonitorTv() == t) Store.S.Monitor = "";
            t.Added = false; t.Key = "";
            Store.Save();
            kick.Set();
        }

        // ------------------------------------------------------------------ polling

        void PollLoop()
        {
            while (true)
            {
                try { Poll(); } catch (Exception e) { Log("poll: " + e.Message); }
                kick.WaitOne(form != null && form.Visible ? 5000 : 15000);
            }
        }

        public void Kick() { kick.Set(); }

        void Poll()
        {
            svc = Service.State();
            var added = Store.AddedTvs();
            bool changed = false;
            foreach (var t in added)
            {
                Live l;
                lock (sync) live.TryGetValue(t.Url, out l);
                if (l != null && l.Reachable && (DateTime.UtcNow - l.At).TotalSeconds < 25) continue;
                var n = new Live { At = DateTime.UtcNow };
                try
                {
                    n.Status = Net.Json(t.Url + "/api/status", t.Key, 6000); n.Reachable = true;
                    string nm = Net.Str(n.Status, "name");
                    if (nm != "" && nm != t.Name) { t.Name = nm; changed = true; }
                    if (t.Platform != Net.Str(n.Status, "platform")) { t.Platform = Net.Str(n.Status, "platform"); t.Brand = Net.Str(n.Status, "brand"); changed = true; }
                }
                catch (Exception e) { n.Error = e is TvException ? e.Message : "not reachable"; }
                lock (sync) live[t.Url] = n;
                CheckAdb(t, n);
            }

            // The shared site: from the first added TV that answers (reachable ones first).
            Dictionary<string, object> s = null; string from = "";
            foreach (var t in added.OrderByDescending(x => { Live l; lock (sync) live.TryGetValue(x.Url, out l); return l != null && l.Reachable; }))
            {
                try
                {
                    s = Net.Json(t.Url + "/api/site", t.Key, 6000); from = t.Name;
                    string self = Net.Str(s, "self");
                    if (self != "" && t.Id != self) { t.Id = self; changed = true; }
                    break;
                }
                catch { }
            }
            if (s != null)
            {
                changed |= Discover(s);
                lock (sync) { site = s; siteFrom = from; siteSkew = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - Net.Num(s, "now"); }
            }
            if (changed) Store.Save();
            Ui(UpdateTip);
        }

        /// <summary>Every TV in the site gets an entry (address + certificate pin, recorded on first contact) so the window
        /// can show and open it, even without a key of its own.</summary>
        bool Discover(Dictionary<string, object> s)
        {
            bool changed = false;
            var items = Net.Obj(s, "items");
            if (items == null) return false;
            foreach (var kv in items)
            {
                if (!kv.Key.StartsWith("dev:")) continue;
                var it = kv.Value as Dictionary<string, object>;
                if (it == null || Net.Bool(it, "x")) continue;
                var d = Net.Obj(it, "d");
                string id = kv.Key.Substring(4);
                var urls = d != null && d.ContainsKey("urls") ? d["urls"] as System.Collections.ArrayList : null;
                string url = urls != null && urls.Count > 0 ? Convert.ToString(urls[0]) : "";
                if (url == "" || !url.StartsWith("https://")) continue;
                var t = Store.Find(id) ?? Store.Find(url);
                if (t == null) t = new TvEntry { Id = id, Url = url, Added = false };
                bool fresh = string.IsNullOrEmpty(t.Pin) || !string.Equals(t.Url, url, StringComparison.OrdinalIgnoreCase);
                if (fresh)
                {
                    try { t.Pin = Net.FirstContact(url); t.Url = url; } catch { continue; }   // not reachable from here: try next round
                }
                if (t.Id != id || t.Name != Net.Str(d, "name") || t.Platform != Net.Str(d, "platform") || (t.Mac ?? "") != Net.Str(d, "mac"))
                {
                    t.Id = id; t.Name = Net.Str(d, "name"); t.Platform = Net.Str(d, "platform"); t.Brand = Net.Str(d, "brand"); t.Mac = Net.Str(d, "mac"); fresh = true;
                }
                if (fresh) { Store.Put(t); changed = true; }
            }
            return changed;
        }

        /// <summary>CanvasFrame on Android TVs needs the TV's debugging allowed (it talks to the TV's own ADB). When that's not
        /// the case, say so once (the window shows the step-by-step dialog).</summary>
        void CheckAdb(TvEntry t, Live l)
        {
            string adb = l.Status == null ? "" : Net.Str(l.Status, "adb");
            bool bad = l.Reachable && t.Platform == "android" && adb != "" && adb != "CONNECTED";
            lock (sync)
            {
                if (!bad) { adbWarned.Remove(t.Url); return; }
                if (!adbWarned.Add(t.Url)) return;
            }
            Ui(() => icon.ShowBalloonTip(8000, t.Name + " needs debugging allowed",
                "CanvasFrame on this TV can't switch inputs or change the picture until debugging is on. Click for the steps.", ToolTipIcon.Warning));
        }

        void UpdateTip()
        {
            var tvs = TvList();
            int n = tvs.Count, on = tvs.Count(t => t.state != "away");
            string tip = "CanvasFrame · " + (n == 0 ? "no TVs yet" : on + " of " + n + " TV" + (n == 1 ? "" : "s") + " online");
            icon.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip;
        }

        // ------------------------------------------------------------------ state for the menu and the window

        public class TvView { public TvEntry e; public string state, label; public bool reachable; public string adb = "", adbError = ""; public bool pcLink; public string version = ""; }

        static string StateLabel(Dictionary<string, object> env, long nowTv, out string state)
        {
            long at = Net.Num(env, "at");
            if (env == null || at == 0 || nowTv - at > 180000) { state = "away"; return "not heard from lately"; }
            string scr = Net.Str(env, "screen");
            var sh = Net.Obj(env, "shows");
            string kind = Net.Str(sh, "kind");
            state = scr == "off" ? "off" : scr == "saver" ? "saver" : "on";
            if (scr == "off" || kind == "off") return "off";
            switch (kind)
            {
                case "photo": return Net.Bool(sh, "sync") ? "photo screen saver (together)" : "photo screen saver";
                case "home": return "CanvasFrame home screen";
                case "input": return Net.Str(sh, "label") != "" ? Net.Str(sh, "label") : "TV input";
                case "saver": return Net.Str(sh, "label") != "" ? Net.Str(sh, "label") : "screen saver";
                case "app": return Net.Str(sh, "label") != "" ? Net.Str(sh, "label") : "an app";
            }
            return scr == "saver" ? "screen saver" : "on";
        }

        public List<TvView> TvList()
        {
            Dictionary<string, object> s; lock (sync) s = site;
            var items = Net.Obj(s, "items");
            long nowTv = Net.Num(s, "now");
            var list = new List<TvView>();
            foreach (var t in Store.All().OrderBy(x => x.Added ? 0 : 1).ThenBy(x => x.Name))
            {
                var v = new TvView { e = t };
                Dictionary<string, object> env = null;
                if (items != null && t.Id != "" && items.ContainsKey("env:" + t.Id))
                {
                    var it = items["env:" + t.Id] as Dictionary<string, object>;
                    if (!Net.Bool(it, "x")) env = Net.Obj(it, "d");
                }
                // Only TVs still in the site (or added by hand) are listed.
                bool inSite = items != null && t.Id != "" && items.ContainsKey("dev:" + t.Id) && !Net.Bool(items["dev:" + t.Id], "x");
                if (!t.Added && !inSite) continue;
                v.label = env != null ? StateLabel(env, nowTv, out v.state) : "";
                Live l; lock (sync) live.TryGetValue(t.Url, out l);
                if (env == null) { v.state = l != null && l.Reachable ? "on" : "away"; v.label = l != null && l.Reachable ? "online" : (l?.Error ?? "checking…"); }
                v.reachable = l != null && l.Reachable;
                if (l?.Status != null)
                {
                    v.adb = Net.Str(l.Status, "adb"); v.adbError = Net.Str(l.Status, "adbError");
                    v.pcLink = Net.Bool(Net.Obj(l.Status, "features"), "pcLink");
                    v.version = Net.Str(l.Status, "version");
                }
                list.Add(v);
            }
            return list;
        }

        /// <summary>Everything the window shows, as JSON (GET /local/state).</summary>
        public Dictionary<string, object> StateJson()
        {
            Dictionary<string, object> s; string from; long skew;
            lock (sync) { s = site; from = siteFrom; skew = siteSkew; }
            string mon = Store.S.Monitor;
            var tvs = TvList().Select(v => (object)new Dictionary<string, object>
            {
                { "id", v.e.Id != "" ? v.e.Id : v.e.Url }, { "devId", v.e.Id }, { "name", v.e.Name ?? "" }, { "brand", v.e.Brand ?? "" },
                { "platform", v.e.Platform ?? "" }, { "url", v.e.Url }, { "added", v.e.Added }, { "hasKey", v.e.KeyProtected != "" && v.e.KeyProtected != null },
                { "reachable", v.reachable }, { "state", v.state ?? "" }, { "label", v.label ?? "" }, { "adb", v.adb }, { "adbError", v.adbError },
                { "pcLink", v.pcLink }, { "version", v.version }, { "monitor", mon != "" && (mon == v.e.Id || mon == v.e.Url) },
                { "canWake", !string.IsNullOrEmpty(v.e.Mac) },
            }).ToList();
            return new Dictionary<string, object>
            {
                { "tvs", tvs }, { "site", s }, { "siteFrom", from }, { "skew", skew }, { "monitor", MonitorState() },
                { "withWindows", Store.S.WithWindows }, { "machine", Environment.MachineName },
                { "version", typeof(Program).Assembly.GetName().Version.ToString(3) },
                { "hint", HintJson() },
            };
        }

        /// <summary>The TV this copy was downloaded from (pre-fills the Add dialog), unless it's already added.</summary>
        static object HintJson()
        {
            var h = Setup.ReadHint();
            if (h == null || Store.AddedTvs().Any(t => string.Equals(Net.Authority(t.Url), Net.Authority(h.Url), StringComparison.OrdinalIgnoreCase))) return null;
            return new Dictionary<string, object> { { "url", h.Url }, { "name", h.Name } };
        }

        // ------------------------------------------------------------------ tray menu

        void BuildMenu()
        {
            menu.Items.Clear();
            var open = new ToolStripMenuItem("Open CanvasFrame", null, (s, e) => ShowWindow()) { Font = new Font(menu.Font, FontStyle.Bold) };
            menu.Items.Add(open);
            menu.Items.Add(new ToolStripSeparator());
            var tvs = TvList();
            if (tvs.Count == 0) menu.Items.Add(new ToolStripMenuItem("No TVs yet") { Enabled = false });
            foreach (var v in tvs)
            {
                var t = v.e;
                var item = new ToolStripMenuItem((t.Name ?? "TV") + "  ·  " + v.label, Dot(v.state));
                item.DropDownItems.Add("Open its dashboard", null, (s, e) => { ShowWindow(); form.OpenTv(t); });
                if (v.state != "away")
                {
                    item.DropDownItems.Add("Screen saver now", null, (s, e) => Act(t, "/api/screensaver", new Dictionary<string, object> { { "preview", true }, { "openHome", true } }, "Screen saver starting"));
                    item.DropDownItems.Add("CanvasFrame home screen", null, (s, e) => Act(t, "/api/home", new Dictionary<string, object>(), null));
                    if (t.Platform == "android")
                    {
                        item.DropDownItems.Add("Sleep", null, (s, e) => Act(t, "/api/key", new Dictionary<string, object> { { "key", "SLEEP" } }, null));
                        item.DropDownItems.Add("Wake", null, (s, e) => Act(t, "/api/key", new Dictionary<string, object> { { "key", "WAKEUP" } }, null));
                    }
                }
                if ((v.state == "away" || v.state == "off") && !string.IsNullOrEmpty(t.Mac))
                    item.DropDownItems.Add("Wake (Wake-on-LAN)", null, (s, e) => Task.Run(() =>
                    {
                        string r = Wake(t);
                        Ui(() => icon.ShowBalloonTip(3000, t.Name, r, ToolTipIcon.None));
                    }));
                if (v.pcLink)
                    item.DropDownItems.Add("Switch to this PC", null, (s, e) => Act(t, "/api/pc", new Dictionary<string, object> { { "event", "on" }, { "from", Environment.MachineName } }, null));
                menu.Items.Add(item);
            }
            menu.Items.Add(new ToolStripSeparator());
            var mon = new ToolStripMenuItem("Monitor for this PC");
            var cur = MonitorTv();
            mon.DropDownItems.Add(new ToolStripMenuItem("None", null, (s, e) => SetMonitor("")) { Checked = cur == null });
            foreach (var v in tvs.Where(x => x.e.Added && (x.pcLink || MonitorTv() == x.e)))
            {
                var t = v.e;
                mon.DropDownItems.Add(new ToolStripMenuItem(t.Name, null, (s, e) => SetMonitor(t.Id != "" ? t.Id : t.Url)) { Checked = cur == t });
            }
            menu.Items.Add(mon);
            menu.Items.Add(new ToolStripMenuItem("Start with Windows", null, (s, e) => { Store.S.WithWindows = !Store.S.WithWindows; Store.Save(); ApplyWithWindows(); })
            { Checked = Store.S.WithWindows });
            menu.Items.Add("Add a TV…", null, (s, e) => { ShowWindow(); form.Run("openAdd()"); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Exit", null, (s, e) => Exit());
        }

        static readonly Dictionary<string, Bitmap> dots = new Dictionary<string, Bitmap>();
        static Bitmap Dot(string state)
        {
            Bitmap b;
            if (dots.TryGetValue(state ?? "", out b)) return b;
            Color c = state == "on" ? Color.FromArgb(0x5f, 0xb8, 0x6a) : state == "saver" ? Color.FromArgb(0xd8, 0xb0, 0x70) : state == "off" ? Color.FromArgb(0x70, 0x70, 0x70) : Color.FromArgb(0xc0, 0x60, 0x60);
            b = new Bitmap(16, 16);
            using (var g = Graphics.FromImage(b)) { g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias; using (var br = new SolidBrush(c)) g.FillEllipse(br, 4, 4, 8, 8); }
            dots[state ?? ""] = b;
            return b;
        }

        /// <summary>Wake-on-LAN for a TV that's off: from this PC, and also from a TV this PC can reach (it may be on the TV's
        /// network when the PC isn't). Returns a line for the user.</summary>
        public string Wake(TvEntry t)
        {
            if (string.IsNullOrEmpty(t.Mac)) return t.Name + " hasn't shared its network address yet (it needs a CanvasFrame update)";
            try { Wol.Send(t.Mac, Wol.HostOf(t.Url)); } catch (Exception e) { return "Couldn't send the wake-up: " + e.Message; }
            var relay = Store.AddedTvs().FirstOrDefault(x => x != t && !string.IsNullOrEmpty(x.Id) && IsReachable(x));
            if (relay != null && !string.IsNullOrEmpty(t.Id))
                try { Net.Json(relay.Url + "/api/wol", relay.Key, 6000, "POST", new Dictionary<string, object> { { "id", t.Id } }); } catch { }
            Log("Wake-on-LAN sent to " + t.Name + " (" + t.Mac + ")");
            kick.Set();
            return "Wake-up sent to " + t.Name + ": it can take up to a minute to answer";
        }

        /// <summary>The address to open a TV's dashboard with, signed in: a one-time pass from the TV itself (if added, with its
        /// API key) or any added TV that answers (they share the mesh secret). Without sign-in on the TVs, just the address.</summary>
        public string DashboardUrl(TvEntry t, string query)
        {
            string url = t.Url + "/" + query + "#overview";
            if (string.IsNullOrEmpty(t.Id)) return url;
            var issuers = Store.AddedTvs().Where(x => !string.IsNullOrEmpty(x.KeyProtected)).OrderBy(x => x == t ? 0 : IsReachable(x) ? 1 : 2);
            foreach (var x in issuers)
            {
                try
                {
                    var r = Net.Json(x.Url + "/api/auth/pass", x.Key, 6000, "POST", new Dictionary<string, object> { { "to", t.Id } });
                    string pass = Net.Str(r, "pass");
                    if (pass != "") return url + "&pass=" + pass;
                    return url;   // sign-in is off: nothing needed
                }
                catch (Exception e) { Log("pass from " + x.Name + " for " + t.Name + ": " + e.Message); }
            }
            return url;
        }

        bool IsReachable(TvEntry t) { Live l; lock (sync) live.TryGetValue(t.Url, out l); return l != null && l.Reachable; }

        void Act(TvEntry t, string path, Dictionary<string, object> body, string ok)
        {
            Task.Run(() =>
            {
                try
                {
                    var d = Net.Json(t.Url + path, t.Key, 10000, "POST", body);
                    string res = Net.Str(d, "result");
                    if (ok != null || res != "") Ui(() => icon.ShowBalloonTip(3000, t.Name, res != "" ? res : ok, ToolTipIcon.None));
                    kick.Set();
                }
                catch (Exception e) { Ui(() => icon.ShowBalloonTip(5000, t.Name, e.Message, ToolTipIcon.Warning)); }
            });
        }
    }

    /// <summary>Hidden window that receives the console display state (on / off / dimmed) and sleep notifications.</summary>
    sealed class PowerWindow : NativeWindow
    {
        [System.Runtime.InteropServices.DllImport("user32.dll", SetLastError = true)]
        static extern IntPtr RegisterPowerSettingNotification(IntPtr recipient, ref Guid setting, int flags);
        readonly Action<int, string> onState;
        const int WM_POWERBROADCAST = 0x218;

        public PowerWindow(Action<int, string> onState)
        {
            this.onState = onState;
            CreateHandle(new CreateParams { Caption = "CanvasFrameLinkPower" });
            RegisterPowerSettingNotification(Handle, ref Service.GUID_CONSOLE_DISPLAY_STATE, 0);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_POWERBROADCAST)
            {
                int ev = (int)m.WParam;
                if (ev == Service.PBT_POWERSETTINGCHANGE && m.LParam != IntPtr.Zero)
                {
                    int v = Service.DisplayState(m.LParam);
                    if (v == 1) onState(1, "display on");
                    else if (v == 0) onState(0, "display off");
                }
                else if (ev == Service.PBT_APMSUSPEND) onState(-2, "going to sleep");
                m.Result = (IntPtr)1;
                return;
            }
            base.WndProc(ref m);
        }
    }
}
