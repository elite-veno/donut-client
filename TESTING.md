# Validation

Automated Auto RTP tests drive its state machine through hits, quiet areas, missed teleports, server cooldowns, damage and amethyst-free areas, and check cooldown parsing, teleport countdowns that must not count as cooldowns, and menu item matching. Automated amethyst-growth tests check the exact worldgen baseline, reject lucky small chunks, and simulate worldgen plus random ticks: fresh geodes almost never reach the default threshold, an hour nearby averages about half growth, and three hours nearly all. Automated configuration tests cover defaults, JSON persistence and replacement, invalid-file recovery/backups, missing and future fields, unknown modes, numeric boundaries, NaN/infinity, and color sanitization.

Run `./gradlew build` with JDK 21. The installable, remapped artifact is `build/libs/donut-client-1.3.0.jar`.

Run `./gradlew runClientGameTest` for the isolated Minecraft smoke test. It creates a disposable world with all twelve block target types, checks scanner counts and filtering, clicks the GUI controls, and captures screenshots of both depth policies for each render mode. It also seals ancient debris in netherrack in a neighboring chunk after that chunk was first indexed, and checks that mined debris disappears within 15 ticks instead of waiting for its chunk's periodic rescan. For ActivityFinder it builds a worldgen-like geode and a fully grown one in neighboring chunks and checks that only the grown chunk is outlined. The vision checks then mark a spawner with a used timer as activated and leave an untouched one alone; build a chunk full of buried debris out of view so it arrives as chunk data, like anti-xray fakes, and check that none is drawn until a neighbor is mined or the server confirms a block; find a tunnel, a hole and a staircase dug into stone but not a cave room; hide stone with X-ray; outline mobs with tracers and labels; and light midnight with Fullbright. Test code and fixtures are excluded from the installable jar. This requires a working graphics device.

The Freecam runtime test compares both the actual client player and integrated server player before and after camera movement, checks speed/boost/vertical movement and mouse look, verifies action queues are cleared, and checks that disabling restores normal input without undoing a server teleport. It also checks disconnect cleanup and clicks the Freecam GUI controls. Configuration tests cover persistence of speed/favorites and deliberately non-persistent activation.

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
13. Open and scroll every module's settings, search by typing, drag and collapse panels, edit the accent color and the HUD modules, close/reopen, and restart the game to check persistence. Reset layout should put the panels back. Test another window size and GUI scale.
14. Assign each module's key from the Bind row in its settings and confirm the same binding appears in Minecraft Controls. Test keyboard and mouse bindings, Escape to cancel, Delete/Backspace to clear, conflicts, and persistence after restarting. Capturing a binding must not toggle its module; shortcuts must stay inactive in chat and menus.
15. Enable all modules and check the array list at the top right: names only, longest first, each on a dark strip with an accent edge and no counters. Disable modules and the ArrayList module in the Client panel, and check F1 hides the HUD.
16. Inspect panel corners, header icons, toggle movement, open animations and scrolling at GUI scales 1 and 2 and a smaller window. Test with the intended shader/rendering mods separately if using them.

Only checks actually executed are reported in the delivery message; this checklist is not a claim that every renderer/mod combination has been tested.
