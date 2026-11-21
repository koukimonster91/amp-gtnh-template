# AMP template: Minecraft GT New Horizons

Generic-module template for CubeCoders AMP that installs and runs the
GT New Horizons server pack (Java 17+ build, default 2.9.0).

## What it does

**Update** (first install and every later Update click):
1. Downloads the zip at *Server Pack URL* from downloads.gtnewhorizons.com into `gtnh-staging/` and unzips it.
2. If *Clean Install on Update* is on, deletes `mods/`, `libraries/`, `config/`.
3. Copies the pack in, but never overwrites files you already have:
   `server.properties`, `eula.txt`, `ops.json`, `whitelist.json`, `banned-*.json`,
   `usercache.json`, `serverutilities/`, `journeymap/`. The world folder is never touched.
4. Downloads Eclipse Temurin Java (17 / 21 / 25, default 21) into the instance.
5. Runs `GtnhImport.java`: does the *Import From Instance* copy (see below) if one is set, then copies every
   `.jar` in `extra-mods/` into `mods/`.

**Start**: `java -Xms… -Xmx… -Dfml.readTimeout=180 -Duser.language=en @java9args.txt -jar lwjgl3ify-forgePatches.jar nogui`
(same as GTNH's `startserver-java9` script, without its auto-restart loop; use AMP's own crash restart instead).
Stops with `stop`, waits up to 180 s for the save.

Settings in AMP: EULA, MOTD, player limit, whitelist, online mode, flight, world name/seed/type
(rwg default), difficulty, game mode, PvP, view distance, RAM, Java version, extra JVM args, pack URL.

## Mod configs in AMP

About 8,750 mod settings from 170 mods can be changed in AMP. They are grouped alphabetically
into 10 Configuration sidebar entries (`Mods A`, `Mods B`, `Mods C-D` …), with one tab per mod.
Left out on purpose: big data tables (IguanaTinkerTweaks block/material tables, RTG biome tables,
TGregworks, SuperTiC effects, Blood Magic, Botania flower and InfernalMobs per-mob lists),
client-only settings, block/item/biome/potion/enchantment/dimension IDs, and debug/internal switches. Those stay editable in the File Manager.

How it works: AMP writes your choices to `amp-modconfigs.txt`, and before every start the
pre-start stage runs `ModConfigApply.java` (with the instance's own Java), which updates only
those lines in the mod `.cfg` files. AMP is the source of truth for these settings, so a value
changed by hand in a `.cfg` file is put back to the AMP value on the next start. Because the values
are reapplied on every start, they also survive a clean pack update.

To refresh the settings for a new GTNH version, run the generator against that version's server files:
`python tools/gen_modconfigs.py <server folder>`. The report goes to `tools/modconfigs-report.txt`.

## Extra mods
Mods that are not part of the GTNH pack (for example Distant Horizons) go in `extra-mods/` as well as `mods/`.
Every Update copies them back into `mods/`, so a clean pack install does not lose them. To remove one,
delete it from both folders. If a later GTNH version ships the same mod itself, delete your copy from `extra-mods/`.

## Upgrading GTNH
**In a new instance (recommended):**
1. Create a new instance from this template. Before the first Update, set *Server Pack URL* to the new
   version and *Import From Instance* to the old instance's folder name as ADS shows it (e.g. `MinecraftGTNewHorizons01`).
2. Stop the old instance, then click Update in the new one.
3. The world, player lists (ops, whitelist, bans, user cache), `serverutilities/` (homes, claims, ranks),
   `journeymap/`, `config/JourneyMapServer/` (keeps the JourneyMap world ID), `visualprospecting/`,
   `blueprints/` and `extra-mods/` are copied over. The old instance is only read, never changed.
   ServerUtilities' own `backups/` folder is not copied.
4. The import only runs once, and never if the new instance already has a world.
5. AMP settings, including the mod settings, are not copied. The old instance's values are saved in
   `imported-amp-modconfigs.txt` so you can compare and set them again.

The import needs both instances on the same machine without Docker (a container cannot see other instances).

**In place:** stop the server, take an AMP backup, paste the new pack link into *Server Pack URL*, click Update.

## Installing into AMP
Either add this repo under **Configuration → Instance Deployment → Configuration Repositories**
as `koukimonster91/amp-gtnh-template:main` and click Fetch, or copy the files (all except README)
into a `LOCAL-gtnh-main` folder under ADS's `Plugins/ADSModule/DeploymentTemplates/`, then refresh the browser.
