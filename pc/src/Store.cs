using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;

namespace CanvasFrameLink
{
    /// <summary>A TV this PC knows: added by the user (with an API key, or none when the TV has no sign-in), or found
    /// through an added TV's shared site (then only its address and certificate pin are kept).</summary>
    public class TvEntry
    {
        public string Id { get; set; }           // the TV's device id in the shared site ("" until known)
        public string Name { get; set; }
        public string Url { get; set; }          // https://host:port
        public string Pin { get; set; }          // certificate fingerprint
        public string KeyProtected { get; set; } // API key, DPAPI (this Windows user only), base64
        public bool Added { get; set; }          // the user added it (vs. found in the site)
        public string Platform { get; set; }
        public string Brand { get; set; }
        public string Mac { get; set; }          // for Wake-on-LAN (from the shared site)

        [ScriptIgnore] public string Key
        {
            get
            {
                if (string.IsNullOrEmpty(KeyProtected)) return "";
                try { return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(KeyProtected), Entropy, DataProtectionScope.CurrentUser)); }
                catch { return ""; }
            }
            set
            {
                KeyProtected = string.IsNullOrEmpty(value) ? "" :
                    Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(value), Entropy, DataProtectionScope.CurrentUser));
            }
        }
        static readonly byte[] Entropy = Encoding.UTF8.GetBytes("CanvasFrame Link key");
    }

    public class Settings
    {
        public List<TvEntry> Tvs { get; set; } = new List<TvEntry>();
        public string Monitor { get; set; } = "";          // id (or url) of the TV this PC uses as its monitor
        public bool WithWindows { get; set; } = true;       // start with Windows (HKCU Run)
        public int Version { get; set; } = 1;
    }

    static class Store
    {
        public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CanvasFrame Link");
        static string FilePath { get { return Path.Combine(Dir, "settings.json"); } }
        static readonly object Lock = new object();
        public static Settings S = new Settings();

        public static void Load()
        {
            lock (Lock)
            {
                try { if (File.Exists(FilePath)) S = new JavaScriptSerializer().Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings(); }
                catch { S = new Settings(); }
                if (S.Tvs == null) S.Tvs = new List<TvEntry>();
                foreach (TvEntry t in S.Tvs) if (!string.IsNullOrEmpty(t.Pin) && !string.IsNullOrEmpty(t.Url)) Net.Pins[Net.Authority(t.Url)] = t.Pin;
            }
        }

        public static void Save()
        {
            lock (Lock)
            {
                Directory.CreateDirectory(Dir);
                string tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, new JavaScriptSerializer().Serialize(S));
                if (File.Exists(FilePath)) File.Replace(tmp, FilePath, null); else File.Move(tmp, FilePath);
            }
        }

        public static List<TvEntry> All() { lock (Lock) return S.Tvs.ToList(); }
        public static List<TvEntry> AddedTvs() { lock (Lock) return S.Tvs.Where(t => t.Added).ToList(); }

        public static TvEntry Find(string idOrUrl)
        {
            if (string.IsNullOrEmpty(idOrUrl)) return null;
            lock (Lock) return S.Tvs.FirstOrDefault(t => (t.Id != "" && t.Id == idOrUrl) || string.Equals(t.Url, idOrUrl, StringComparison.OrdinalIgnoreCase));
        }

        public static TvEntry FindByAuthority(string authority)
        {
            lock (Lock) return S.Tvs.FirstOrDefault(t => !string.IsNullOrEmpty(t.Url) && string.Equals(Net.Authority(t.Url), authority, StringComparison.OrdinalIgnoreCase));
        }

        public static void Put(TvEntry t)
        {
            lock (Lock)
            {
                TvEntry old = S.Tvs.FirstOrDefault(x => (t.Id != "" && x.Id == t.Id) || string.Equals(x.Url, t.Url, StringComparison.OrdinalIgnoreCase));
                if (old != null) S.Tvs.Remove(old);
                S.Tvs.Add(t);
                if (!string.IsNullOrEmpty(t.Pin)) Net.Pins[Net.Authority(t.Url)] = t.Pin;
            }
        }

        public static void Remove(TvEntry t) { lock (Lock) S.Tvs.Remove(t); }
    }
}
