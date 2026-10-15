# Donut Client

A client-side Fabric mod for Minecraft Java 26.2 for seeing what is hidden: storage, spawner and ancient debris highlights, X-ray, Fullbright, dug tunnel and hole detection, player and mob ESP with tracers, amethyst-based activity detection with Auto RTP, Freecam, module keybinds, distance labels and an info HUD. It carries the GoNuts auction trading bot inside it.

## Install

1. Install Fabric Loader 0.19.5 or newer for Minecraft 26.2 (Java 25).
2. Install Fabric API 0.158.0 or newer for Minecraft 26.2.
3. Copy `donut-client-2.0.0.jar` into your Minecraft `mods` folder, removing any older Donut Client jar and any separate GoNuts (`doughbay`) jar: GoNuts is already inside, and Fabric refuses to start with a separate copy beside it.
4. Start Minecraft with the Fabric profile.

Press **Right Shift** to open the menu. The sidebar lists the module categories (Finders, Render, Utility, HUD), the GoNuts pages (Overview and Settings) and the Keybinds and Theme pages. A category shows its modules as cards: the switch turns a module on or off, and clicking the card opens its settings, each with a short description; **Back** returns to the list. Settings people rarely change, such as opacity, line width and colors, sit behind **More options** at the end of a module's settings, and **Reset settings** below them puts every setting back to its default on a second click, leaving the module on or off and its key as they were. Keybinds lists every module's key, and the menu's own, in one place (a right click puts a key back to its default), and Theme sets the accent color and optional snowfall. Type anywhere to search. Text fields select their text when clicked, so typing or pasting replaces it; Enter saves and Esc leaves the field as it was. Backspace or the mouse's back button goes back from a module's settings. Freecam defaults to **F7** (F6 is vanilla's debug options key). Other module keys can be assigned from the Bind row at the top of each module's settings, or under Minecraft's keybind settings.

### Seeing underground

Every finder can draw **tracers**, lines from your crosshair to each highlight, and the nearest finds get **distance labels** on screen. The **info panel** at the top left lists each enabled finder with its count and the nearest find's distance, with an arrow pointing to it. Labels and the info panel can be turned off on the menu's HUD page.

**StorageFinder** highlights chests, trapped and ender chests, shulker boxes, barrels, hoppers, droppers, dispensers, furnaces, smokers and blast furnaces, and chest or hopper minecarts. **SpawnerFinder** colors spawners a player has been near: a spawner's timer stays at 20 ticks until someone comes within 16 blocks, so any other timer, read the first time the spawner loads, means it was visited or placed.

**DebrisFinder** reads ancient debris straight from loaded chunk sections and updates the moment a block changes. Worldgen never places debris next to air, so nearly all real debris is buried, and anti-xray plugins act on exactly those blocks: some hide them, others fill buried netherrack with fake ores. DebrisFinder handles both:

- A chunk with far more buried debris than worldgen places is treated as faked. Only its exposed debris, which anti-xray never touches, and debris the server confirms with a block update are drawn; the rest is counted as hidden fakes.
- When a server hides buried debris, its settings say so. Mining reveals debris within a couple of blocks; DebrisFinder lights it up at once and plays a sound with its coordinates (Reveal alert).
- Seed estimates (off by default) mark buried netherrack where the world seed placed debris, in chunks whose gravel and blackstone show they follow that seed. A server can give its debris a seed of its own, and DonutSMP does, so the estimates check themselves: every estimate spot the server reveals while you mine counts as a hit or a miss, and once they keep coming up empty the estimates stop and the HUD says so.

The live scan in its settings shows the chunks scanned, buried and exposed debris, and hidden fakes.

**TunnelFinder** marks passages players dig: straight 1x2 tunnels, 1x1 vertical holes and 1-wide staircases, at least 10 blocks long by default. Caves and structures are rarely that narrow and regular. Anti-xray only disguises solid blocks, so dug-out air always reaches the client and this works on any server.

**EntityFinder** outlines other players (labeled with their name), and optionally hostile mobs, other mobs and dropped items, through walls with tracers.

**BaseFinder** marks chunks that look like someone's base. It scores each loaded chunk by what worldgen never places: shulker boxes, beacons, enchanting tables, conduits, netherite and diamond blocks, ender chests, lit nether portals, signs, redstone parts such as observers and comparators, concrete, a room of ten or more chests and barrels, and item frames and armor stands. Each kind has a weight and counts up to a cap, and the list was checked against every vanilla structure, so what villages, ancient cities, trial chambers and mansions place stays below the default score of 6 (one shulker box or beacon reaches it alone). Only blocks open to air count, because anti-xray's fake chests are always buried. A marked chunk gets a box around its evidence and a label such as "Base? 10: shulker box, 2 signs".

**Xray** hides stone, deepslate, dirt, sand, gravel, Nether terrain, End stone and water (each group can be switched, lava too, and extra block ids added), so ores, chests, spawners, caves' contents and anything built stands out. Sideways deepslate stays visible because only players place it that way. Only exposed ores hides ores that touch no air, which removes anti-xray fake ores. X-ray brightens the world while it is on. It changes how chunks are drawn, not the blocks themselves, and works with Minecraft's own renderer (with Fabric API), not with Sodium.

**Fullbright** lights every block fully without changing your brightness option, and by default removes distance, water, lava, blindness and darkness fog.

All finders only see blocks and entities the server has sent to your client.

