using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace CanvasFrameLink
{
    /// <summary>
    /// Install / uninstall for the current Windows user (no admin rights): the exe in %LocalAppData%\Programs\CanvasFrame Link,
    /// a Start Menu shortcut, an entry in Settings > Apps > Installed apps (uninstall = this exe with --uninstall-app), and the
    /// Run entry (Start with Windows, see TrayApp). Also reads the TV hint a download from a TV carries at the end of the file.
    /// </summary>
    static class Setup
    {
        const string UNINSTALL = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\CanvasFrameLink";
        const string RUN = @"Software\Microsoft\Windows\CurrentVersion\Run";
        static string Shortcut { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "CanvasFrame.lnk"); } }
        public static string MyExe { get { return Process.GetCurrentProcess().MainModule.FileName; } }
        public static string Version { get { return typeof(Program).Assembly.GetName().Version.ToString(3); } }

        /// <summary>Copies this exe (only this file) to the app folder.</summary>
        public static void CopySelf()
        {
            Directory.CreateDirectory(Program.AppDir);
            // 2.0 installed as several files; everything is inside the exe now, and a DLL left next to it would win.
            foreach (string f in new[] { "Microsoft.Web.WebView2.Core.dll", "Microsoft.Web.WebView2.WinForms.dll", "Microsoft.Web.WebView2.Wpf.dll",
                     "WebView2Loader.dll", "CanvasFrameLink.exe.config", "CanvasFrameLink.pdb" })
                try { File.Delete(Path.Combine(Program.AppDir, f)); } catch { }
            try { Directory.Delete(Path.Combine(Program.AppDir, "runtimes"), true); } catch { }
            for (int i = 0; ; i++)
            {
                try { File.Copy(MyExe, Program.ExePath, true); break; }
                catch (IOException) when (i < 40) { Thread.Sleep(250); }   // the old copy is still closing
            }
        }

        /// <summary>Start Menu shortcut + Installed apps entry, kept current on every start of the installed copy.</summary>
        public static void Register()
        {
            try
            {
                if (!File.Exists(Shortcut)) MakeShortcut(Shortcut, Program.ExePath, "--show", "CanvasFrame: your TVs, and this PC's monitor");
            }
            catch (Exception e) { TrayApp.Log("shortcut: " + e.Message); }
            try
            {
                using (RegistryKey k = Registry.CurrentUser.CreateSubKey(UNINSTALL))
                {
                    k.SetValue("DisplayName", "CanvasFrame Link");
                    k.SetValue("DisplayVersion", Version);
                    k.SetValue("Publisher", "CanvasFrame");
                    k.SetValue("DisplayIcon", Program.ExePath + ",0");
                    k.SetValue("InstallLocation", Program.AppDir);
                    k.SetValue("UninstallString", "\"" + Program.ExePath + "\" --uninstall-app");
                    k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                    k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                    k.SetValue("EstimatedSize", (int)Math.Max(1, new FileInfo(Program.ExePath).Length / 1024), RegistryValueKind.DWord);
                }
            }
            catch (Exception e) { TrayApp.Log("uninstall entry: " + e.Message); }
        }

        static void MakeShortcut(string lnk, string target, string args, string desc)
        {
            Type t = Type.GetTypeFromProgID("WScript.Shell");
            object shell = Activator.CreateInstance(t);
            object sc = t.InvokeMember("CreateShortcut", System.Reflection.BindingFlags.InvokeMethod, null, shell, new object[] { lnk });
            Type st = sc.GetType();
            st.InvokeMember("TargetPath", System.Reflection.BindingFlags.SetProperty, null, sc, new object[] { target });
            st.InvokeMember("Arguments", System.Reflection.BindingFlags.SetProperty, null, sc, new object[] { args });
            st.InvokeMember("WorkingDirectory", System.Reflection.BindingFlags.SetProperty, null, sc, new object[] { Path.GetDirectoryName(target) });
            st.InvokeMember("Description", System.Reflection.BindingFlags.SetProperty, null, sc, new object[] { desc });
            st.InvokeMember("IconLocation", System.Reflection.BindingFlags.SetProperty, null, sc, new object[] { target + ",0" });
            st.InvokeMember("Save", System.Reflection.BindingFlags.InvokeMethod, null, sc, null);
        }

        /// <summary>Settings > Apps > Uninstall: removes the app (and, if wanted, its settings), the service (UAC), the
        /// shortcut, the entries; the folder goes once this process has exited.</summary>
        public static int Uninstall()
        {
            if (MessageBox.Show("Remove CanvasFrame Link from this PC?\n\nThe TVs aren't changed. Its API keys stay on the TVs until you revoke them there (Privacy → API keys).",
                    "CanvasFrame Link", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return 1;
            Program.QuitRunning();
            var svc = Service.State();
            if (svc.installed)
            {
                var r = Service.Elevated("uninstall");
                if (r.code != 0)
                    MessageBox.Show("The login-screen service wasn't removed (" + (r.output == "" ? "code " + r.code : r.output) + "). Uninstall again to retry.",
                        "CanvasFrame Link", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
            bool wipe = MessageBox.Show("Also remove its settings (your TVs and their API keys on this PC)?\n\nKeep them if you'll install it again.",
                "CanvasFrame Link", MessageBoxButtons.YesNo, MessageBoxIcon.Question, MessageBoxDefaultButton.Button2) == DialogResult.Yes;
            try { using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RUN, true)) k?.DeleteValue("CanvasFrame Link", false); } catch { }
            try { if (File.Exists(Shortcut)) File.Delete(Shortcut); } catch { }
            try { Registry.CurrentUser.DeleteSubKeyTree(UNINSTALL, false); } catch { }
            var dirs = new StringBuilder("\"" + Program.AppDir + "\"");
            if (wipe)
            {
                dirs.Append(" \"" + Store.Dir + "\"");
                dirs.Append(" \"" + Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CanvasFrame Link") + "\"");
            }
            // This exe can't delete its own folder while running: a short-lived cmd does it after we exit.
            Process.Start(new ProcessStartInfo("cmd.exe", "/c ping -n 3 127.0.0.1 >nul & for %d in (" + dirs + ") do rmdir /s /q %d")
            { CreateNoWindow = true, UseShellExecute = false, WorkingDirectory = Path.GetTempPath() });
            return 0;
        }

        // ------------------------------------------------------------------ TV hint at the end of a downloaded exe

        public class Hint { public string Url = "", Name = "", Fp = ""; }
        static Hint hint; static bool hintRead;

        /// <summary>A TV serving the download appends: JSON {url, name, fp}, its length (4 bytes, little-endian), "CFLINK01".
        /// Windows ignores bytes after the program image, so the exe runs unchanged.</summary>
        public static Hint ReadHint()
        {
            if (hintRead) return hint;
            hintRead = true;
            try
            {
                using (var f = new FileStream(MyExe, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                {
                    if (f.Length < 12) return null;
                    byte[] tail = new byte[12];
                    f.Seek(-12, SeekOrigin.End); f.Read(tail, 0, 12);
                    if (Encoding.ASCII.GetString(tail, 4, 8) != "CFLINK01") return null;
                    int len = BitConverter.ToInt32(tail, 0);
                    if (len <= 0 || len > 4096 || len > f.Length - 12) return null;
                    byte[] js = new byte[len];
                    f.Seek(-12 - len, SeekOrigin.End); f.Read(js, 0, len);
                    var d = Net.Parse(Encoding.UTF8.GetString(js));
                    hint = new Hint { Url = Net.Str(d, "url"), Name = Net.Str(d, "name"), Fp = Net.Str(d, "fp").Replace(":", "").ToLowerInvariant() };
                    if (!hint.Url.StartsWith("https://")) hint = null;
                }
            }
            catch { hint = null; }
            return hint;
        }
    }
}
