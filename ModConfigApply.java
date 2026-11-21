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
                    Long.parseLong(v);
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

        int changed = 0, filesChanged = 0, bad = 0;
        for (Map.Entry<String, Map<String, String>> entry : byFile.entrySet()) {
            Path file = base.resolve(entry.getKey()).normalize();
            if (!file.startsWith(base) || !Files.isRegularFile(file)) continue;

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
                + (bad > 0 ? ", " + bad + " invalid value(s) ignored" : ""));
    }
}
