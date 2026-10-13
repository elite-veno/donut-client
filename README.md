# Donut Client

A client-side Fabric mod for Minecraft Java 1.21.11 with storage, spawner and ancient debris highlights, amethyst-based activity detection, Freecam, module keybinds, and a minimal HUD.

## Install

1. Install Fabric Loader for Minecraft 1.21.11.
2. Install Fabric API 0.141.4 or newer for Minecraft 1.21.11.
3. Copy `donut-client-1.2.0.jar` into your Minecraft `mods` folder.
4. Start Minecraft with the Fabric profile.

Press **Right Shift** to open the click GUI. Freecam defaults to **F6**. Other module keys can be assigned in the click GUI or under Minecraft's keybind settings.

DebrisFinder only sees blocks the server has sent to your client. On servers with anti-xray, buried debris may be hidden or replaced with fake ores until it is exposed.

ActivityFinder outlines chunks whose amethyst geodes grew well past their generated state. Buds only grow from random ticks, which only run within a player's simulation distance, so a grown geode means someone spent a long time within a few chunks of it. Worldgen gives about a third of budding amethyst one bud of a random size and never more; ActivityFinder ignores chunks whose buds could plausibly be that, and at the default random tick speed a geode reaches the default 50% threshold after roughly an hour nearby. Harvested clusters, servers that change the random tick speed, or anti-xray that hides amethyst all reduce what it can see.

## Build

Install JDK 21, then run:

```powershell
.\gradlew.bat build
```

The finished mod is written to `build/libs/donut-client-1.2.0.jar`.

## License

MIT. The bundled Inter font uses the SIL Open Font License.
