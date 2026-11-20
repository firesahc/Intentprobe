package com.example.intentprobe.scan;

import com.example.intentprobe.model.RouteCandidate;

import net.dongliu.apk.parser.ApkFile;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Reads an installed APK's binary manifest and expands each intent-filter into
 * launch candidates.
 *
 * <p>{@code apk-parser} has no Activity model, so the manifest XML is parsed
 * directly. Only the manifest entry is decompressed, not the whole APK.</p>
 */
public final class ApkManifestScanner {

    private static final String NS = "http://schemas.android.com/apk/res/android";
    private static final int MAX_CANDIDATES = 3000;

    private ApkManifestScanner() {}

    /** Manifest routes plus the schemes a dex route scan can look for. */
    public static final class ScanResult {
        public final List<RouteCandidate> routes;
        public final Set<String> schemes;

        ScanResult(List<RouteCandidate> routes, Set<String> schemes) {
            this.routes = routes;
            this.schemes = schemes;
        }
    }

    public static List<RouteCandidate> scan(String apkPath, String pkg) throws Exception {
        return scanAll(apkPath, pkg).routes;
    }

    public static ScanResult scanAll(String apkPath, String pkg) throws Exception {
        String xml = readManifest(apkPath);
        return new ScanResult(parse(xml, pkg), collectSchemes(xml));
    }

    private static String readManifest(String apkPath) throws Exception {
        File file = new File(apkPath);
        if (!file.isFile()) {
            throw new IOException("APK 不存在：" + apkPath);
        }
        String xml;
        try (ApkFile apk = new ApkFile(file)) {
            xml = apk.getManifestXml();
        }
        if (xml == null || xml.isEmpty()) {
            throw new IOException("无法读取 manifest");
        }
        return xml;
    }

