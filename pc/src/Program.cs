using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace CanvasFrameLink
{
    /// <summary>
    /// One exe, three roles:
    ///   (no arguments)  the tray app (per Windows user): TV list, the CanvasFrame window, "Monitor for this PC".
    ///   --service       the Windows service (started by Windows; see Service.cs).
    ///   install / uninstall / on / off / status   command line, mostly run elevated by the tray app.
    /// The tray app installs itself to %LocalAppData%\Programs\CanvasFrame Link the first time it's started from elsewhere (a
    /// new build replaces the running copy), and keeps one copy running per user.
    /// </summary>
    static class Program
    {
        public static readonly string AppDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "CanvasFrame Link");
        public static string ExePath { get { return Path.Combine(AppDir, "CanvasFrameLink.exe"); } }
        const string MUTEX = @"Local\CanvasFrameLink.Tray", SHOW = @"Local\CanvasFrameLink.Show", QUIT = @"Local\CanvasFrameLink.Quit";

        [DllImport("kernel32.dll")] static extern bool AttachConsole(int pid);
        public static void AttachConsoleOut()
        {
            if (AttachConsole(-1))
            {
                var w = new StreamWriter(Console.OpenStandardOutput()) { AutoFlush = true };
                Console.SetOut(w); Console.SetError(w);
                Console.WriteLine();
            }
        }

        [STAThread]
        static int Main(string[] args)
        {
            Embedded.Init();   // before anything touches WebView2 (its assemblies are resources in this exe)
            Net.Init();
            if (args.Contains("--uninstall-app")) { Application.EnableVisualStyles(); return Setup.Uninstall(); }
            if (args.Contains("--service") || (args.Length == 0 && !Environment.UserInteractive)) return Service.RunService();
            if (args.Length > 0 && !args[0].StartsWith("--")) return Service.Cli(args);

            string here = Path.GetDirectoryName(Process.GetCurrentProcess().MainModule.FileName);
            bool installed = string.Equals(Path.GetFullPath(here).TrimEnd('\\'), Path.GetFullPath(AppDir).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);
            if (!installed && !args.Contains("--portable")) return InstallAndRelaunch(here, args);

            bool first;
            using (var mutex = new Mutex(true, MUTEX, out first))
            {
                if (!first)
                {
                    // Already running: bring its window up instead.
                    try { using (var e = EventWaitHandle.OpenExisting(SHOW)) e.Set(); } catch { }
                    return 0;
                }
                Application.EnableVisualStyles();
                Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
                Application.ThreadException += (s, e) => TrayApp.Log("Error: " + e.Exception);   // log, keep running
                AppDomain.CurrentDomain.UnhandledException += (s, e) => TrayApp.Log("Fatal: " + e.ExceptionObject);
                Application.SetCompatibleTextRenderingDefault(false);
                if (installed) Setup.Register();   // Start Menu shortcut + Installed apps entry (current version)
                using (var show = new EventWaitHandle(false, EventResetMode.AutoReset, SHOW))
                using (var quit = new EventWaitHandle(false, EventResetMode.AutoReset, QUIT))
                {
                    var app = new TrayApp(show, quit, args.Contains("--show"));
                    Application.Run(app);
                }
                GC.KeepAlive(mutex);
            }
            return 0;
        }

        /// <summary>Closes a running copy of the tray app (if any) and waits until it has exited.</summary>
        public static void QuitRunning()
        {
            try { using (var q = EventWaitHandle.OpenExisting(QUIT)) q.Set(); } catch { return; }
            for (int i = 0; i < 80; i++)
            {
                try { using (Mutex.OpenExisting(MUTEX)) { } Thread.Sleep(100); } catch (WaitHandleCannotBeOpenedException) { break; }
            }
            Thread.Sleep(300);
        }

        /// <summary>Copies this exe (only this file) into the per-user app folder, closing a running copy first, and starts it
        /// from there. Run from Downloads, a newer build or a TV's download replaces the installed copy.</summary>
        static int InstallAndRelaunch(string from, string[] args)
        {
            try
            {
                QuitRunning();
                Setup.CopySelf();
                Process.Start(new ProcessStartInfo(ExePath, "--show") { UseShellExecute = false, WorkingDirectory = AppDir });
                return 0;
            }
            catch (Exception e)
            {
                MessageBox.Show("CanvasFrame Link couldn't install itself to " + AppDir + ":\n\n" + e.Message, "CanvasFrame Link", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }
    }
}
