using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace CanvasFrameLink
{
    /// <summary>
    /// The CanvasFrame window: WebView2 showing the app's own page (https://canvasframe.local/, served from this exe) in the
    /// dashboard's look, and each TV's own dashboard when opened. The page reaches the TVs through this window:
    ///   /tv/{id}/api/...  -> that TV's API, with this PC's API key and the TV's pinned certificate;
    ///   /local/...        -> the app itself (state, add / remove TVs, monitor, service, start with Windows).
    /// A TV's dashboard is only shown if the TV presents its pinned certificate.
    /// </summary>
    sealed class MainForm : Form
    {
        const string HOST = "canvasframe.local";
        const string HOME = "https://" + HOST + "/";
        readonly WebView2 web;
        public bool ReallyClose;
        bool ready;
        readonly List<string> pendingScripts = new List<string>();

        public MainForm()
        {
            Text = "CanvasFrame";
            Icon = Icon.ExtractAssociatedIcon(System.Diagnostics.Process.GetCurrentProcess().MainModule.FileName);
            StartPosition = FormStartPosition.CenterScreen;
            var wa = Screen.PrimaryScreen.WorkingArea;
            Size = new Size(Math.Min(1180, wa.Width * 9 / 10), Math.Min(940, wa.Height * 9 / 10));
            MinimumSize = new Size(480, 400);
            BackColor = Color.FromArgb(0x1e, 0x1b, 0x18);
            web = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = Color.FromArgb(0x1e, 0x1b, 0x18) };
            Controls.Add(web);
            FormClosing += (s, e) => { if (!ReallyClose && e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); } };
            _ = Init();
        }

        public void ShowAndActivate()
        {
            if (!Visible) Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
            TrayApp.I.Kick();
        }

        async Task Init()
        {
            try
            {
                Embedded.LoadNative();   // WebView2Loader.dll, unpacked from this exe
                string data = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CanvasFrame Link", "WebView2");
                // CANVASFRAME_LINK_DEVTOOLS=<port>: Chrome DevTools protocol on 127.0.0.1 (for testing the window).
                string dev = Environment.GetEnvironmentVariable("CANVASFRAME_LINK_DEVTOOLS");
                var opts = new CoreWebView2EnvironmentOptions(string.IsNullOrEmpty(dev) ? null : "--remote-debugging-port=" + int.Parse(dev));
                var env = await CoreWebView2Environment.CreateAsync(null, data, opts);
                await web.EnsureCoreWebView2Async(env);
            }
            catch (Exception e)
            {
                Controls.Remove(web);
                Controls.Add(new Label
                {
                    Dock = DockStyle.Fill, ForeColor = Color.White, Padding = new Padding(24), Font = new Font("Segoe UI", 11),
                    Text = "The CanvasFrame window needs Microsoft Edge WebView2, which is part of Windows 11.\n\n" + e.Message +
                           "\n\nThe tray menu still works.",
                });
                return;
            }
            var core = web.CoreWebView2;
            core.Settings.AreDevToolsEnabled = System.Diagnostics.Debugger.IsAttached;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = true;
            core.AddWebResourceRequestedFilter("https://" + HOST + "/*", CoreWebView2WebResourceContext.All);
            core.WebResourceRequested += OnRequest;
            core.ServerCertificateErrorDetected += OnCertificate;
            core.NewWindowRequested += (s, e) =>
            {
                e.Handled = true;
                if (IsTvUrl(e.Uri) || e.Uri.StartsWith(HOME)) core.Navigate(e.Uri);
                else try { System.Diagnostics.Process.Start(e.Uri); } catch { }
            };
            core.NavigationStarting += (s, e) =>
            {
                // Only the app page and the TVs (by their pinned address); anything else opens in the browser.
                if (e.Uri.StartsWith(HOME) || IsTvUrl(e.Uri) || e.Uri.StartsWith("about:") || e.Uri.StartsWith("data:")) return;
                e.Cancel = true;
                try { System.Diagnostics.Process.Start(e.Uri); } catch { }
            };
            core.DocumentTitleChanged += (s, e) => Text = core.DocumentTitle == "" || core.DocumentTitle == "CanvasFrame" ? "CanvasFrame" : core.DocumentTitle + " · CanvasFrame";
            await core.AddScriptToExecuteOnDocumentCreatedAsync(BackButtonScript);
            core.Navigate(HOME);
            ready = true;
            foreach (var js in pendingScripts) Run(js);
            pendingScripts.Clear();
        }

        /// <summary>On a TV's dashboard: a small "All TVs" button back to the app page, in the dashboard's own colours.</summary>
        const string BackButtonScript = @"