    /** All schemes declared by activity intent-filters, lower-cased. */
    public static Set<String> collectSchemes(String xml) throws Exception {
        Document document = newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        Element manifest = document.getDocumentElement();
        Set<String> schemes = new LinkedHashSet<>();
        if (manifest == null) return schemes;
        Element application = firstChild(manifest, "application");
        if (application == null) return schemes;
        for (Element component : children(application)) {
            String tag = component.getTagName();
            if (!"activity".equals(tag) && !"activity-alias".equals(tag)) continue;
            for (Element child : children(component)) {
                if (!"intent-filter".equals(child.getTagName())) continue;
                for (Element data : children(child)) {
                    if (!"data".equals(data.getTagName())) continue;
                    String scheme = attr(data, "scheme");
                    if (!isEmpty(scheme)) {
                        schemes.add(scheme.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return schemes;
    }

    public static List<RouteCandidate> parse(String xml, String pkg) throws Exception {
        Document document = newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        Element manifest = document.getDocumentElement();
        if (manifest == null) return Collections.emptyList();
        Element application = firstChild(manifest, "application");
        if (application == null) return Collections.emptyList();
        if (!boolAttr(application, "enabled", true)) return Collections.emptyList();

        String appPermission = attr(application, "permission");
        List<RouteCandidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Element component : children(application)) {
            String tag = component.getTagName();
            if (!"activity".equals(tag) && !"activity-alias".equals(tag)) continue;
            if (!boolAttr(component, "enabled", true)) continue;

            String declaredName = attr(component, "name");
            if (isEmpty(declaredName)) continue;
            String componentName = resolveName(pkg, declaredName);

            String alias = null;
            String target = componentName;
            if ("activity-alias".equals(tag)) {
                alias = componentName;
                String targetName = attr(component, "targetActivity");
                if (isEmpty(targetName)) continue;
                target = resolveName(pkg, targetName);
            }

            List<Element> filters = new ArrayList<>();
            for (Element child : children(component)) {
                if ("intent-filter".equals(child.getTagName())) filters.add(child);
            }

            String permission = attr(component, "permission");
            if (isEmpty(permission)) permission = appPermission;
            String launchMode = attr(component, "launchMode");

            String exportedValue = attr(component, "exported");
            boolean inferred = isEmpty(exportedValue);
            boolean exported = inferred ? !filters.isEmpty() : parseBool(exportedValue, false);

            if (filters.isEmpty()) {
                if (exported) {
                    RouteCandidate route = new RouteCandidate();
                    route.pkg = pkg;
                    route.activity = target;
                    route.alias = alias;
                    route.exported = true;
                    route.exportedInferred = inferred;
                    route.permission = permission;
                    route.launchMode = launchMode;
                    route.filterIndex = -1;
                    add(result, seen, route);
                }
                continue;
            }

            for (int i = 0; i < filters.size(); i++) {
                Element filter = filters.get(i);
                FilterData data = readFilter(filter);
                if (data.actions.isEmpty() && data.schemes.isEmpty()
                        && data.mimeTypes.isEmpty()) {
                    continue;
                }
                List<String> actions = data.actions.isEmpty()
                        ? Collections.singletonList(null) : new ArrayList<>(data.actions);
                List<DataPoint> points = data.points();
                if (points.isEmpty()) {
                    for (String action : actions) {
                        RouteCandidate route = baseRoute(pkg, target, alias, exported,
                                inferred, permission, launchMode, i);
                        route.action = action;
                        route.categories = join(data.categories);
                        add(result, seen, route);
                    }
                    continue;
                }
                for (String action : actions) {
                    for (DataPoint point : points) {
                        RouteCandidate route = baseRoute(pkg, target, alias, exported,
                                inferred, permission, launchMode, i);
                        route.action = action;
                        route.categories = join(data.categories);
                        route.scheme = point.scheme;
                        route.host = point.host;
                        route.port = point.port;
                        route.path = point.path;
                        route.pathPrefix = point.pathPrefix;
                        route.pathPattern = point.pathPattern;
                        route.pathSuffix = point.pathSuffix;
                        route.mimeType = point.mimeType;
                        route.uri = point.uri;
                        route.patternOnly = point.patternOnly;
                        add(result, seen, route);
                    }
                }
            }
        }

        result.sort(Comparator
                .comparing((RouteCandidate r) -> r.activity,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(r -> r.action,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(r -> r.uri,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return result;
    }

    private static RouteCandidate baseRoute(String pkg, String target, String alias,
            boolean exported, boolean inferred, String permission, String launchMode,
            int filterIndex) {
        RouteCandidate route = new RouteCandidate();
        route.pkg = pkg;
        route.activity = target;
        route.alias = alias;
        route.exported = exported;
        route.exportedInferred = inferred;
        route.permission = permission;
        route.launchMode = launchMode;
        route.filterIndex = filterIndex;
        return route;
    }

    private static void add(List<RouteCandidate> result, Set<String> seen,
            RouteCandidate route) {
        if (result.size() >= MAX_CANDIDATES) return;
        String key = route.activity + '|' + route.alias + '|' + route.action + '|'
                + route.uri + '|' + route.mimeType + '|' + route.categories + '|'
                + route.exported;
        if (!seen.add(key)) return;
        result.add(route);
    }

    private static FilterData readFilter(Element filter) {
        FilterData data = new FilterData();
        for (Element child : children(filter)) {
            switch (child.getTagName()) {
                case "action": {
                    String name = attr(child, "name");
                    if (!isEmpty(name)) data.actions.add(name);
                    break;
                }
                case "category": {
                    String name = attr(child, "name");
                    if (!isEmpty(name)) data.categories.add(name);
                    break;
                }
                case "data": {
                    String scheme = attr(child, "scheme");
                    String host = attr(child, "host");
                    String port = attr(child, "port");
                    String path = attr(child, "path");
                    String prefix = attr(child, "pathPrefix");
                    String pattern = attr(child, "pathPattern");
                    String suffix = attr(child, "pathSuffix");
                    String mime = attr(child, "mimeType");
                    if (!isEmpty(scheme)) data.schemes.add(scheme);
                    if (!isEmpty(host)) data.hosts.add(host);
                    if (!isEmpty(port)) data.ports.add(port);
                    if (!isEmpty(path)) data.paths.add(path);
                    if (!isEmpty(prefix)) data.prefixes.add(prefix);
                    if (!isEmpty(pattern)) data.patterns.add(pattern);
                    if (!isEmpty(suffix)) data.suffixes.add(suffix);
                    if (!isEmpty(mime)) data.mimeTypes.add(mime);
                    break;
                }
                default:
                    break;
            }
        }
        return data;
    }

    private static final class FilterData {
        final LinkedHashSet<String> actions = new LinkedHashSet<>();
        final LinkedHashSet<String> categories = new LinkedHashSet<>();
        final LinkedHashSet<String> schemes = new LinkedHashSet<>();
        final LinkedHashSet<String> hosts = new LinkedHashSet<>();
        final LinkedHashSet<String> ports = new LinkedHashSet<>();
        final LinkedHashSet<String> paths = new LinkedHashSet<>();
        final LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        final LinkedHashSet<String> patterns = new LinkedHashSet<>();
        final LinkedHashSet<String> suffixes = new LinkedHashSet<>();
        final LinkedHashSet<String> mimeTypes = new LinkedHashSet<>();

        List<DataPoint> points() {
            List<DataPoint> points = new ArrayList<>();
            if (schemes.isEmpty()) {
                if (!mimeTypes.isEmpty()) {
                    for (String mime : mimeTypes) {
                        DataPoint point = new DataPoint();
                        point.mimeType = mime;
                        points.add(point);
                    }
                }
                return points;
            }
            String path = first(paths);
            String prefix = first(prefixes);
            String pattern = first(patterns);
            String suffix = first(suffixes);
            boolean patternOnly = path == null && prefix == null
                    && (pattern != null || suffix != null);
            String pathPart = "";
            if (path != null) {
                pathPart = path.startsWith("/") ? path : "/" + path;
            } else if (prefix != null) {
                pathPart = prefix.startsWith("/") ? prefix : "/" + prefix;
            }
            String mime = first(mimeTypes);
            List<String> hostList = hosts.isEmpty()
                    ? Collections.singletonList(null) : new ArrayList<>(hosts);
            String port = first(ports);
            for (String scheme : schemes) {
                for (String host : hostList) {
                    DataPoint point = new DataPoint();
                    point.scheme = scheme;
                    point.host = host;
                    point.port = host == null ? null : port;
                    point.path = path;
                    point.pathPrefix = prefix;
                    point.pathPattern = pattern;
                    point.pathSuffix = suffix;
                    point.mimeType = mime;
                    point.patternOnly = patternOnly;
                    StringBuilder uri = new StringBuilder(scheme).append("://");
                    if (host != null) {
                        uri.append(host);
                        if (port != null) uri.append(':').append(port);
                        uri.append(pathPart);
                    }
                    point.uri = uri.toString();
                    points.add(point);
                }
            }
            return points;
        }
    }

    private static final class DataPoint {
        String scheme;
        String host;
        String port;
        String path;
        String pathPrefix;
        String pathPattern;
        String pathSuffix;
        String mimeType;
        String uri;
        boolean patternOnly;
    }

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Android's built-in parser rejects several JAXP hardening features at
        // newDocumentBuilder() time, so keep the factory minimal. The source is
        // a compiled binary manifest, which cannot carry a DTD or external entity.
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder();
    }

    private static Element firstChild(Element parent, String tag) {
        for (Element child : children(parent)) {
            if (tag.equals(child.getTagName())) return child;
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static String attr(Element element, String name) {
        String value = element.getAttributeNS(NS, name);
        if (value == null || value.isEmpty()) {
            value = element.getAttribute("android:" + name);
        }
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean boolAttr(Element element, String name, boolean fallback) {
        String value = attr(element, name);
        return isEmpty(value) ? fallback : parseBool(value, fallback);
    }

    private static boolean parseBool(String value, boolean fallback) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return fallback;
    }

    private static String resolveName(String pkg, String name) {
        String trimmed = name.trim();
        if (trimmed.startsWith(".")) return pkg + trimmed;
        if (!trimmed.contains(".")) return pkg + "." + trimmed;
        return trimmed;
    }

    private static String join(Set<String> values) {
        if (values == null || values.isEmpty()) return null;
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(',');
            builder.append(value);
        }
        return builder.toString();
    }

    private static String first(Set<String> values) {
        for (String value : values) return value;
        return null;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
