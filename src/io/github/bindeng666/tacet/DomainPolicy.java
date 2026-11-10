package io.github.bindeng666.tacet;

import java.net.IDN;
import java.util.*;

/** Host matching has DNS label boundaries; allow rules always win. */
public final class DomainPolicy {
    private final Set<String> block, allow;
    public DomainPolicy(Collection<String> block, Collection<String> allow) {
        this.block = normalizeAll(block); this.allow = normalizeAll(allow);
    }
    public static String normalize(String host) {
        if (host == null) return "";
        String s = host.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("*.")) s = s.substring(2);
        try { s = IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES); }
        catch (IllegalArgumentException e) { return ""; }
        if (s.length() > 253 || s.indexOf('.') < 1) return "";
        for (String part : s.split("\\.", -1)) if (part.isEmpty() || part.length() > 63) return "";
        return s;
    }
    private static Set<String> normalizeAll(Collection<String> hosts) {
        Set<String> result = new HashSet<>();
        for (String h : hosts) { String n = normalize(h); if (!n.isEmpty()) result.add(n); }
        return result;
    }
    private static boolean matches(Set<String> rules, String host) {
        String current = host;
        while (!current.isEmpty()) {
            if (rules.contains(current)) return true;
            int dot = current.indexOf('.');
            if (dot < 0) break;
            current = current.substring(dot + 1);
        }
        return false;
    }
    public boolean blocks(String host) {
        String n = normalize(host);
        return !n.isEmpty() && !matches(allow, n) && matches(block, n);
    }
}
