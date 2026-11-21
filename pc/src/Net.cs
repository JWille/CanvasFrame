using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Web.Script.Serialization;

namespace CanvasFrameLink
{
    /// <summary>HTTPS to CanvasFrame TVs. Each TV has a self-signed certificate: its SHA-256 fingerprint is recorded on first
    /// contact (trust on first use, as the TVs do between themselves) and every later request must present that exact one.</summary>
    static class Net
    {
        /// <summary>"host:port" -> fingerprint (hex, lower case, no colons). Requests to a host with no pin are refused,
        /// except through <see cref="FirstContact"/>.</summary>
        public static readonly ConcurrentDictionary<string, string> Pins = new ConcurrentDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        [ThreadStatic] static bool tofu;
        [ThreadStatic] static string seen;

        public static void Init()
        {
            ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072 | (SecurityProtocolType)12288;   // TLS 1.2 | 1.3
            ServicePointManager.Expect100Continue = false;
            ServicePointManager.DefaultConnectionLimit = 16;
            ServicePointManager.ServerCertificateValidationCallback = Check;
        }

        static bool Check(object sender, X509Certificate cert, X509Chain chain, SslPolicyErrors errors)
        {
            if (cert == null) return false;
            string fp = Fingerprint(cert.GetRawCertData());
            HttpWebRequest r = sender as HttpWebRequest;
            if (tofu) { seen = fp; return true; }
            if (r == null) return false;
            string pin;
            return Pins.TryGetValue(r.RequestUri.Authority, out pin) && pin == fp;
        }

        public static string Fingerprint(byte[] der)
        {
            using (SHA256 sha = SHA256.Create())
                return BitConverter.ToString(sha.ComputeHash(der)).Replace("-", "").ToLowerInvariant();
        }

        public static string Pretty(string fp)
        {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < fp.Length; i += 2) { if (i > 0) b.Append(':'); b.Append(fp.Substring(i, 2).ToUpperInvariant()); }
            return b.ToString();
        }

        /// <summary>Normalises "10.0.0.5", "https://10.0.0.5:9443/", "10.0.0.5:8080" to "https://10.0.0.5:9443".</summary>
        public static string BaseUrl(string address)
        {
            string a = (address ?? "").Trim();
            if (a.StartsWith("http://", StringComparison.OrdinalIgnoreCase)) a = a.Substring(7);
            if (a.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) a = a.Substring(8);
            a = a.Split('/')[0];
            if (a == "") throw new ArgumentException("Enter the TV's address");
            string host = a, port = "9443";
            int c = a.LastIndexOf(':');
            if (c > 0 && a.IndexOf(']') < c) { host = a.Substring(0, c); port = a.Substring(c + 1); }
            if (port == "8080") port = "9443";   // the HTTP port: keys only ever travel over HTTPS
            if (port == "8081") port = "9444";
            return "https://" + host + ":" + port;
        }

        public static string Authority(string baseUrl) { return new Uri(baseUrl).Authority; }

        /// <summary>Reads a TV's certificate fingerprint without trusting it yet (shown to the user / recorded as its pin).</summary>
        public static string FirstContact(string baseUrl)
        {
            tofu = true; seen = null;
            try
            {
                HttpWebRequest r = (HttpWebRequest)WebRequest.Create(baseUrl + "/api/tls");
                r.Timeout = 6000; r.ReadWriteTimeout = 6000; r.Proxy = null; r.KeepAlive = false;
                using (r.GetResponse()) { }
                if (seen == null) throw new Exception("no certificate");
                return seen;
            }
            finally { tofu = false; }
        }

        public class Response { public int Status; public string ContentType; public byte[] Body; public string Text { get { return Encoding.UTF8.GetString(Body ?? new byte[0]); } } }

        /// <summary>One request to a TV. Never throws for HTTP errors (Status tells); throws for network / certificate errors.</summary>
        public static Response Send(string method, string url, byte[] body, string contentType, string key, int timeoutMs)
        {
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create(url);
            r.Method = method;
            r.Timeout = timeoutMs; r.ReadWriteTimeout = timeoutMs;
            r.Proxy = null;
            r.KeepAlive = true;
            r.UserAgent = "CanvasFrameLink/2";
            r.AllowAutoRedirect = false;
            if (!string.IsNullOrEmpty(key)) r.Headers["X-CanvasFrame-Key"] = key;
            if (method != "GET" && method != "HEAD")
            {
                r.Headers["X-CanvasFrame"] = "1";
                r.ContentType = contentType ?? "application/json";
                byte[] b = body ?? new byte[0];
                r.ContentLength = b.Length;
                using (Stream s = r.GetRequestStream()) s.Write(b, 0, b.Length);
            }
            HttpWebResponse res;
            try { res = (HttpWebResponse)r.GetResponse(); }
            catch (WebException e)
            {
                res = e.Response as HttpWebResponse;
                if (res == null) throw;
            }
            using (res)
            using (Stream s = res.GetResponseStream())
            using (MemoryStream m = new MemoryStream())
            {
                s.CopyTo(m);
                return new Response { Status = (int)res.StatusCode, ContentType = res.ContentType, Body = m.ToArray() };
            }
        }

        public static Dictionary<string, object> Json(string url, string key, int timeoutMs = 6000, string method = "GET", object body = null)
        {
            Response r = Send(method, url, body == null ? null : Encoding.UTF8.GetBytes(ToJson(body)), "application/json", key, timeoutMs);
            Dictionary<string, object> d = null;
            try { d = Parse(r.Text); } catch { }
            if (r.Status >= 400)
            {
                string msg = d != null && d.ContainsKey("error") ? Convert.ToString(d["error"]) : "HTTP " + r.Status;
                throw new TvException(r.Status, msg);
            }
            return d ?? new Dictionary<string, object>();
        }

        static JavaScriptSerializer Ser() { return new JavaScriptSerializer { MaxJsonLength = 64 * 1024 * 1024, RecursionLimit = 200 }; }
        public static Dictionary<string, object> Parse(string json) { return Ser().Deserialize<Dictionary<string, object>>(json); }
        public static string ToJson(object o) { return Ser().Serialize(o); }

        // Small helpers for the loosely typed JSON trees.
        public static Dictionary<string, object> Obj(object o, string k)
        {
            Dictionary<string, object> d = o as Dictionary<string, object>; object v;
            return d != null && d.TryGetValue(k, out v) ? v as Dictionary<string, object> : null;
        }
        public static string Str(object o, string k)
        {
            Dictionary<string, object> d = o as Dictionary<string, object>; object v;
            return d != null && d.TryGetValue(k, out v) && v != null ? Convert.ToString(v, System.Globalization.CultureInfo.InvariantCulture) : "";
        }
        public static long Num(object o, string k)
        {
            Dictionary<string, object> d = o as Dictionary<string, object>; object v;
            try { return d != null && d.TryGetValue(k, out v) && v != null ? Convert.ToInt64(v) : 0; } catch { return 0; }
        }
        public static bool Bool(object o, string k)
        {
            Dictionary<string, object> d = o as Dictionary<string, object>; object v;
            return d != null && d.TryGetValue(k, out v) && v is bool && (bool)v;
        }
    }

    class TvException : Exception
    {
        public readonly int Status;
        public TvException(int status, string msg) : base(msg) { Status = status; }
    }
}
