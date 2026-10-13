# Donut Client

A client-side Fabric mod for Minecraft Java 1.21.11 with storage, spawner and ancient debris highlights, amethyst-based activity detection with Auto RTP, Freecam, module keybinds, and a minimal HUD.

## Install

1. Install Fabric Loader for Minecraft 1.21.11.
2. Install Fabric API 0.141.4 or newer for Minecraft 1.21.11.
3. Copy `donut-client-1.2.0.jar` into your Minecraft `mods` folder.
4. Start Minecraft with the Fabric profile.

Press **Right Shift** to open the click GUI. Freecam defaults to **F6**. Other module keys can be assigned in the click GUI or under Minecraft's keybind settings.

DebrisFinder only sees blocks the server has sent to your client. On servers with anti-xray, buried debris may be hidden or replaced with fake ores until it is exposed.

ActivityFinder outlines chunks whose amethyst geodes grew well past their generated state. Buds only grow from random ticks, which only run within a player's simulation distance, so a grown geode means someone spent a long time within a few chunks of it. By default the square sits on the chunk's highest block, with faint corner lines down to the geode, so you can spot it from the surface; turn off Surface marker to draw it around the geode instead. All loaded chunks (on by default) scans every chunk your client holds, from the top of the world to the bottom, without a range limit or distance fade; turn it off to use the detection range. Worldgen gives about a third of budding amethyst one bud of a random size and never more; ActivityFinder ignores chunks whose buds could plausibly be that, and at the default random tick speed a geode reaches the default 50% threshold after roughly an hour nearby. Harvested clusters, servers that change the random tick speed, or anti-xray that hides amethyst all reduce what it can see.

Auto RTP sends a random-teleport command (`rtp` by default), clicks the configured item (`Overworld`) when the command opens a world menu, waits for the teleport, lets ActivityFinder scan for a few seconds, and stops with a chat message and a sound once a chunk reaches its Stop at growth (55% by default, separate from ActivityFinder's square threshold). Each command is sent exactly as if typed in chat and echoed as a grey local line, and the teleport may take up to a minute. Otherwise it waits out its interval and tries again, up to its attempt limit. Cooldown messages from the server delay the next attempt instead of being retried. It also stops when you take damage, when three commands in a row do not teleport you, when the menu lacks the configured item, or when three areas in a row hold no budding amethyst at all. It never starts on its own after joining. Check a server's rules before automating commands.

## Build

Install JDK 21, then run:

```powershell
.\gradlew.bat build
```

The finished mod is written to `build/libs/donut-client-1.2.0.jar`.

## License

MIT. The bundled Inter font uses the SIL Open Font License.