### Activity and Auto RTP

ActivityFinder outlines chunks whose amethyst geodes grew well past their generated state. Buds only grow from random ticks, which only run within a player's simulation distance, so a grown geode means someone spent a long time within a few chunks of it. By default the square sits on the chunk's highest block, with faint corner lines down to the geode, so you can spot it from the surface; turn off Surface marker to draw it around the geode instead. All loaded chunks (on by default) scans every chunk your client holds, from the top of the world to the bottom, without a range limit or distance fade; turn it off to use the detection range. Worldgen gives about a third of budding amethyst one bud of a random size and never more; ActivityFinder ignores chunks whose buds could plausibly be that, and at the default random tick speed a geode reaches the default 50% threshold after roughly an hour nearby. Harvested clusters, servers that change the random tick speed, or anti-xray that hides amethyst all reduce what it can see.

Auto RTP sends a random-teleport command (`rtp` by default), clicks the configured item (`Overworld`) when the command opens a world menu, waits for the teleport, lets ActivityFinder scan for a few seconds, and stops with a chat message and a sound once a chunk reaches its Stop at growth (55% by default, separate from ActivityFinder's square threshold). Each command is sent exactly as if typed in chat and echoed as a grey local line, and the teleport may take up to a minute. Otherwise it waits out its interval and tries again, up to its attempt limit. Cooldown messages from the server delay the next attempt instead of being retried. It also stops when you take damage, when three commands in a row do not teleport you, when the menu lacks the configured item, or when three areas in a row hold no budding amethyst at all. It never starts on its own after joining. Check a server's rules before automating commands.

## Trading with GoNuts

Donut Client bundles [GoNuts](https://github.com/cody-raves/GoNuts), an auction-house trading bot for DonutSMP, and does its trading through it; the earlier AH Sniper is gone. GoNuts starts in its observe mode and never trades until you turn live trading on and start a session.

- **GoNuts > Overview** shows the trading session (state, trades started, money committed, open listings, balance) with Start, Resume or Stop and, while it trades, Wind down (buy nothing more, sell what it holds, then stop); Start closes the menu so GoNuts can work. A payroll rule waiting for your approval has an Approve button that takes a second click to confirm (no payroll payment goes out before that); what has been paid out in the last 24 hours against the daily cap; the features that can spend money or act for the account and are on; the update status; and a button for GoNuts's full screen with its markets, charts, payroll rules, presets and web panel.
- **GoNuts > Settings** has a card for each GoNuts settings group and one for its lists. Settings that move money, data or the server joined are marked in amber, change only on a second click within eight seconds, and are typed rather than dragged. Typed amber values save on a second Enter, after the first one says what they will be set to; Esc or clicking elsewhere drops them. Typed values take their unit: a minutes field reads `2h` as 120, and amounts read `250k`, `1.5M` or `1,5M`. A dot after a setting's name marks it as changed from GoNuts's default; hovering the row shows **Reset**, which puts the default back.
- GoNuts's on-screen panel, alerts and nearby-player list keep clear of Donut's info panel and module list. GoNuts's own keys: **B** opens its full screen, **End** is its emergency stop, **Page Up** resumes, **Home** lists the inventory and **Left Alt** (held) peeks.
- Auto RTP stops, saying "GoNuts is trading; stop its session first", while a GoNuts session works, so a teleport never lands in the middle of a trade. Trading steps wait while the Donut menu is open; close it to let GoNuts work.

DonutSMP's rules ban scripts and macros, and the ban reaches every account linked to a player. Running the bot is a risk the account owner chooses to take.

## Build

Install JDK 25 and put GoNuts next to this repository (`../Gonuts`) on its `local-hardening` branch, which adds the `GoNutsApi` this mod is built on; that branch is local to this machine, not published with upstream GoNuts. Then run:

```powershell
.\gradlew.bat build
```

The finished mod, with GoNuts inside, is written to `build/libs/donut-client-2.0.0.jar`.

### Sale builds and keys

Copies sold through Lemon Squeezy need a key. Build them with the product's id:

```powershell
.\gradlew.bat build -PlicenseProductId=<product id>
```

That jar is written to `build/sale/` instead, so it never replaces the key-free jar in `build/libs`. Without `-PlicenseProductId`, the mod needs no key at all, and that's how the gametests and your own installs run.

A sale copy stays locked until a key for that product passes. While it's locked:
- every module is off;
- module keys do nothing;
- the menu shows only its **Key** page, which also opens by itself on the title screen.

Paste the key from the purchase email into the Key page and press Enter. Donut checks the key with Lemon Squeezy's License API, then activates it on this PC. A key from any other product is refused. The key and this PC's activation are saved in `config/donut-license.json`.

Donut checks the key again at every start and every six hours, and locks the moment the key runs out. If the key server can't be reached, a key that passed within the last three days keeps working. While locked, settings aren't saved, so a player's own module choices come back as soon as a key passes. `-PlicenseBuyUrl` changes where the Key page's **Buy a key** button leads (by default, the landing page at elite-veno.github.io/donut-client-site).

## License

MIT. The bundled Inter font uses the SIL Open Font License. GoNuts is MIT licensed (Copyright (c) 2026 cody-raves); its notice ships in the jar as `LICENSE_doughbay`, and the libraries it carries (Jackson, sqlite-jdbc) keep their Apache 2.0 notices inside their own jars.
