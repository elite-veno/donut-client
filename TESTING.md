# Validation

Automated Auto RTP tests drive its state machine through hits, quiet areas, missed teleports, server cooldowns, damage and amethyst-free areas, and check cooldown parsing, teleport countdowns that must not count as cooldowns, and menu item matching. Automated amethyst-growth tests check the exact worldgen baseline, reject lucky small chunks, and simulate worldgen plus random ticks: fresh geodes almost never reach the default threshold, an hour nearby averages about half growth, and three hours nearly all. Automated configuration tests cover defaults, JSON persistence and replacement, invalid-file recovery/backups, missing and future fields, unknown modes, numeric boundaries, NaN/infinity, and color sanitization.

Run `./gradlew build` with JDK 25 (Minecraft 26.2). The installable artifact is `build/libs/donut-client-builder-2.2.1.jar`. This Builder edition (branch `builder`) carries no trading bot and needs no GoNuts clone; the regular edition on `gonuts-update` carries GoNuts (`../Gonuts`, branch local-hardening) as a nested mod.

Run `./gradlew runClientGameTest` for the isolated Minecraft smoke test. It creates a disposable world with all twelve block target types, checks scanner counts and filtering, clicks the GUI controls, and captures screenshots of both depth policies for each render mode. It also seals ancient debris in netherrack in a neighboring chunk after that chunk was first indexed, and checks that mined debris disappears within 15 ticks instead of waiting for its chunk's periodic rescan. For ActivityFinder it builds a worldgen-like geode and a fully grown one in neighboring chunks and checks that only the grown chunk is outlined. The vision checks then mark a spawner with a used timer as activated and leave an untouched one alone; build a chunk full of buried debris out of view so it arrives as chunk data, like anti-xray fakes, and check that none is drawn until a neighbor is mined or the server confirms a block; find a tunnel, a hole and a staircase dug into stone but not a cave room; hide stone with X-ray; outline mobs with tracers and labels; and light midnight with Fullbright. Test code and fixtures are excluded from the installable jar. This requires a working graphics device.

The schematic builder runtime test builds a small redstone schematic in survival as a player would: normal reach, no flying, walking and climbing to each block, real looks at each face clicked, partly from a shulker box. It checks every block on the server, the order within each part of a layer, the delay between blocks placed from one spot, that the ground of a pit dug for a hanging lantern is whole again, and that no helper block is left. `SchematicFileGameTest` builds any .litematic named by `DONUT_SCHEMATIC_TEST` the same way, every block packed in shulker boxes, and compares the result on the server (`DONUT_SCHEMATIC_MINUTES` raises its 50-minute limit). `gradlew runWatch -PwatchFile=<file>` builds a schematic in a normal game window to watch, with the real 0.65 to 2.3 s delays (`-PwatchDelay=min,max` for others), and checks the result in chat.

The Freecam runtime test compares both the actual client player and integrated server player before and after camera movement, checks speed/boost/vertical movement and mouse look, verifies action queues are cleared, and checks that disabling restores normal input without undoing a server teleport. It also checks disconnect cleanup and clicks the Freecam GUI controls. Configuration tests cover persistence of speed/favorites and deliberately non-persistent activation.

## Verified on 2026-10-17 (ChunkMiner, with ChunkFinder and the walking SchematicBuilder)

