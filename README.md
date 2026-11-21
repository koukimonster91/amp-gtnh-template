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

**Start**: `java -Xms… -Xmx… -Dfml.readTimeout=180 -Duser.language=en @java9args.txt -jar lwjgl3ify-forgePatches.jar nogui`
(same as GTNH's `startserver-java9` script, without its auto-restart loop; use AMP's own crash restart instead).
Stops with `stop`, waits up to 180 s for the save.

Settings in AMP: EULA, MOTD, player limit, whitelist, online mode, flight, world name/seed/type
(rwg default), difficulty, game mode, PvP, view distance, RAM, Java version, extra JVM args, pack URL.

## Upgrading GTNH
Stop the server, take an AMP backup, paste the new pack link into *Server Pack URL*, click Update.

## Installing into AMP
Either add this repo under **Configuration → Instance Deployment → Configuration Repositories**
as `koukimonster91/amp-gtnh-template:main` and click Fetch, or copy the files (all except README)
into a `LOCAL-gtnh-main` folder under ADS's `Plugins/ADSModule/DeploymentTemplates/`, then refresh the browser.
