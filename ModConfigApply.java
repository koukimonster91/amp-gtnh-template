import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies mod config values chosen in AMP to the Forge .cfg files before the server starts.
 *
 * AMP writes amp-modconfigs.txt (one line per setting): file|category.path|T:name=value
 * where each key part has %, | and = escaped as %25, %7C and %3D.
 * Only setting lines that already exist in the .cfg are changed; comments, ordering,
 * line endings and every other byte of the file are kept.
 *
 * Usage: java ModConfigApply.java <server dir>
 */
public class ModConfigApply {
    static final Pattern CATEGORY = Pattern.compile("^\\s*(\"[^\"]*\"|[^{}#=]+?)\\s*\\{\\s*$");
    static final Pattern SETTING = Pattern.compile("^(\\s*)([BIDS]):(\"[^\"]*\"|[^=<]+?)=(.*)$");

    static String unescape(String s) {
        return s.replace("%7C", "|").replace("%3D", "=").replace("%25", "%");
    }

    static String stripQuotes(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }

    static String normalize(String type, String v) {
        v = v.replace("\r", "").replace("\n", " ").trim();
        try {
            switch (type) {
                case "B":
                    if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return v.toLowerCase();
                    return null;
                case "I":
                    // some mods (e.g. HungerOverhaul) store whole numbers as "4.0"
                    Double.parseDouble(v);
                    return v;
                case "D":
                    Double.parseDouble(v);
                    return v;
                default:
                    return v;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path values = base.resolve("amp-modconfigs.txt");
        if (!Files.exists(values)) {
            System.out.println("[ModConfigs] amp-modconfigs.txt not found, nothing to apply");
            return;
        }

        Map<String, Map<String, String>> byFile = new TreeMap<>();
        for (String line : Files.readAllLines(values, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String[] parts = line.substring(0, eq).split("\\|", -1);
            if (parts.length != 3) continue;
            byFile.computeIfAbsent(unescape(parts[0]), k -> new HashMap<>())
                  .put(unescape(parts[1]) + "|" + unescape(parts[2]), line.substring(eq + 1));
        }

        int changed = 0, filesChanged = 0, bad = 0, created = 0;
        for (Map.Entry<String, Map<String, String>> entry : byFile.entrySet()) {
            Path file = base.resolve(entry.getKey()).normalize();
            if (!file.startsWith(base)) continue;
            if (!Files.exists(file)) {
                // Some mods only create their config on first start (e.g. after a clean pack install).
                // Write just the AMP values; Forge adds every other setting with its default when the mod loads.
                created += createFile(file, entry.getValue());
                continue;
            }
            if (!Files.isRegularFile(file)) continue;

            // ISO-8859-1 keeps every byte of the original file intact.
            String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            String nl = text.contains("\r\n") ? "\r\n" : "\n";
            String[] lines = text.split("\r?\n", -1);
            Deque<String> stack = new ArrayDeque<>();
            boolean modified = false;

            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                Matcher c = CATEGORY.matcher(l);
                if (c.matches()) { stack.addLast(stripQuotes(c.group(1))); continue; }
                if (l.trim().equals("}")) { if (!stack.isEmpty()) stack.removeLast(); continue; }
                Matcher m = SETTING.matcher(l);
                if (!m.matches()) continue;

                String key = String.join(".", stack) + "|" + m.group(2) + ":" + stripQuotes(m.group(3));
                String wanted = entry.getValue().get(key);
                if (wanted == null) continue;
                String v = normalize(m.group(2), wanted);
                if (v == null) {
                    bad++;
                    System.out.println("[ModConfigs] ignored invalid value '" + wanted + "' for " + entry.getKey() + " " + key);
                    continue;
                }
                String encoded = new String(v.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
                if (!m.group(4).equals(encoded)) {
                    lines[i] = m.group(1) + m.group(2) + ":" + m.group(3) + "=" + encoded;
                    modified = true;
                    changed++;
                    System.out.println("[ModConfigs] " + entry.getKey() + " " + key + " = " + v);
                }
            }

            if (modified) {
                Files.write(file, String.join(nl, lines).getBytes(StandardCharsets.ISO_8859_1));
                filesChanged++;
            }
        }
        System.out.println("[ModConfigs] " + changed + " setting(s) changed in " + filesChanged + " file(s)"
                + (created > 0 ? ", " + created + " missing file(s) created" : "")
                + (bad > 0 ? ", " + bad + " invalid value(s) ignored" : ""));
    }

    static String quote(String name) {
        return name.matches("[A-Za-z0-9_.\\-]+") ? name : "\"" + name + "\"";
    }

    /** Writes a minimal Forge config holding only the given settings, nested by category. */
    static int createFile(Path file, Map<String, String> settings) throws Exception {
        // category path -> lines "T:name=value"
        Map<String, java.util.List<String>> cats = new TreeMap<>();
        for (Map.Entry<String, String> s : settings.entrySet()) {
            int bar = s.getKey().indexOf('|');
            String cat = s.getKey().substring(0, bar), setting = s.getKey().substring(bar + 1);
            String type = setting.substring(0, 1), name = setting.substring(2);
            String v = normalize(type, s.getValue());
            if (v == null) continue;
            cats.computeIfAbsent(cat, k -> new java.util.ArrayList<>()).add(type + ":" + quote(name) + "=" + v);
        }
        if (cats.isEmpty()) return 0;
        StringBuilder out = new StringBuilder("# Configuration file\n# Created by ModConfigApply from AMP settings; the mod adds its other settings on load.\n\n");
        for (Map.Entry<String, java.util.List<String>> c : cats.entrySet()) {
            String[] path = c.getKey().isEmpty() ? new String[0] : c.getKey().split("\\.");
            for (int d = 0; d < path.length; d++) out.append("    ".repeat(d)).append(quote(path[d])).append(" {\n");
            for (String l : c.getValue()) out.append("    ".repeat(path.length)).append(l).append("\n");
            for (int d = path.length - 1; d >= 0; d--) out.append("    ".repeat(d)).append("}\n");
            out.append("\n");
        }
        Files.createDirectories(file.getParent());
        Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[ModConfigs] created " + file.getFileName() + " with " + settings.size() + " AMP value(s)");
        return 1;
    }
}
