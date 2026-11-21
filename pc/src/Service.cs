using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using Microsoft.Win32;

namespace CanvasFrameLink
{
    /// <summary>
    /// The optional Windows service: tells the monitor TV what this PC's display does, also at the login screen when nobody is
    /// signed in (the tray app only runs inside a session). POST /api/pc {event:"on"|"off"}; on = display on, logon, unlock,
    /// service start (Windows reports the current display state on registration); off = display off, sleep, shutdown.
    /// Its settings (TV address, pin, API key) live in %ProgramData%\CanvasFrame Link\config.ini, readable by SYSTEM and
    /// Administrators only; HKLM\SOFTWARE\CanvasFrame Link holds the TV's address and name (no secrets) for the tray app.
    /// </summary>
    static class Service
    {
        public const string NAME = "CanvasFrameLink";
        const string DISPLAY = "CanvasFrame Link";
        public const string REG = @"SOFTWARE\CanvasFrame Link";
        static readonly string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "CanvasFrame Link");
        static readonly string InstallDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "CanvasFrame Link");
        static string ConfigPath { get { return Path.Combine(DataDir, "config.ini"); } }
        static string LogPath { get { return Path.Combine(DataDir, "link.log"); } }

        static readonly Dictionary<string, string> cfg = new Dictionary<string, string>();
        static string Get(string k) { string v; return cfg.TryGetValue(k, out v) ? v : ""; }
        static string Base { get { return "https://" + Get("host") + ":" + (Get("port") == "" ? "9443" : Get("port")); } }

        static void LoadConfig()
        {
            if (!File.Exists(ConfigPath)) throw new Exception("Not installed (no " + ConfigPath + ")");
            cfg.Clear();
            foreach (string line in File.ReadAllLines(ConfigPath))
            {
                int i = line.IndexOf('=');
                if (i > 0 && !line.StartsWith("#")) cfg[line.Substring(0, i).Trim()] = line.Substring(i + 1).Trim();
            }
            Net.Pins[Get("host") + ":" + (Get("port") == "" ? "9443" : Get("port"))] = Get("pin").Replace(":", "").ToLowerInvariant();
        }

        static readonly object logLock = new object();
        static void Log(string msg)
        {
            lock (logLock)
            {
                try
                {
                    Directory.CreateDirectory(DataDir);
                    FileInfo f = new FileInfo(LogPath);
                    if (f.Exists && f.Length > 512 * 1024) { File.Delete(LogPath + ".old"); File.Move(LogPath, LogPath + ".old"); }
                    File.AppendAllText(LogPath, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + msg + Environment.NewLine);
                }
                catch { }
            }
        }

        public static string SendEvent(string baseUrl, string key, string ev, int timeoutMs)
        {
            var d = Net.Json(baseUrl + "/api/pc", key, timeoutMs, "POST", new Dictionary<string, object> { { "event", ev }, { "from", Environment.MachineName } });
            return Net.Str(d, "result");
        }

        /// <summary>The service as the tray app sees it: installed / running and which TV (from HKLM, no secrets).</summary>
        public static (bool installed, bool running, string host, string tv) State()
        {
            bool inst = false, run = false;
            try
            {
                using (var sc = new System.ServiceProcess.ServiceController(NAME))
                {
                    var st = sc.Status; inst = true; run = st == System.ServiceProcess.ServiceControllerStatus.Running;
                }
            }
            catch { }
            string host = "", tv = "";
            try
            {
                using (RegistryKey k = Registry.LocalMachine.OpenSubKey(REG))
                    if (k != null) { host = Convert.ToString(k.GetValue("Host", "")); tv = Convert.ToString(k.GetValue("Tv", "")); }
            }
            catch { }
            return (inst, run, host, tv);
        }

        // ------------------------------------------------------------------ install / uninstall (run elevated)

        public static bool IsAdmin() { return new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator); }

        static string Arg(string[] args, string name)
        {
            for (int i = 0; i < args.Length - 1; i++) if (args[i] == name) return args[i + 1];
            return null;
        }

        static int Run(string exe, string args)
        {
            var psi = new ProcessStartInfo(exe, args) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            using (Process p = Process.Start(psi)) { p.StandardOutput.ReadToEnd(); p.StandardError.ReadToEnd(); p.WaitForExit(); return p.ExitCode; }
        }

        /// <summary>Runs this exe elevated (UAC) with the given arguments; output comes back through a temp file.</summary>
        public static (int code, string output) Elevated(string args)
        {
            string outFile = Path.Combine(Path.GetTempPath(), "CanvasFrameLink-" + Guid.NewGuid().ToString("N") + ".txt");
            var psi = new ProcessStartInfo(Process.GetCurrentProcess().MainModule.FileName, args + " --out \"" + outFile + "\"")
            { Verb = "runas", UseShellExecute = true, WindowStyle = ProcessWindowStyle.Hidden };
            Process p;
            try { p = Process.Start(psi); }
            catch (System.ComponentModel.Win32Exception) { return (3, "Administrator rights are needed (the request was declined)."); }
            p.WaitForExit();
            string text = "";
            if (File.Exists(outFile)) { text = File.ReadAllText(outFile); try { File.Delete(outFile); } catch { } }
            return (p.ExitCode, text.Trim());
        }

        /// <summary>CLI entry for install / uninstall / on / off / status. Output goes to --out (elevated runs) or the console.</summary>
        public static int Cli(string[] args)
        {
            string outFile = Arg(args, "--out");
            StringWriter w = new StringWriter();
            int rc;
            try { rc = CliBody(args, w); }
            catch (Exception e) { w.WriteLine("Error: " + e.Message); rc = 2; }
            if (outFile != null) File.WriteAllText(outFile, w.ToString());
            else { Program.AttachConsoleOut(); Console.Write(w.ToString()); }
            return rc;
        }

        static int CliBody(string[] args, TextWriter o)
        {
            switch (args[0].ToLowerInvariant())
            {
                case "install":
                {
                    if (args.Length < 2 || args[1].StartsWith("--")) { o.WriteLine("Usage: CanvasFrameLink install <tv address> [--key cfk_... | --keyfile path]"); return 1; }
                    if (!IsAdmin()) { o.WriteLine("Run from an administrator prompt (or let the tray app ask)."); return 3; }
                    string baseUrl = Net.BaseUrl(args[1]);
                    Uri u = new Uri(baseUrl);
                    string fp = Net.FirstContact(baseUrl);
                    Net.Pins[u.Authority] = fp;
                    o.WriteLine("TV certificate " + Net.Pretty(fp));
                    string key = Arg(args, "--key");
                    string keyFile = Arg(args, "--keyfile");
                    if (key == null && keyFile != null) { key = File.ReadAllText(keyFile).Trim(); try { File.Delete(keyFile); } catch { } }
                    string name = "CanvasFrame Link (" + Environment.MachineName + ")";
                    if (string.IsNullOrEmpty(key))
                    {
                        var d = Net.Json(baseUrl + "/api/auth/key-create", null, 8000, "POST", new Dictionary<string, object> { { "name", name.Length > 40 ? name.Substring(0, 40) : name } });
                        key = Net.Str(Net.Obj(d, "newKey"), "key");
                        if (!key.StartsWith("cfk_")) throw new Exception("couldn't create an API key (sign-in on? pass --key)");
                        o.WriteLine("Created the API key \"" + name + "\" on the TV.");
                    }
                    string tvName = Net.Str(Net.Json(baseUrl + "/api/status", key, 8000), "name");
                    string mac = "";
                    try
                    {
                        var site = Net.Json(baseUrl + "/api/site", key, 8000);
                        mac = Net.Str(Net.Obj(Net.Obj(Net.Obj(site, "items"), "dev:" + Net.Str(site, "self")), "d"), "mac");
                    }
                    catch { }
                    if (mac != "") o.WriteLine("Wake-on-LAN: " + mac);
                    o.WriteLine("Connected to " + (tvName != "" ? tvName : u.Host) + ".");

                    Run("sc.exe", "stop " + NAME);
                    Thread.Sleep(1500);
                    Directory.CreateDirectory(DataDir);
                    Run("icacls.exe", "\"" + DataDir + "\" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F *S-1-5-32-544:(OI)(CI)F");
                    File.WriteAllText(ConfigPath, "# CanvasFrame Link service (written by install)\r\nhost=" + u.Host + "\r\nport=" + u.Port + "\r\npin=" + fp
                        + "\r\nkey=" + key + "\r\nname=" + name + "\r\nmac=" + mac + "\r\n");
                    using (RegistryKey k = Registry.LocalMachine.CreateSubKey(REG))
                    {
                        k.SetValue("Host", baseUrl);
                        k.SetValue("Tv", tvName);
                    }

                    // The service runs from Program Files (a copy of this exe and its files), not from the user's folder.
                    Directory.CreateDirectory(InstallDir);
                    // Only this exe (everything it needs is inside it); never other files from wherever it was started.
                    string me = Process.GetCurrentProcess().MainModule.FileName;
                    string exe = Path.Combine(InstallDir, "CanvasFrameLink.exe");
                    if (!string.Equals(Path.GetFullPath(me), Path.GetFullPath(exe), StringComparison.OrdinalIgnoreCase)) File.Copy(me, exe, true);
                    Run("sc.exe", "delete " + NAME);
                    Thread.Sleep(500);
                    int rc = Run("sc.exe", "create " + NAME + " binPath= \"\\\"" + exe + "\\\" --service\" start= auto DisplayName= \"" + DISPLAY + "\"");
                    if (rc != 0) throw new Exception("sc create failed (" + rc + ")");
                    Run("sc.exe", "description " + NAME + " \"Switches the CanvasFrame TV to this PC (input and picture mode) when the display turns on, also at the login screen, and back to CanvasFrame when it turns off.\"");
                    Run("sc.exe", "failure " + NAME + " reset= 86400 actions= restart/5000/restart/30000/restart/60000");
                    rc = Run("sc.exe", "start " + NAME);
                    o.WriteLine(rc == 0 ? "The service is installed and running." : "Installed; starting the service failed (" + rc + "), see " + LogPath);
                    return 0;
                }
                case "uninstall":
                {
                    if (!IsAdmin()) { o.WriteLine("Run from an administrator prompt (or let the tray app ask)."); return 3; }
                    Run("sc.exe", "stop " + NAME);
                    Thread.Sleep(1500);
                    int rc = Run("sc.exe", "delete " + NAME);
                    o.WriteLine(rc == 0 ? "Removed the service." : "The service wasn't installed.");
                    if (File.Exists(ConfigPath))
                    {
                        try { LoadConfig(); } catch { }
                        if (Get("name") != "") o.WriteLine("Its API key on the TV can be revoked in the dashboard (Privacy -> API keys): \"" + Get("name") + "\".");
                        File.Delete(ConfigPath);
                    }
                    try { Registry.LocalMachine.DeleteSubKey(REG, false); } catch { }
                    try
                    {
                        // Only our own files; the folder goes if nothing else is in it.
                        foreach (string f in new[] { "CanvasFrameLink.exe", "CanvasFrameLink.exe.config", "Microsoft.Web.WebView2.Core.dll",
                                 "Microsoft.Web.WebView2.WinForms.dll", "Microsoft.Web.WebView2.Wpf.dll", "WebView2Loader.dll" })
                            try { File.Delete(Path.Combine(InstallDir, f)); } catch { }
                        Directory.Delete(InstallDir);
                    }
                    catch { }
                    return 0;
                }
                case "on": case "off":
                    if (!IsAdmin()) { o.WriteLine("Run from an administrator prompt (the service's settings are admin-only)."); return 3; }
                    LoadConfig();
                    o.WriteLine(SendEvent(Base, Get("key"), args[0].ToLowerInvariant(), 8000));
                    return 0;
                case "status":
                {
                    var s = State();
                    o.WriteLine("Service: " + (!s.installed ? "not installed" : s.running ? "running" : "stopped") + (s.host != "" ? ", TV " + s.tv + " " + s.host : ""));
                    return 0;
                }
            }
            o.WriteLine("Commands: install <tv> [--key k], uninstall, on, off, status. Without a command: the tray app.");
            return 1;
        }

        // ------------------------------------------------------------------ the service itself

        delegate void ServiceMainFn(int argc, IntPtr argv);
        delegate int HandlerEx(int control, int eventType, IntPtr eventData, IntPtr context);

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct SERVICE_TABLE_ENTRY { public string name; public ServiceMainFn proc; }
        [StructLayout(LayoutKind.Sequential)]
        struct SERVICE_STATUS { public int type, state, accepted, exitCode, svcExitCode, checkPoint, waitHint; }

        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern bool StartServiceCtrlDispatcher(SERVICE_TABLE_ENTRY[] table);
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr RegisterServiceCtrlHandlerEx(string name, HandlerEx handler, IntPtr context);
        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool SetServiceStatus(IntPtr handle, ref SERVICE_STATUS status);
        [DllImport("user32.dll", SetLastError = true)]
        static extern IntPtr RegisterPowerSettingNotification(IntPtr recipient, ref Guid setting, int flags);

        const int SERVICE_STOPPED = 1, SERVICE_START_PENDING = 2, SERVICE_STOP_PENDING = 3, SERVICE_RUNNING = 4;
        const int CONTROL_STOP = 1, CONTROL_INTERROGATE = 4, CONTROL_SHUTDOWN = 5, CONTROL_POWEREVENT = 13, CONTROL_SESSIONCHANGE = 14;
        const int ACCEPT = 0x1 | 0x4 | 0x40 | 0x80;   // stop, shutdown, power events, session changes
        public const int PBT_APMSUSPEND = 4, PBT_POWERSETTINGCHANGE = 0x8013;
        const int WTS_SESSION_LOGON = 5, WTS_SESSION_UNLOCK = 8;
        public static Guid GUID_CONSOLE_DISPLAY_STATE = new Guid("6FE69556-704A-47A0-8F24-C28D936FDA47");

        static IntPtr statusHandle;
        static SERVICE_STATUS status;
        static HandlerEx handlerRef;
        static ServiceMainFn mainRef;
        static readonly ManualResetEvent stopped = new ManualResetEvent(false);
        static Sender sender;

        public static int RunService()
        {
            mainRef = ServiceMain;
            SERVICE_TABLE_ENTRY[] table = { new SERVICE_TABLE_ENTRY { name = NAME, proc = mainRef }, new SERVICE_TABLE_ENTRY() };
            if (!StartServiceCtrlDispatcher(table)) { Log("Not started by the service manager (" + Marshal.GetLastWin32Error() + ")"); return 1; }
            return 0;
        }

        static void SetState(int state)
        {
            status.type = 0x10;
            status.state = state;
            status.accepted = state == SERVICE_RUNNING ? ACCEPT : 0;
            status.waitHint = state == SERVICE_RUNNING || state == SERVICE_STOPPED ? 0 : 10000;
            SetServiceStatus(statusHandle, ref status);
        }

        static void ServiceMain(int argc, IntPtr argv)
        {
            handlerRef = Handler;
            statusHandle = RegisterServiceCtrlHandlerEx(NAME, handlerRef, IntPtr.Zero);
            SetState(SERVICE_START_PENDING);
            Net.Init();
            try { LoadConfig(); }
            catch (Exception e) { Log(e.Message); SetState(SERVICE_STOPPED); return; }
            sender = new Sender(() => Base, () => Get("key"), () => Get("mac"), Log);
            SetState(SERVICE_RUNNING);
            Log("Started; TV " + Base);
            if (RegisterPowerSettingNotification(statusHandle, ref GUID_CONSOLE_DISPLAY_STATE, 1) == IntPtr.Zero)
                Log("Couldn't register for display notifications (" + Marshal.GetLastWin32Error() + ")");
            stopped.WaitOne();
            SetState(SERVICE_STOPPED);
        }

        static int Handler(int control, int eventType, IntPtr data, IntPtr context)
        {
            switch (control)
            {
                case CONTROL_STOP:
                    Log("Stopping");
                    SetState(SERVICE_STOP_PENDING);
                    stopped.Set();
                    return 0;
                case CONTROL_SHUTDOWN:
                    sender.SendNow("off", "shutdown");
                    SetState(SERVICE_STOP_PENDING);
                    stopped.Set();
                    return 0;
                case CONTROL_POWEREVENT:
                    if (eventType == PBT_POWERSETTINGCHANGE && data != IntPtr.Zero)
                    {
                        int v = DisplayState(data);
                        if (v == 1) sender.Queue("on", "display on");
                        else if (v == 0) sender.Queue("off", "display off");
                    }
                    else if (eventType == PBT_APMSUSPEND) sender.SendNow("off", "going to sleep");
                    return 0;
                case CONTROL_SESSIONCHANGE:
                    if (eventType == WTS_SESSION_LOGON || eventType == WTS_SESSION_UNLOCK) sender.Queue("on", eventType == WTS_SESSION_LOGON ? "sign-in" : "unlock");
                    return 0;
                case CONTROL_INTERROGATE:
                    return 0;
            }
            return 120;
        }

        /// <summary>POWERBROADCAST_SETTING for the console display: 0 off, 1 on, 2 dimmed; -1 for another setting.</summary>
        public static int DisplayState(IntPtr data)
        {
            Guid g = (Guid)Marshal.PtrToStructure(data, typeof(Guid));
            return g == GUID_CONSOLE_DISPLAY_STATE ? Marshal.ReadInt32(data, 20) : -1;
        }
    }

    /// <summary>Sends display events to the monitor TV from a background thread: the newest event wins, "on" is retried for
    /// 2 minutes (the network comes back a while after a wake), "off" for 20 seconds. Used by the service and the tray app.</summary>
    class Sender
    {
        readonly Func<string> baseUrl, key, mac;
        readonly Action<string> log;
        readonly object q = new object();
        string pending, lastSent;
        int gen;
        DateTime lastAt;
        public string LastResult = "";
        public DateTime LastResultAt;

        public Sender(Func<string> baseUrl, Func<string> key, Func<string> mac, Action<string> log)
        {
            this.baseUrl = baseUrl; this.key = key; this.mac = mac; this.log = log;
            new Thread(Loop) { IsBackground = true, Name = "sender" }.Start();
        }

        public void Queue(string ev, string why)
        {
            lock (q)
            {
                if (pending == null && ev == lastSent && (DateTime.UtcNow - lastAt).TotalSeconds < 5) return;   // display on + sign-in
                pending = ev; gen++;
                Monitor.PulseAll(q);
            }
            log("Event: " + why);
        }

        public void SendNow(string ev, string why)
        {
            lock (q) { pending = null; gen++; }
            log("Event: " + why);
            try { Done(ev, Service.SendEvent(baseUrl(), key(), ev, 3000)); }
            catch (Exception e) { log("Couldn't reach the TV: " + Msg(e)); }
        }

        void Done(string ev, string result)
        {
            log("TV: " + result);
            lock (q) { lastSent = ev; lastAt = DateTime.UtcNow; }
            LastResult = result; LastResultAt = DateTime.Now;
        }

        static string Msg(Exception e) { return e.InnerException != null ? e.Message + " (" + e.InnerException.Message + ")" : e.Message; }

        void Wake(bool first)
        {
            string m = mac == null ? "" : mac();
            if (string.IsNullOrEmpty(m)) return;
            try { Wol.Send(m, Wol.HostOf(baseUrl())); if (first) log("Wake-on-LAN sent to " + m); }
            catch (Exception e) { log("Wake-on-LAN: " + e.Message); }
        }

        void Loop()
        {
            while (true)
            {
                string ev; int g;
                lock (q) { while (pending == null) Monitor.Wait(q); ev = pending; g = gen; }
                DateTime until = DateTime.UtcNow.AddSeconds(ev == "on" ? 120 : 20);
                for (int tries = 1; ; tries++)
                {
                    // "on": wake the TV first in case it's fully off (Wake-on-LAN), and again about every 18 s while it doesn't answer.
                    if (ev == "on" && tries % 2 == 1) Wake(tries == 1);
                    try
                    {
                        string r = Service.SendEvent(baseUrl(), key(), ev, 6000);
                        Done(ev, r + (tries > 1 ? " (try " + tries + ")" : ""));
                        lock (q) if (gen == g) pending = null;
                        break;
                    }
                    catch (Exception e)
                    {
                        if (tries == 1) log("Couldn't reach the TV yet: " + Msg(e));
                        lock (q)
                        {
                            if (gen != g) break;
                            if (DateTime.UtcNow > until) { log("Gave up after " + tries + " tries"); pending = null; break; }
                            Monitor.Wait(q, 3000);
                            if (gen != g) break;
                        }
                    }
                }
            }
        }
    }
}
