import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;

/**
 * Runs at the end of every AMP Update, after the GTNH pack is installed.
 *
 * 1. Import (only when a name is given and this instance has no world yet):
 *    copies the world, player lists and mod data that live outside the world folder
 *    from another AMP instance of this template. The old instance is only read, never changed.
 * 2. Extra mods: copies every .jar in extra-mods/ into mods/, so mods that are not part of
 *    the pack (e.g. Distant Horizons) survive a clean pack install.
 *
 * Usage: java GtnhImport.java <server dir> [old instance name]
 */
public class GtnhImport {
    static final String[] FILES = {
        "ops.json", "whitelist.json", "banned-ips.json", "banned-players.json",
        "usercache.json", "usernamecache.json"
    };
    // Folders outside the world that hold per-server data (not pack files).
    static final String[] DIRS = {
        "serverutilities", "journeymap", "config/JourneyMapServer", "visualprospecting", "blueprints", "extra-mods"
    };

    public static void main(String[] args) throws Exception {
        Path base = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        String from = args.length > 1 ? args[1].trim() : "";
        // World Name from this instance's AMP settings; server.properties may not exist yet on a fresh instance
        String newWorld = args.length > 2 && !args[2].trim().isEmpty() ? args[2].trim() : levelName(base);
        if (!from.isEmpty()) importFrom(base, from, newWorld);
        extraMods(base);
    }

    static String levelName(Path serverDir) {
        Path p = serverDir.resolve("server.properties");
        if (Files.isRegularFile(p)) {
            try (InputStream in = Files.newInputStream(p)) {
                Properties props = new Properties();
                props.load(in);
                String n = props.getProperty("level-name", "").trim();
                if (!n.isEmpty()) return n;
            } catch (IOException ignored) { }
        }
        return "world";
    }

    static void importFrom(Path base, String name, String newWorld) throws IOException {
        if (!name.matches("[A-Za-z0-9_.\\-]+") || name.equals(".") || name.equals("..")) {
            System.out.println("[Import] '" + name + "' is not a valid instance name, import skipped");
            return;
        }
        Path marker = base.resolve(".gtnh-imported");
        if (Files.exists(marker)) {
            System.out.println("[Import] already imported from " + new String(Files.readAllBytes(marker)).trim() + ", skipped");
            return;
        }
        // base = .../instances/<this instance>/minecraft-gtnh
        Path instances = base.getParent() == null ? null : base.getParent().getParent();
        Path old = instances == null ? null : instances.resolve(name).resolve(base.getFileName().toString()).normalize();
        if (base.equals(old)) {
            System.out.println("[Import] '" + name + "' is this instance; enter the OLD instance's name. Import skipped");
            return;
        }
        if (old == null || !old.startsWith(instances) || !Files.isDirectory(old)) {
            System.out.println("[Import] instance folder not found: " + old + " (import skipped). "
                + "Use the instance's folder name as shown in ADS, e.g. MinecraftGTNewHorizons01");
            return;
        }

        String world = levelName(old);
        Path oldWorld = old.resolve(world);
        if (!Files.isRegularFile(oldWorld.resolve("level.dat"))) {
            System.out.println("[Import] no world '" + world + "' with a level.dat in " + old + ", import skipped");
            return;
        }
        Path log = old.resolve("logs").resolve("latest.log");
        if (Files.exists(log) && System.currentTimeMillis() - Files.getLastModifiedTime(log).toMillis() < 120_000) {
            System.out.println("[Import] " + name + " looks like it is still running (its log changed in the last 2 minutes). "
                + "Stop it, then click Update again. Import skipped");
            return;
        }
        for (String w : new String[] { world, newWorld }) {
            if (Files.exists(base.resolve(w).resolve("level.dat"))) {
                System.out.println("[Import] this instance already has a world ('" + w + "'), import skipped so nothing is overwritten");
                return;
            }
        }

        System.out.println("[Import] copying from " + old);
        long[] count = { 0, 0 };
        copyTree(oldWorld, base.resolve(world), false, count);
        System.out.println("[Import] world '" + world + "' copied");
        for (String f : FILES) {
            Path src = old.resolve(f);
            if (Files.isRegularFile(src)) {
                Files.copy(src, base.resolve(f), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                count[0]++;
                count[1] += Files.size(src);
            }
        }
        for (String d : DIRS) {
            Path src = old.resolve(d);
            if (Files.isDirectory(src)) {
                // keep the new pack's .cfg files; AMP's mod settings are applied to them on start
                copyTree(src, base.resolve(d), true, count);
                System.out.println("[Import] " + d + "/ copied");
            }
        }
        if (!world.equals(newWorld)) {
            System.out.println("[Import] NOTE: the old world is named '" + world + "' but this instance uses '" + newWorld
                + "'. Set World Name to '" + world + "' in this instance's settings before starting");
        }
        Path oldSettings = old.resolve("amp-modconfigs.txt");
        if (Files.isRegularFile(oldSettings)) {
            Files.copy(oldSettings, base.resolve("imported-amp-modconfigs.txt"), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[Import] the old instance's mod settings were saved to imported-amp-modconfigs.txt for reference;"
                + " AMP settings themselves are not carried over");
        }
        Files.write(marker, (name + "\n").getBytes());
        System.out.printf("[Import] done: %d files, %.1f MB%n", count[0], count[1] / 1048576.0);
    }

    static void copyTree(Path src, Path dst, boolean keepExistingCfg, long[] count) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String n = file.getFileName().toString();
                if (n.equals("session.lock")) return FileVisitResult.CONTINUE;
                Path target = dst.resolve(src.relativize(file).toString());
                if (keepExistingCfg && n.endsWith(".cfg") && Files.exists(target)) return FileVisitResult.CONTINUE;
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                count[0]++;
                count[1] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void extraMods(Path base) throws IOException {
        Path extra = base.resolve("extra-mods");
        if (!Files.isDirectory(extra)) {
            Files.createDirectories(extra);
            Files.write(extra.resolve("README.txt"), ("Put mod .jar files that are not part of the GTNH pack here.\n"
                + "They are copied into mods/ on every Update (including clean installs) and carried over by Import From Instance.\n"
                + "To remove such a mod, delete it from here AND from mods/.\n").getBytes());
        }
        Path mods = base.resolve("mods");
        Files.createDirectories(mods);
        int n = 0;
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(extra, "*.jar")) {
            for (Path jar : jars) {
                Files.copy(jar, mods.resolve(jar.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[ExtraMods] " + jar.getFileName());
                n++;
            }
        }
        System.out.println("[ExtraMods] " + n + " extra mod(s) copied into mods/");
    }
}
