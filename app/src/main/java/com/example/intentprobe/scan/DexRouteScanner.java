package com.example.intentprobe.scan;

import android.content.Intent;

import com.example.intentprobe.model.RouteCandidate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds hard-coded route strings in an APK's dex files.
 *
 * <p>Single-Activity apps never start a new Activity for an internal tab, so
 * there is no Intent to hook. Their router usually keeps a string table such as
 * {@code myapp://message} in the dex constant pool. This scanner searches the
 * schemes declared by the manifest and turns every complete constant into a
 * route candidate.</p>
 *
 * <p>Only dex entries are read, one 64 KB block at a time. A small carry keeps
 * a URI from being cut at a block boundary, and a URI is accepted only when its
 * terminator was seen inside the block.</p>
 */
public final class DexRouteScanner {

    private static final int BLOCK_SIZE = 64 * 1024;
    private static final int MAX_URI_LEN = 1024;
    private static final int MAX_ROUTES = 2000;
    private static final int MAX_CARRY = MAX_URI_LEN + 64;

    private static final String[] RESOURCE_SUFFIXES = {
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".bmp", ".ico",
            ".css", ".js", ".json", ".html", ".htm", ".xml",
            ".mp3", ".mp4", ".wav", ".ogg", ".woff", ".woff2", ".ttf"
    };

    private DexRouteScanner() {}

    public static List<RouteCandidate> scan(String apkPath, String pkg, Set<String> schemes)
            throws IOException {
        if (schemes == null || schemes.isEmpty()) return Collections.emptyList();

        // Manifest schemes include http/https for App Links. Their dex strings
        // are mostly asset and page URLs, which would drown the real routes.
        Set<String> usable = new LinkedHashSet<>();
        for (String scheme : schemes) {
            if (scheme == null || scheme.isEmpty()) continue;
            String lower = scheme.toLowerCase(Locale.ROOT);
            if ("http".equals(lower) || "https".equals(lower)) continue;
            usable.add(lower);
        }
        if (usable.isEmpty()) return Collections.emptyList();

        Map<String, RouteCandidate> found = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && found.size() < MAX_ROUTES) {
                ZipEntry entry = entries.nextElement();
                if (!isDex(entry.getName())) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    scanStream(in, pkg, usable, found);
                }
            }
        }

        List<RouteCandidate> result = new ArrayList<>(found.values());
        Collections.sort(result, (a, b) -> a.uri.compareToIgnoreCase(b.uri));
        return result;
    }

    private static boolean isDex(String name) {
        if (name == null) return false;
        if (!name.startsWith("classes") || !name.endsWith(".dex")) return false;
        String middle = name.substring("classes".length(), name.length() - ".dex".length());
        if (middle.isEmpty()) return true;
        for (int i = 0; i < middle.length(); i++) {
            if (!Character.isDigit(middle.charAt(i))) return false;
        }
        return true;
    }

    private static void scanStream(InputStream in, String pkg, Set<String> schemes,
            Map<String, RouteCandidate> found) throws IOException {
        byte[] buffer = new byte[BLOCK_SIZE];
        byte[] carry = new byte[0];
        int read;
        while ((read = in.read(buffer)) > 0 && found.size() < MAX_ROUTES) {
            byte[] chunk;
            if (carry.length == 0) {
                chunk = Arrays.copyOf(buffer, read);
            } else {
                chunk = new byte[carry.length + read];
                System.arraycopy(carry, 0, chunk, 0, carry.length);
                System.arraycopy(buffer, 0, chunk, carry.length, read);
            }
            scanChunk(chunk, pkg, schemes, found);
            int keep = Math.min(chunk.length, MAX_CARRY);
            carry = Arrays.copyOfRange(chunk, chunk.length - keep, chunk.length);
        }
    }

    private static void scanChunk(byte[] chunk, String pkg, Set<String> schemes,
            Map<String, RouteCandidate> found) {
        for (String scheme : schemes) {
            if (scheme == null || scheme.isEmpty()) continue;
            String lower = scheme.toLowerCase(Locale.ROOT);
            search(chunk, lower + "://", pkg, lower, found);
            if (!lower.equals(scheme)) {
                search(chunk, scheme + "://", pkg, lower, found);
            }
        }
    }

    private static void search(byte[] chunk, String needle, String pkg, String scheme,
            Map<String, RouteCandidate> found) {
        byte[] pattern = needle.getBytes(StandardCharsets.US_ASCII);
        int from = 0;
        while (from <= chunk.length - pattern.length && found.size() < MAX_ROUTES) {
            int index = indexOf(chunk, pattern, from);
            if (index < 0) return;
            int end = index + pattern.length;
            while (end < chunk.length && isUriByte(chunk[end])) end++;
            // A URI that reaches the block end may be truncated; the carry will
            // present it again in the next block with its terminator.
            if (end < chunk.length) {
                String raw = new String(chunk, index, end - index, StandardCharsets.US_ASCII);
                add(found, pkg, scheme, raw);
            }
            from = index + pattern.length;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        int last = haystack.length - needle.length;
        outer:
        for (int i = from; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** RFC 3986 ASCII, stopping at the characters that cannot appear in a URI. */
    private static boolean isUriByte(byte b) {
        if (b <= 0x20 || b >= 0x7f) return false;
        switch (b) {
            case '"':
            case '\'':
            case '<':
            case '>':
            case '\\':
            case '`':
            case '{':
            case '}':
            case '|':
            case '^':
                return false;
            default:
                return true;
        }
    }

    private static void add(Map<String, RouteCandidate> found, String pkg, String scheme,
            String raw) {
        String uri = trimTail(raw);
        if (uri.length() <= scheme.length() + 3) return;
        if (uri.contains("_$$Fragment")) return;
        if (uri.contains("...")) return;

        String lowerUri = uri.toLowerCase(Locale.ROOT);
        for (String suffix : RESOURCE_SUFFIXES) {
            if (lowerUri.endsWith(suffix)) return;
        }

        String rest = uri.substring(scheme.length() + 3);
        if (rest.isEmpty() || rest.charAt(0) == '?') return;

        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String host = cut == 0 ? null : rest.substring(0, cut);
        String path = cut < rest.length() ? rest.substring(cut) : null;

        String key = uri.toLowerCase(Locale.ROOT);
        if (found.containsKey(key)) return;

        RouteCandidate route = new RouteCandidate();
        route.pkg = pkg;
        route.activity = "";
        route.exported = true;
        route.exportedInferred = true;
        route.action = Intent.ACTION_VIEW;
        route.scheme = scheme;
        route.host = host;
        route.path = path;
        route.uri = uri;
        route.patternOnly = needsArguments(uri);
        route.origin = "dex";
        route.filterIndex = -1;
        found.put(key, route);
    }

    private static String trimTail(String value) {
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c == ';' || c == ',' || c == ')' || c == ']' || c == ':') {
                end--;
            } else {
                break;
            }
        }
        return value.substring(0, end);
    }

    private static boolean needsArguments(String uri) {
        int query = uri.indexOf('?');
        if (query < 0) return false;
        String tail = uri.substring(query + 1);
        return tail.isEmpty() || tail.endsWith("=") || tail.endsWith("&");
    }
}
