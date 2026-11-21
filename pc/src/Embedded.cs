using System;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;

namespace CanvasFrameLink
{
    /// <summary>The app is one file: the WebView2 assemblies are resources in the exe, loaded on demand; the native
    /// WebView2Loader.dll is unpacked to %LocalAppData%\CanvasFrame Link\bin and loaded by full path before the window starts.</summary>
    static class Embedded
    {
        public static void Init()
        {
            AppDomain.CurrentDomain.AssemblyResolve += (s, e) =>
            {
                string name = new AssemblyName(e.Name).Name + ".dll";
                using (Stream r = Assembly.GetExecutingAssembly().GetManifestResourceStream("dll." + name))
                {
                    if (r == null) return null;
                    byte[] b = new byte[r.Length];
                    r.Read(b, 0, b.Length);
                    return Assembly.Load(b);
                }
            };
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr LoadLibrary(string path);
        static bool nativeDone;

        /// <summary>Makes WebView2Loader.dll available (a copy next to the exe, as in a dev build, also works).</summary>
        public static void LoadNative()
        {
            if (nativeDone) return;
            nativeDone = true;
            string here = Path.Combine(Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location), "WebView2Loader.dll");
            if (File.Exists(here)) { LoadLibrary(here); return; }
            byte[] b;
            using (Stream r = Assembly.GetExecutingAssembly().GetManifestResourceStream("native.WebView2Loader.dll"))
            {
                b = new byte[r.Length];
                r.Read(b, 0, b.Length);
            }
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CanvasFrame Link", "bin");
            Directory.CreateDirectory(dir);
            string path = Path.Combine(dir, "WebView2Loader.dll");
            try
            {
                if (!File.Exists(path) || new FileInfo(path).Length != b.Length) File.WriteAllBytes(path, b);
            }
            catch (IOException) { /* in use by a running copy: it's the same file */ }
            if (LoadLibrary(path) == IntPtr.Zero) throw new Exception("Couldn't load WebView2Loader.dll (" + Marshal.GetLastWin32Error() + ")");
        }
    }
}
