package com.canvasframe.home;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal QR code encoder (byte mode, error correction level M, versions 1–10, best of 8 masks), rendered as SVG.
 * Enough for an otpauth:// enrolment link. Follows the structure of Project Nayuki's reference encoder.
 */
final class Qr {
    private Qr() { }

    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS    = {-1,  1,  1,  1,  2,  2,  4,  4,  4,  5,  5};

    private int size;
    private boolean[][] mod, fn;

    /** SVG markup for {@code text}; throws if the text is too long for version 10. */
    static String svg(String text) {
        Qr q = new Qr();
        q.encode(text.getBytes(StandardCharsets.UTF_8));
        int border = 4, dim = q.size + border * 2;
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(dim).append(' ').append(dim)
          .append("\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"");
        for (int y = 0; y < q.size; y++)
            for (int x = 0; x < q.size; x++)
                if (q.mod[y][x]) sb.append('M').append(x + border).append(',').append(y + border).append("h1v1h-1z");
        return sb.append("\"/></svg>").toString();
    }

    /** Module grid (true = dark), for tests. */
    static boolean[][] matrix(String text) {
        Qr q = new Qr();
        q.encode(text.getBytes(StandardCharsets.UTF_8));
        return q.mod;
    }

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int n = ver / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCapacity(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    private void encode(byte[] data) {
        int ver = 1;
        for (; ver <= 10; ver++) {
            int bits = 4 + (ver < 10 ? 8 : 16) + data.length * 8;
            if (bits <= dataCapacity(ver) * 8) break;
        }
        if (ver > 10) throw new IllegalArgumentException("Text too long for QR code");

        // Bit stream: byte mode, length, data, terminator, padding.
        int cap = dataCapacity(ver) * 8;
        BitBuf bb = new BitBuf();
        bb.add(4, 4);
        bb.add(data.length, ver < 10 ? 8 : 16);
        for (byte b : data) bb.add(b & 0xFF, 8);
        bb.add(0, Math.min(4, cap - bb.len));
        bb.add(0, (8 - bb.len % 8) % 8);
        for (int pad = 0xEC; bb.len < cap; pad ^= 0xEC ^ 0x11) bb.add(pad, 8);
        byte[] codewords = new byte[bb.len / 8];
        for (int i = 0; i < bb.len; i++) if (bb.get(i)) codewords[i >>> 3] |= 1 << (7 - (i & 7));

        size = ver * 4 + 17;
        mod = new boolean[size][size];
        fn = new boolean[size][size];
        drawFunctionPatterns(ver);
        drawCodewords(addEccAndInterleave(codewords, ver));
        // Try all 8 masks and keep the one with the lowest penalty (some decoders struggle otherwise).
        int best = 0, bestScore = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            int s = penalty();
            if (s < bestScore) { best = m; bestScore = s; }
            applyMask(m);   // XOR again undoes it
        }
        applyMask(best);
        drawFormatBits(best);
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (fn[y][x]) continue;
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv) mod[y][x] = !mod[y][x];
            }
    }

    /** ISO 18004 penalty: long runs, 2×2 blocks, finder-like patterns, dark/light balance. */
    private int penalty() {
        int score = 0, dark = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < size; i++) {
                int run = 0;
                boolean prev = false;
                StringBuilder line = new StringBuilder(size);
                for (int j = 0; j < size; j++) {
                    boolean v = pass == 0 ? mod[i][j] : mod[j][i];
                    line.append(v ? '1' : '0');
                    if (j > 0 && v == prev) run++;
                    else { if (run >= 5) score += 3 + run - 5; run = 1; }
                    prev = v;
                }
                if (run >= 5) score += 3 + run - 5;
                String s = "0000" + line + "0000";
                for (int k = s.indexOf("1011101"); k >= 0; k = s.indexOf("1011101", k + 1)) {
                    if (s.startsWith("0000", k + 7) || (k >= 4 && s.startsWith("0000", k - 4))) score += 40;
                }
            }
        }
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (mod[y][x]) dark++;
                if (x + 1 < size && y + 1 < size && mod[y][x] == mod[y][x + 1] && mod[y][x] == mod[y + 1][x] && mod[y][x] == mod[y + 1][x + 1]) score += 3;
            }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return score + Math.max(0, k) * 10;
    }

    private void set(int x, int y, boolean dark) { mod[y][x] = dark; fn[y][x] = true; }

    private void drawFunctionPatterns(int ver) {
        for (int i = 0; i < size; i++) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0); }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
        int[] al = alignmentPositions(ver);
        int n = al.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) alignment(al[i], al[j]);
        drawFormatBits(0);
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(a, b, bit); set(b, a, bit);
            }
        }
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, d != 2 && d != 4);
            }
    }

    private void alignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }

    private int[] alignmentPositions(int ver) {
        if (ver == 1) return new int[0];
        int n = ver / 7 + 2;
        int step = (ver * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, pos = size - 7; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    private void drawFormatBits(int mask) {
        int data = mask; // level M = 00 in the top two bits
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private static byte[] addEccAndInterleave(byte[] data, int ver) {
        int numBlocks = NUM_BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver];
        int raw = rawModules(ver) / 8;
        int numShort = numBlocks - raw % numBlocks, shortLen = raw / numBlocks;
        byte[][] blocks = new byte[numBlocks][];
        byte[] div = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            byte[] dat = Arrays.copyOfRange(data, k, k + shortLen - eccLen + (i < numShort ? 0 : 1));
            k += dat.length;
            byte[] block = Arrays.copyOf(dat, shortLen + 1);
            byte[] ecc = rsRemainder(dat, div);
            System.arraycopy(ecc, 0, block, block.length - eccLen, ecc.length);
            blocks[i] = block;
        }
        byte[] out = new byte[raw];
        for (int i = 0, k = 0; i < blocks[0].length; i++)
            for (int j = 0; j < numBlocks; j++)
                if (i != shortLen - eccLen || j >= numShort) out[k++] = blocks[j][i];
        return out;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        mod[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
        }
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) gfMul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= gfMul(div[i] & 0xFF, factor);
        }
        return r;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static final class BitBuf {
        boolean[] bits = new boolean[64];
        int len;
        void add(int val, int n) {
            for (int i = n - 1; i >= 0; i--) {
                if (len == bits.length) bits = Arrays.copyOf(bits, len * 2);
                bits[len++] = ((val >>> i) & 1) != 0;
            }
        }
        boolean get(int i) { return bits[i]; }
    }
}