- `ExcavatorTest` digs whole rooms in a block grid with a simulated player that carries out every action and checks it is one a player could do: the block in reach and in view, an area swing's look agreeing with its face, every step over solid ground, nothing broken under the player, under the room or under a staircase step, nothing next to water or lava. It runs 40 random room sizes and starts with a normal pickaxe, an area pickaxe or both; 60 rough worlds with caves and gravel pockets (none gave up); hilly ground; ceilings of gravel; lava and water beside the room; chests; and floors that give way into caves under the staircase, under a band and under the room. `MinerPartsTest` covers the room's boxes and bands, staircase shapes (each step one block over, level steps before climbs for the area pickaxe, no step digging another's floor), 3x3 squares and look agreement, and settings.
- `build`: 137 unit tests pass on the merged branch.
- `ChunkMinerGameTest` (run on chunk-miner before the merges): with a normal pickaxe the miner dug a 28-step staircase from the ground, climbed inside and dug a 6 x 7 x 6 room with a 6 x 3 x 3 storage room (306 blocks) in 6,982 ticks; with only a stand-in Amethyst Pickaxe (3x3 flat against the face hit, done by the test server) it dug the same room in 2,581 ticks and kept taking the pickaxe for a 3x3 one. Afterwards every block of both rooms was gone and the floors whole; the camera never turned more than 31.3 degrees in a tick (limit 30 x 1.15 plus wobble). The 3x3 run's staircase check was then fixed to check the staircase planned last (the area pickaxe digs into the ground first and plans again) and has not been re-run.
- `ChunkMinerFinderGameTest` (on ChunkFinder 1d9db87): on DonutSMP's seed the miner switched ChunkFinder on, took its good chunk at 216 -72, placed the room under its middle at y 30 and dug six steps toward it in 18 s. Not re-run on the merged branch: the machine ran short of memory with several sessions' game tests going.

## Work in progress on 2026-10-17 (Builder edition: real placement)

- The builder never flies or teleports and uses the normal reach. It walks, jumps, climbs on helper blocks and takes them away again, and digs a pit to stand in when a block can only be placed looking up at it, then fills the pit in. Every click is a visible camera turn onto a face in view; it really crouches, and items reach the hotbar through the opened inventory. Water, lava and kelp go in after every other block. Blocks that would wall in a neighbour, or the player, wait. Tested with patches 1-12 from the speed review (session self-gonuts-77).
- `build`: all unit tests pass, including `BuildPlanTest`. `SchematicBuilderGameTest` passes: 30 of 30 blocks, the lantern hung from a pit refilled with smooth stone, no dirt left.
- IKEA kelp v1 (1,708 blocks, 14 layers): not yet built end to end. Runs got through layer 1 (30 observers facing up, each placed from a pit) and the hopper rows of layer 2; the last stalls found (hopper bowls, fence-gate outlines, ice, shulker boxes sliding off ice) are fixed but not yet re-run to the end.
- Mossy crafter V1.3 (11,039 blocks): every block kind is handled (survey); a fast run is under way. Item frames, sign text and container contents are not part of building.

## Verified on 2026-10-17 (2.2.1 Builder edition: Nether ore features read from the registries)

- DebrisFinder's seed replays no longer carry the Nether's feature numbers. `NetherFeatures` builds the vanilla registries, sorts the Nether's features as its chunk generator does and reads each ore's index, placements per chunk, height range, size, block and the biomes that lack it (debris 21 and 22, magma 11, gravel 17, blackstone 18 in 26.2). A feature placed in a way the replays can't follow is refused and logged, leaving the seed estimates off.
- Before the switch, a throwaway test compared the new replay with the fixed numbers: the same 72,734 debris positions over 40 seeds. `NetherFeaturesTest` pins 26.2's values.
- `build`: 96 unit tests pass. `runClientGameTest`: all seven suites pass. In `DebrisSeedGameTest`, 245 and 246 of 245 and 246 hidden debris got an estimate (server, singleplayer), every estimate was debris, all exposed debris lay on seed positions, and magma, gravel and blackstone matched best at the indexes read.

## Verified on 2026-10-17 (2.2.0 Builder edition: no GoNuts, Schematic Builder)

- Branch `builder`: GoNuts and its pages are gone (3da8a0b); the info panel and module list now claim their screen areas through `HudSpace` instead of `GoNutsApi`, and the keybind test checks those claims at GUI scales 1, 2 and 6.
- `build`: 96 unit tests pass, among them the .litematic reader against Litematica's bit packing (indexes that run on into the next long, 3- and 6-bit palettes, negative region sizes, several regions, unknown blocks, cut-short files) and the block rules (what placing decides, follow-up clicks, counts, layer and redstone order, rotation and mirror).
- `runClientGameTest`: all seven suites pass, with no mixin errors. `SchematicBuilderGameTest` drops a 4-layer redstone schematic on the menu and builds it with the production builder in survival, with a lantern, candles, stairs and a comparator only in a shulker box. All 30 blocks match on the server (repeater delay 3, comparator subtract, observer north, piston up, wall torch, top slab, hopper east, lever on, open door with left hinge, bed, three candles, hanging lantern, and the wire's three diagonal steps joined up). Every block went in before every block of a higher layer except the lantern, which followed its ceiling at once; redstone followed the bottom layer's plain blocks and the piston and observer came last; no two blocks went in closer than the 0.25 s shortest delay. The box was placed, emptied of what the build needed (its diamonds stayed), broken and picked up.
- Found while testing: the server works out a piston's, observer's or dispenser's facing from the player's head, which takes a new look only on its next tick, so a look sent with the click placed the observer backwards. Looks now go out two ticks before their click.
- With the PvP profile's mods (appleskin 3.0.10, betterhurtcam 1.14.0, ShieldFixes 2.1.0, ShieldStatus 4.2.1, ukulib 2.1.1, ukus-armor-hud 0.12.0, WalksyLib 2.0.3, all for 26.2) on the game test classpath, all seven suites pass on 2848c46 with no mixin conflicts; the only warnings are appleskin's optional JEI mixin and WalksyLib recommending Mod Menu. These make up the Modrinth profile "PvP + Donut Builder" (C:\projects\Self-Gonuts\pvp-donut-builder-2.2.1.mrpack), which was imported but not yet launched.
- Not tested: Litematica itself side by side, a real server's anti-cheat, very large schematics, and the move-closer walk at survival reach (the test raises reach to 8.5 so one spot covers the build; it does check that nothing is placed out of reach first).

## Verified on 2026-10-16 (2.1.0: newest GoNuts inside)

- GoNuts is upstream db4ced1 (proxies, hive setup, Region Map, automatic reports, Staff Watch region escape, Flips only) with the local-hardening fixes and `GoNutsApi` rebuilt on top. The old local-hardening source was gone, so its changes were recovered from the 2.0.0 jar: upstream cc9c340 built here matched it byte for byte outside the fork's classes, and the decompiled differences were ported by hand.
- Donut's classes built from e9bf4f5 are byte-identical to 2.0.0's; only the nested GoNuts and the version changed.
- GoNuts `build`: 971 tests pass. Four upstream tests were rewritten for the fork's behaviour: money settings are guarded (the web panel can't change them) and Enable keeps a typed one-trade limit instead of raising it.
- Donut `build` (78 tests) and `runClientGameTest` passed, with no mixin errors. Screenshots show every GoNuts tab, including Region Map, Proxies and Hive setup, in the Donut theme, and GoNuts's HUD clear of the info panel and module list at 2560x1440.

## Verified on 2026-10-14 (final review round: menu, GoNuts pages, HUD, mixins, build)

Two review agents per module looked for bugs and improvements, and the fixes were checked by `runClientGameTest`. The run at 00:34 passed DonutClientGameTest, FreecamGameTest, ModuleKeybindGameTest and AutoRtpGameTest with these changes; DebrisSeedGameTest then failed on its own fixture (fixed separately), so ActivityWorldgenGameTest did not run. New checks:
- GoNuts pages:
  - a changed setting's Reset link restores its default;
  - a guarded typed value is unchanged after one Enter and set after the second;
  - `1,5m` reads as 1,500,000;
  - Esc leaves a field as it was;
  - clicking a field selects its text, so typing replaces it;
  - a paste after End is added to a 300-character list rather than replacing it.
- Module settings: Reset settings needs Confirm and leaves the module on.
- HUD: GoNuts's `hudReserved()` holds the info panel's and the module list's boxes while they show, and nothing once F1 hides the HUD. The overlap check against `hudDrawn()` only bites once GoNuts draws its panel, which needs a live feed or a preview.

Decisions to review:
- Start closes the menu, since GoNuts's trading steps wait while it is open.
- Approve takes a second click.
- Resume is not offered for a recovered session that is unsure about a trade (the full GoNuts screen resolves it).
- Webhook URLs saved from Donut also turn the hourly reports on, as GoNuts's own Save does.

## Verified on 2026-10-13 (Minecraft 26.2 port, GoNuts inside)

Done on branch `port/26.2` while the user was away. Decisions to review:
- The 1.21.11 tree was checkpointed as `ef8881c` on `feature/debris-finder`, with the user's OK. The port is `b3fb76e`, GoNuts bundling is `dacd1fb`, and the GoNuts pages are `985d2fa`. All are local, nothing is pushed, and each can be reverted on its own.
- Build: Loom 1.17.21 (`net.fabricmc.fabric-loom`, unobfuscated, no mappings), Gradle 9.7.1, Java 25, loader 0.19.5. fabric-api is 0.158.0+26.2, the version GoNuts builds against and the one already cached (the profile runs 0.161.0, which also satisfies `>=0.158.0`). Mod version is 2.0.0 because the Minecraft version changed. Mixins use compatibility level JAVA_25.
- Mixins on the 26.2 API:
  - Freecam places the camera at the end of `Camera.alignWithEntity`, before the culling frustum is built. The old LevelRenderer spectator-culling hook became `smartCull = false` in `Camera.extractRenderState`, used while the free camera is inside an opaque block.
  - Picking moved to `Minecraft.pick`, and `pickBlock` became `pickBlockOrEntity`.
  - Screen effects are hooked at `ScreenEffectRenderer.submit`.
  - Fullbright reads gamma in `LightmapRenderStateExtractor.extract`, now pinned to the first option read so the Darkness effect scale is no longer raised too.
  - Distance labels still capture the projection the world is drawn with, bobbing included, from `ProjectionMatrixBuffer.getBuffer` in `GameRenderer.renderLevel`.
- GoNuts pages: a GoNuts sidebar group (Overview and Settings) built only on `dev.doughbay.fabric.GoNutsApi`. The pending payroll payment shows as an amber note with a Confirm button, guarded settings are marked in amber, and resets and presets stay on the full GoNuts screen.
- `build` and `runClientGameTest` passed after each step. Screenshots of the GoNuts Overview, the settings list and the Session group were reviewed. The build was installed into the "Donut Client 26.2" Modrinth profile with the game closed.

## Verified on 2026-10-13 (sidebar menu)

- The menu follows a reference design the user supplied: a sidebar of categories and pages (Modules: Finders, Render, Utility, HUD; Trading: Sniper, Dashboard, Markets; General: Keybinds, Theme), a header with the page title, module counts and a search box, module cards with switches, and a settings card per module whose rows carry a label, a one-line description and the control on the right. Colors moved to a dark teal with a mint accent; older configs are migrated once (`gui.theme`), and snowfall is now off by default.
- UI text comes in three sizes (`ui_small_N`, `ui_N`, `ui_title_N`), each rasterized per pixel ratio. The window uses a whole number of pixels per unit, at most window height / 384.
- From a review agent's findings: a left or right click during key capture now cancels instead of binding the attack/use button; hover follows the exact cursor position; text is centred in its boxes; scrolling snaps to whole screen pixels; notes have 12-unit line spacing; the field and its row share one click target; Tab scrolls the focused control into view; and the color editor closes on an outside click and has a close button.
- Game-test ids: sidebar pages are `nav.<page>`; on a module's settings page its switch is `module.<name>` and Back is `expand.<name>`. `DonutScreen.scrollTo` opens the page that holds a control, so `GuiClicks` works unchanged. The finder suite also screenshots the Keybinds and Theme pages.
- The mode selector's highlight now animates by its offset inside the control, after a resize screenshot caught it sliding in from its old position.

## Verified on 2026-10-13 (winter menu)

- The category panels became one "Donut Client" menu with two columns of module groups, a pine-green palette, red switches, gold group labels and optional snowfall (Client > Interface > Snowfall). Filters are chips, the render mode is a segmented control, and opacity, line width, colors and the sniper's deal rules sit behind each module's "More options". Dashboard and Markets open as modal sheets.
- Test ids changed in three places: the render mode is clicked per option (`StorageFinder.mode.Outline`), `GuiClicks.showMore` unfolds "More options" before the opacity slider and the sniper's Commands, and the sniper test closes the Dashboard sheet before opening Markets.
- `build` and `runClientGameTest` passed (all suites). Reviewed screenshots of the menu, open Debris, Activity, Storage and AHSniper settings, the Dashboard sheet and key capture at GUI scales 1 and 2.
- Follow-up: modules are filled boxes on drop shadows, with filled chips, a raised selected segment and inset fields. Minecraft samples font glyphs without filtering, so the UI font is now shipped at oversample 1 to 8 (`assets/donut/font/ui_N.json`) and `UiPaint` uses the one matching the screen pixels per unit. The menu uses a whole number of pixels per unit, at most a window height / 400, so a 1440p screen at auto GUI scale gets 3 instead of a stretched 4.8. Bold and shadow are drawn one screen pixel apart instead of Minecraft's whole-unit offset. `build` and `runClientGameTest` passed again; screenshots at scale 2 show sharp text.

## Verified on 2026-10-13 (click GUI overhaul)

- The click GUI is now draggable category panels (Finders, Render, Utility, Trading, Client) instead of the sidebar window. The game tests click controls by id through `DonutScreen.controlBounds` and `scrollTo`, so they no longer depend on fixed coordinates.
- `build`: all 70 unit tests passed, including 3 new layout tests (panel places survive a reload, older configs get an empty layout, null and out-of-range entries are repaired).
- `runClientGameTest`: all five runtime suites passed. The finder suite toggled StorageFinder and SpawnerFinder from their rows, enabled DebrisFinder and ActivityFinder with their settings open, cycled the render mode to Outline and set outline opacity to 50%. The Freecam suite turned Freecam on from its row and set speed 1.6 and boost 3.7 at 30% of each slider. The keybind suite bound all ten modules from their Bind rows, cancelled with Escape, cleared with Delete and Backspace, captured mouse button 4, rebound at GUI scale 2 and toggled the array list from the Client panel.
- The first run caught a window's close button that tests could not find, because headers never scroll, and the second showed two sniper windows opening on top of each other and clicks on an empty part of a window reaching the panel underneath. Windows now open staggered and only the topmost panel under the mouse takes clicks.
- Reviewed screenshots at GUI scales 1 and 2: panels, open settings, the live scans, key capture, the Dashboard and Markets windows, the array list and the info panel. Translucent panel bodies let window text show through each other, so they are opaque now. Title-screen access, real servers and shader packs were not checked.

## Verified on 2026-10-13 (1.3.0)

- `build` with JDK 25 targeting Java 21: all 67 unit tests passed, including 8 new TunnelDetector tests (1x2 tunnels along X and Z, short runs ignored, 2-wide corridors and open caves rejected, a 17-deep hole, a 9-step staircase found once, runs stopping at unknown blocks) and 4 new settings tests (defaults, an older config without the new modules, persistence, repair of bad values).
- `runClientGameTest`: all five runtime suites passed (finders, Freecam, module keybinds, AH Sniper, Auto RTP) with the new 11-row sidebar.
- StorageFinder found barrels, hoppers, dispensers and furnaces next to the original eight types; the dropper filter still removed only droppers.
- SpawnerFinder marked a spawner placed with `Delay:300` as activated and left an untouched spawner 66 blocks away unmarked. Its first run caught the fixture spawner 12 blocks from the player counting as activated: our own presence had run its timer before the finder looked. Spawner timers are now read as each chunk arrives.
- DebrisFinder: a chunk with 1,372 buried debris delivered as chunk data was detected as faked and none was drawn; mining one block showed exactly its 6 newly exposed neighbors, and a server block update confirmed one more buried block (7 shown, reveal message with coordinates in the action bar). Turning Filter anti-xray fakes off drew all of them. An earlier run that placed the debris with `/fill` showed all of it, because block updates from the server count as confirmation, which is why the test now delivers fakes as chunk data.
- TunnelFinder found exactly a 14-long tunnel, a 19-deep hole and a 6-step staircase in a stone block, and not a 9x5x3 room beside them. The first attempt found nothing because the stone was placed before the server had reloaded the chunks after a teleport; the test now waits for them.
- X-ray hid the stone block entirely (and the geode's calcite) while the tunnel, hole and staircase highlights, labels and tracers stayed; it brightened the world and kept sideways deepslate visible.
- EntityFinder outlined a husk and a pig with tracers and name labels. Overlapping labels now move up or are left out, nearest first.
- Fullbright lit the world at midnight and removed fog; turning it off restored the dark scene and normal brightness.
- Reviewed screenshots of the info panel (counts, arrows, distances, "2 activated", "anti-xray fakes hidden"), labels, the module overview, the debris settings page and the array list with all modules. External servers, real anti-xray plugins, Sodium and shader packs were not tested.

## Verified on 2026-10-13 (1.2.0)

- `build`: passed with JDK 25 targeting Java 21; all 15 configuration tests passed, including debris persistence and purple defaults for older configs.
- `runClientGameTest`: all three runtime suites passed. The finder suite enabled DebrisFinder through its settings page, found open debris and debris sealed in netherrack in a neighboring chunk that was placed after indexing, and dropped mined debris within 15 ticks. With the mined-debris recheck removed, that step timed out, so the test covers it.
- The keybind suite assigned DebrisFinder's key from its settings header, checked it in `options.txt` after a reload, toggled the module on and off with it, and showed it in the array list.
- ActivityFinder (1.2.0): all 22 unit tests passed, including the worldgen tail, lucky small chunks, and the worldgen plus random-tick simulation. The finder suite enabled it through its settings page, scored a worldgen-like geode below 5% and a fully grown one at 100% in neighboring chunks, and outlined only the grown chunk at the default threshold. With grass laid over the grown geode, it read the chunk surface at y 63 and drew the square on top with a guide down to the geode; with Surface marker off, the square returned to the geode. With the detection range cut to 48 blocks, All loaded chunks still found a grown geode about 76 blocks away, and turning it off dropped that chunk. The keybind suite bound, persisted, reloaded and toggled its key. The first simulation run caught small chunks reaching 50% from worldgen luck alone, which the exact baseline test now rejects.
- Auto RTP: all 35 unit tests passed. A new runtime suite used a relative teleport as the command: the first jump landed by a geode at 52% growth, which it saw and passed over because the stop threshold is 55%; the second landed by a grown geode 800 blocks out and stopped with FOUND after two attempts; a one-attempt run ended EXHAUSTED; a client-side chest menu had its Overworld item clicked, and a menu without it ended MENU_MISSING with the item names in chat. The keybind suite bound, toggled and stopped it. The sidebar now holds six entries at 32-pixel spacing.
- Reviewed the activity settings and detection section, the orange chunk square, and the five-name array list.
- Reviewed the debris settings page, the scrolling four-card overview, and the debris highlights with and without depth testing. External servers, anti-xray plugins, shader packs and alternate renderers were not tested.

## Verified on 2026-09-27

- `build`: passed; all 15 configuration tests passed.
- `runClientGameTest`: all three runtime suites passed (finders, Freecam, and module keybinds).
- Assigned keys to all three modules through the production click GUI; verified keyboard/mouse capture, Escape cancellation (including key repeat), Delete/Backspace clearing, `options.txt` persistence, and reloading through Minecraft Options.
- Verified real shortcuts toggle each module, native key repeats do not retoggle a held binding, menu input does not replay after closing, and holding Sprint across menu closure does not block shortcuts.
- Reviewed captured GUI screenshots at GUI scales 1 and 2, including the scaled binding-capture control. Rounded edges and icons use antialiased geometry.
- Reviewed the names-only array list at both scales and confirmed F1 hides it. All-disabled state was also captured.
- Updated representative screenshots in `docs/screenshots/`. Third-party renderers and shader packs were not tested.

## Verified on 2026-09-23

- `build`: passed; all 15 configuration tests passed, including Freecam settings persistence, disabled activation on load, and numeric sanitization.
- `runClientGameTest`: both the finder and Freecam runtime suites passed on Windows with Minecraft 1.21.11, Fabric Loader 0.18.4 and Fabric API 0.141.4+1.21.11.
- GUI module switches, mode selection, opacity input, save callbacks, and Freecam speed/boost sliders passed assertions.
- All seven target types and dropper filtering passed loaded-world assertions.
- Captured all six combinations of render mode and depth policy. Visual review confirmed outlines, fill, and wall occlusion. Representative screenshots are in `docs/screenshots/`.
- Freecam forward, sprint-boosted, and vertical movement passed while both the actual client player and integrated-server player retained their positions and rotations. Real mouse input and direct camera rotation also preserved both player poses.
- Freecam interaction queues were cleared; disabling restored the original player input and camera. A server teleport received during Freecam remained authoritative after disabling, and disconnect cleanup reset activation.
- External multiplayer servers, third-party shader packs, and alternate renderers have not been tested.

## Gameplay checks

Use a separate creative singleplayer world with commands enabled:

1. Open the Donut title-screen button and check the panels, module names and typography. Enter a world and open the GUI with Right Shift.
2. Place a chest, double chest, trapped chest, ender chest, regular and colored shulker boxes, dropper, monster spawner, trial spawner and ancient debris. Dispensers should not match the dropper filter.
3. Enable each finder independently; switch between Outline, Filled and Both. Verify the outlines track the block shapes while walking and turning the camera.
4. Place an opaque wall between the player and targets. Toggle Through walls to compare rendering with and without depth testing.
5. Set each opacity to zero and full, and compare line widths. Change base and family colors using presets, RGB sliders and hex entry.
6. Turn each filter off and confirm only the corresponding blocks disappear. Break a highlighted block; its highlight should disappear immediately.
7. In the Nether, enable DebrisFinder and check buried debris is highlighted through netherrack. Mine one and place a new one; both changes should show at once. On a server with anti-xray, check the live scan on its settings page: hidden fakes or "hides it" should be reported, and mining next to real debris should light it up with a sound.
8. Enable X-ray underground and in the Nether: stone, deepslate, dirt and netherrack disappear while ores, chests and caves' contents stay. Toggle each group, Only exposed ores and extra block ids. Enable Fullbright in a dark cave and at night, with and without Remove fog.
9. Dig a straight 1x2 tunnel, a 1x1 hole and a staircase of 10 or more blocks underground; TunnelFinder should mark each. Natural caves should rarely be marked.
10. Enable EntityFinder near another player and some mobs; check boxes, tracers and name labels. Check the info panel's counts, distances and arrows while turning around.
11. Enable ActivityFinder near an untouched geode and near one with clusters on every face. Only the grown geode's chunk should get a square, resting on the highest block with corner lines down to the geode; lower Minimum growth to see weaker chunks, and toggle Surface marker to move the square to the geode. With All loaded chunks on, squares should appear out to your render distance without fading; turning it off brings back the range slider and distance fade.
12. Reduce range and walk across its boundary. Test distance fading, unload chunks, and change dimension/world; previous-world targets should disappear.
13. Open and scroll every page and module's settings, search by typing, edit the accent color and the HUD modules, close and reopen (each page keeps its scroll position), and restart the game to check persistence. Use Reset settings on a module (it needs Confirm). In a text field, check that a click selects the text, that a paste replaces it and that Esc leaves it unchanged. Test another window size and GUI scale.
14. Assign each module's key from the Bind row in its settings and confirm the same binding appears in Minecraft Controls. Test keyboard and mouse bindings, Escape to cancel, Delete/Backspace to clear, conflicts, and persistence after restarting. Capturing a binding must not toggle its module; shortcuts must stay inactive in chat and menus.
15. Enable all modules and check the array list at the top right: names only, longest first, each on a dark strip with an accent edge and no counters. With a potion effect active the list starts below the effect icons, and F3 hides it. Turn modules and the ArrayList module (HUD page) off, and check F1 hides the HUD. Distance labels should stay clear of both Donut lists.
16. Inspect card corners, sidebar icons, switch movement, page animations, the fade at scrolled edges and scrolling at GUI scales 1, 2 and Auto, and in a smaller window. Test with the intended shader/rendering mods separately if using them.
17. On the Build page, drop a .litematic on the menu, set an origin, and build something with redstone in survival, walking along each layer. Check that layers go in from the bottom up, that blocks hanging from the layer above wait for it, that facings, repeater delays and comparator modes match the schematic, and that a block kept only in a shulker box is fetched and the box comes back. Change both delays and watch the pace change; open a menu and check that it pauses.

This checklist is not a claim that every renderer and mod combination has been tested; the dated sections above list what was.
