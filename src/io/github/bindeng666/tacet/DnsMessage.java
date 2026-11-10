package io.github.bindeng666.tacet;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** DNS wire parsing and responses. Never trusts length fields or follows label pointers in queries. */
public final class DnsMessage {
    public final byte[] wire;
    public final String name;
    public final int questionEnd, type;
    private DnsMessage(byte[] wire, String name, int end, int type) {
        this.wire = wire; this.name = name; questionEnd = end; this.type = type;
    }
    public static int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i + 1] & 255); }
    public static void put16(byte[] b, int i, int v) { b[i] = (byte)(v >>> 8); b[i + 1] = (byte)v; }
    public static DnsMessage parse(byte[] b) throws IOException {
        if (b.length < 17 || b.length > 65535 || (b[2] & 0xF8) != 0 || u16(b, 4) != 1)
            throw new IOException("Not a standard single-question DNS query");
        int i = 12; StringBuilder name = new StringBuilder();
        while (true) {
            if (i >= b.length) throw new IOException("Missing label");
            int len = b[i++] & 255;
            if (len == 0) break;
            if (len > 63 || i + len > b.length) throw new IOException("Invalid query label");
            if (name.length() > 0) name.append('.');
            for (int j = 0; j < len; j++) {
                int c = b[i++] & 255;
                if (c < 33 || c > 126 || c == '.') throw new IOException("Invalid DNS hostname");
                name.append((char)c);
            }
            if (name.length() > 253) throw new IOException("Hostname too long");
        }
        if (i + 4 > b.length || u16(b, i + 2) != 1) throw new IOException("Invalid question");
        return new DnsMessage(b, name.toString(), i + 4, u16(b, i));
    }
    public byte[] error(int code) {
        byte[] out = Arrays.copyOf(wire, questionEnd);
        out[2] = (byte)(0x80 | (wire[2] & 1)); out[3] = (byte)(0x80 | code);
        Arrays.fill(out, 6, 12, (byte)0);
        return out;
    }
    public boolean accepts(byte[] response) {
        if (response.length < questionEnd || u16(response,0) != u16(wire,0) || (response[2] & 0x80)==0 || u16(response,4)!=1) return false;
        for(int i=12;i<questionEnd;i++) if(response[i]!=wire[i]) return false;
        return true;
    }
    public static byte[] query(String name, int id, int type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte)(id >> 8),(byte)id,1,0,0,1,0,0,0,0,0,0});
        for(String label:name.split("\\.")) { byte[] b=label.getBytes(StandardCharsets.US_ASCII); out.write(b.length);out.write(b); }
        out.write(0);out.write(type >> 8);out.write(type);out.write(0);out.write(1);return out.toByteArray();
    }
}
