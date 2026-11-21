package com.canvasframe.home;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Wake-on-LAN: the "magic packet" (6 x 0xFF, then the MAC 16 times) as UDP to ports 9 and 7, sent to the limited broadcast,
 * each local network's broadcast address and, when known, the target's last IP address (some Wi-Fi chips only wake on a
 * packet addressed to them). Broadcasts stay inside one network: a TV on another subnet needs the router to pass them.
 * Same as webos/server/lib/wol.js and the PC app's Wol.cs.
 */
final class Wol {
    private Wol() { }

    static byte[] packet(String mac) {
        String hex = mac == null ? "" : mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) throw new IllegalArgumentException("Not a MAC address: " + mac);
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        byte[] p = new byte[102];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xff;
        for (int r = 0; r < 16; r++) System.arraycopy(m, 0, p, 6 + 6 * r, 6);
        return p;
    }

    /** Sends the packet; returns the addresses it went to. */
    static List<String> send(String mac, String ip) throws Exception {
        byte[] p = packet(mac);
        Set<String> to = new LinkedHashSet<>();
        to.add("255.255.255.255");
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InterfaceAddress a : ni.getInterfaceAddresses())
                if (a.getBroadcast() != null) to.add(a.getBroadcast().getHostAddress());
        }
        if (ip != null && ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) to.add(ip);
        List<String> sent = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            for (String addr : to) {
                try {
                    InetAddress ia = InetAddress.getByName(addr);
                    for (int port : new int[]{9, 7}) s.send(new DatagramPacket(p, p.length, ia, port));
                    sent.add(addr);
                } catch (Exception ignored) { }
            }
        }
        return sent;
    }
}