(function () {
  if (location.hostname === 'canvasframe.local') return;
  document.addEventListener('DOMContentLoaded', function () {
    var b = document.createElement('button');
    b.textContent = '← All TVs';
    b.title = 'Back to all your CanvasFrame TVs';
    b.className = 'on';
    b.style.cssText = 'position:fixed;left:16px;bottom:16px;z-index:9999;font-weight:600;box-shadow:0 4px 18px rgba(0,0,0,.45)';
    b.onclick = function () { location.href = 'https://canvasframe.local/'; };
    document.body.appendChild(b);
  });
})();";

        public void Run(string js)
        {
            if (!ready) { pendingScripts.Add(js); return; }
            if (!web.CoreWebView2.Source.StartsWith(HOME)) { web.CoreWebView2.Navigate(HOME + "#" + Uri.EscapeDataString(js)); return; }
            _ = web.CoreWebView2.ExecuteScriptAsync(js);
        }

        /// <summary>Opens a TV's dashboard in this window, signed in with a pass (see TrayApp.DashboardUrl).</summary>
        public void OpenTv(TvEntry t)
        {
            string q = LookQuery();
            Task.Run(() => TrayApp.I.DashboardUrl(t, q)).ContinueWith(task => BeginInvoke((Action)(() =>
            {
                string url = task.Status == TaskStatus.RanToCompletion ? task.Result : t.Url + "/" + q + "#overview";
                if (!ready) { pendingScripts.Add("location.href=" + Net.ToJson(url)); return; }
                web.CoreWebView2.Navigate(url);
            })));
        }

        static string LookQuery()
        {
            var s = TrayApp.I.StateJson()["site"] as Dictionary<string, object>;
            var look = Net.Obj(Net.Obj(Net.Obj(s, "items"), "opt:look"), "d");
            return look == null ? "" : "?look=" + Uri.EscapeDataString(Net.Str(look, "look")) + "&mode=" + Uri.EscapeDataString(Net.Str(look, "mode"));
        }

        static bool IsTvUrl(string uri)
        {
            Uri u;
            if (!Uri.TryCreate(uri, UriKind.Absolute, out u) || u.Scheme != "https") return false;
            return Store.FindByAuthority(u.Authority) != null;
        }

        void OnCertificate(object sender, CoreWebView2ServerCertificateErrorDetectedEventArgs e)
        {
            Uri u;
            var t = Uri.TryCreate(e.RequestUri, UriKind.Absolute, out u) ? Store.FindByAuthority(u.Authority) : null;
            string fp = "";
            try
            {
                string pem = e.ServerCertificate.ToPemEncoding();
                string b64 = string.Concat(pem.Split('\n').Where(l => !l.StartsWith("-----")).Select(l => l.Trim()));
                fp = Net.Fingerprint(Convert.FromBase64String(b64));
            }
            catch { }
            e.Action = t != null && !string.IsNullOrEmpty(t.Pin) && t.Pin == fp
                ? CoreWebView2ServerCertificateErrorAction.AlwaysAllow
                : CoreWebView2ServerCertificateErrorAction.Cancel;
            if (e.Action == CoreWebView2ServerCertificateErrorAction.Cancel)
                TrayApp.Log("Refused " + e.RequestUri + ": certificate " + (fp == "" ? "unreadable" : "doesn't match the one recorded for this TV"));
        }

        // ------------------------------------------------------------------ the app page and its API

        void OnRequest(object sender, CoreWebView2WebResourceRequestedEventArgs e)
        {
            var req = e.Request;
            Uri u = new Uri(req.Uri);
            string method = req.Method;
            byte[] body = null;
            if (req.Content != null) using (var m = new MemoryStream()) { req.Content.CopyTo(m); body = m.ToArray(); }
            string ctype = "";
            try { ctype = req.Headers.GetHeader("Content-Type"); } catch { }
            var deferral = e.GetDeferral();
            Task.Run(() =>
            {
                Reply r;
                try { r = Serve(method, u, body, ctype); }
                catch (Exception ex) { r = Reply.Json(500, new Dictionary<string, object> { { "error", ex.Message } }); }
                BeginInvoke((Action)(() =>
                {
                    try
                    {
                        string headers = "Content-Type: " + r.ContentType + "\r\nCache-Control: no-store";
                        e.Response = web.CoreWebView2.Environment.CreateWebResourceResponse(new MemoryStream(r.Body), r.Status, r.Status < 400 ? "OK" : "Error", headers);
                    }
                    finally { deferral.Complete(); }
                }));
            });
        }

        class Reply
        {
            public int Status = 200; public string ContentType = "application/json; charset=utf-8"; public byte[] Body = new byte[0];
            public static Reply Json(int status, object o) { return new Reply { Status = status, Body = Encoding.UTF8.GetBytes(Net.ToJson(o)) }; }
            public static Reply Ok(object o) { return Json(200, o); }
        }

        static string resourceHtml;
        static string Resource(string name)
        {
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
            using (var r = new StreamReader(s, Encoding.UTF8)) return r.ReadToEnd();
        }

        Reply Serve(string method, Uri u, byte[] body, string ctype)
        {
            string path = u.AbsolutePath;
            if (path == "/" || path == "/index.html")
            {
                if (resourceHtml == null) resourceHtml = Resource("app.html").Replace("/*DASHBOARD_CSS*/", Resource("dashboard.css"));
                return new Reply { ContentType = "text/html; charset=utf-8", Body = Encoding.UTF8.GetBytes(resourceHtml) };
            }
            if (path.StartsWith("/tv/"))
            {
                // /tv/{id}/rest -> that TV
                string rest = path.Substring(4);
                int i = rest.IndexOf('/');
                if (i < 0) return Reply.Json(404, new Dictionary<string, object> { { "error", "not found" } });
                string id = Uri.UnescapeDataString(rest.Substring(0, i));
                var t = Store.Find(id);
                if (t == null) return Reply.Json(404, new Dictionary<string, object> { { "error", "Unknown TV" } });
                try
                {
                    var res = Net.Send(method, t.Url + rest.Substring(i) + u.Query, body, string.IsNullOrEmpty(ctype) ? "application/json" : ctype, t.Key, 15000);
                    return new Reply { Status = res.Status, ContentType = string.IsNullOrEmpty(res.ContentType) ? "application/octet-stream" : res.ContentType, Body = res.Body };
                }
                catch (Exception ex)
                {
                    return Reply.Json(502, new Dictionary<string, object> { { "error", (t.Name ?? "The TV") + " isn't answering (" + (ex.InnerException?.Message ?? ex.Message) + ")" } });
                }
            }
            if (path.StartsWith("/local/"))
            {
                var app = TrayApp.I;
                Dictionary<string, object> q = new Dictionary<string, object>();
                if (body != null && body.Length > 0) try { q = Net.Parse(Encoding.UTF8.GetString(body)) ?? q; } catch { }
                try
                {
                    switch (method + " " + path.Substring(7))
                    {
                        case "GET state": return Reply.Ok(app.StateJson());
                        case "POST add":
                        {
                            var t = app.AddTv(Net.Str(q, "address"), Net.Str(q, "key"));
                            string note = "";
                            if (Net.Bool(q, "monitor")) app.SetMonitor(t.Id != "" ? t.Id : t.Url);
                            if (Net.Bool(q, "monitor") && Net.Bool(q, "loginScreen"))
                            {
                                try { note = app.SetService(true); }
                                catch (Exception ex) { note = "The TV was added, but the login-screen service wasn't installed: " + ex.Message; }
                            }
                            app.Kick();
                            return Reply.Ok(new Dictionary<string, object> { { "ok", true }, { "name", t.Name }, { "note", note } });
                        }
                        case "POST remove": app.RemoveTv(Net.Str(q, "id")); return Reply.Ok(app.StateJson());
                        case "POST monitor": app.SetMonitor(Net.Str(q, "id")); return Reply.Ok(app.StateJson());
                        case "POST service":
                        {
                            string outp = app.SetService(Net.Bool(q, "on"));
                            var st = app.StateJson(); st["note"] = outp;
                            return Reply.Ok(st);
                        }
                        case "POST withWindows":
                            Store.S.WithWindows = Net.Bool(q, "on"); Store.Save(); app.ApplyWithWindows();
                            return Reply.Ok(app.StateJson());
                        case "POST refresh": app.Kick(); return Reply.Ok(new Dictionary<string, object> { { "ok", true } });
                        case "POST open":
                        {
                            var t = Store.Find(Net.Str(q, "id"));
                            if (t == null) throw new Exception("Unknown TV");
                            return Reply.Ok(new Dictionary<string, object> { { "url", app.DashboardUrl(t, LookQuery()) } });
                        }
                        case "POST wake":
                        {
                            var t = Store.Find(Net.Str(q, "id"));
                            if (t == null) throw new Exception("Unknown TV");
                            return Reply.Ok(new Dictionary<string, object> { { "ok", true }, { "result", app.Wake(t) } });
                        }
                    }
                }
                catch (Exception ex) { return Reply.Json(400, new Dictionary<string, object> { { "error", ex.Message } }); }
            }
            return Reply.Json(404, new Dictionary<string, object> { { "error", "not found" } });
        }
    }
}
