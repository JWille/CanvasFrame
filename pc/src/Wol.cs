using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace CanvasFrameLink
{
    /// <summary>Wake-on-LAN (same as Wol.java / lib/wol.js): the magic packet (6 x 0xFF + the MAC 16 times) as UDP to ports 9
    /// and 7, sent to the limited broadcast, each local network's broadcast address and the TV's last IP. The TVs publish their
    /// MAC in the shared site (dev:&lt;id&gt;.mac); LG "Turn on via Wi-Fi" / Hisense wake-on-wireless must be on (they are).</summary>
    static class Wol
    {
        public static byte[] Packet(string mac)
        {
            string hex = new string((mac ?? "").Where(Uri.IsHexDigit).ToArray());
            if (hex.Length != 12) throw new ArgumentException("Not a MAC address: " + mac);
            byte[] p = new byte[102];
            for (int i = 0; i < 6; i++) p[i] = 0xff;
            for (int r = 0; r < 16; r++) for (int i = 0; i < 6; i++) p[6 + 6 * r + i] = Convert.ToByte(hex.Substring(2 * i, 2), 16);
            return p;
        }

        static IEnumerable<IPAddress> Broadcasts()
        {
            yield return IPAddress.Broadcast;
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (var u in ni.GetIPProperties().UnicastAddresses)
                {
                    if (u.Address.AddressFamily != AddressFamily.InterNetwork || u.IPv4Mask == null) continue;
                    byte[] a = u.Address.GetAddressBytes(), m = u.IPv4Mask.GetAddressBytes(), b = new byte[4];
                    for (int i = 0; i < 4; i++) b[i] = (byte)(a[i] | ~m[i]);
                    yield return new IPAddress(b);
                }
            }
        }

        /// <summary>Sends the packet (never throws for network errors); returns how many addresses it went to.</summary>
        public static int Send(string mac, string ip)
        {
            byte[] p = Packet(mac);
            var to = Broadcasts().Select(a => a.ToString()).ToList();
            IPAddress one;
            if (!string.IsNullOrEmpty(ip) && IPAddress.TryParse(ip, out one) && one.AddressFamily == AddressFamily.InterNetwork) to.Add(ip);
            int n = 0;
            using (var u = new UdpClient())
            {
                u.EnableBroadcast = true;
                foreach (string addr in to.Distinct())
                {
                    try { foreach (int port in new[] { 9, 7 }) u.Send(p, p.Length, new IPEndPoint(IPAddress.Parse(addr), port)); n++; }
                    catch { }
                }
            }
            return n;
        }

        public static string HostOf(string url) { try { return new Uri(url).Host; } catch { return ""; } }
    }
}
