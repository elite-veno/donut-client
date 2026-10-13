# Validation

Automated Auto RTP tests drive its state machine through hits, quiet areas, missed teleports, server cooldowns, damage and amethyst-free areas, and check cooldown parsing, teleport countdowns that must not count as cooldowns, and menu item matching. Automated amethyst-growth tests check the exact worldgen baseline, reject lucky small chunks, and simulate worldgen plus random ticks: fresh geodes almost never reach the default threshold, an hour nearby averages about half growth, and three hours nearly all. Automated configuration tests cover defaults, JSON persistence and replacement, invalid-file recovery/backups, missing and future fields, unknown modes, numeric boundaries, NaN/infinity, and color sanitization.

Run `./gradlew build` with JDK 21. The installable, remapped artifact is `build/libs/donut-client-1.2.0.jar`.

Run `./gradlew runClientGameTest` for the isolated Minecraft smoke test. It creates a disposable world with all eight target types, checks scanner counts and filtering, clicks the GUI controls, and captures screenshots of both depth policies for each render mode. It also seals ancient debris in netherrack in a neighboring chunk after that chunk was first indexed, and checks that mined debris disappears within 15 ticks instead of waiting for its chunk's periodic rescan. For ActivityFinder it builds a worldgen-like geode and a fully grown one in neighboring chunks and checks that only the grown chunk is outlined. Test code and fixtures are excluded from the installable jar. This requires a working graphics device.

The Freecam runtime test compares both the actual client player and integrated server player before and after camera movement, checks speed/boost/vertical movement and mouse look, verifies action queues are cleared, and checks that disabling restores normal input without undoing a server teleport. It also checks disconnect cleanup and clicks the Freecam GUI controls. Configuration tests cover persistence of speed/favorites and deliberately non-persistent activation.

## Verified on 2026-10-13

- `build`: passed with JDK 25 targeting Java 21; all 15 configuration tests passed, including debris persistence and purple defaults for older configs.
- `runClientGameTest`: all three runtime suites passed. The finder suite enabled DebrisFinder through its settings page, found open debris and debris sealed in netherrack in a neighboring chunk that was placed after indexing, and dropped mined debris within 15 ticks. With the mined-debris recheck removed, that step timed out, so the test covers it.
- The keybind suite assigned DebrisFinder's key from its settings header, checked it in `options.txt` after a reload, toggled the module on and off with it, and showed it in the array list.
- ActivityFinder (1.2.0): all 22 unit tests passed, including the worldgen tail, lucky small chunks, and the worldgen plus random-tick simulation. The finder suite enabled it through its settings page, scored a worldgen-like geode below 5% and a fully grown one at 100% in neighboring chunks, and outlined only the grown chunk at the default threshold. With grass laid over the grown geode, it read the chunk surface at y 63 and drew the square on top with a guide down to the geode; with Surface marker off, the square returned to the geode. With the detection range cut to 48 blocks, All loaded chunks still found a grown geode about 76 blocks away, and turning it off dropped that chunk. The keybind suite bound, persisted, reloaded and toggled its key. The first simulation run caught small chunks reaching 50% from worldgen luck alone, which the exact baseline test now rejects.
- Auto RTP: all 35 unit tests passed. A new runtime suite used a relative teleport as the command: the first jump found nothing, the second landed by a grown geode 800 blocks out and stopped with FOUND after two attempts; a one-attempt run ended EXHAUSTED; a client-side chest menu had its Overworld item clicked, and a menu without it ended MENU_MISSING with the item names in chat. The keybind suite bound, toggled and stopped it. The sidebar now holds six entries at 32-pixel spacing.
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

1. Open the Donut title-screen button and check the module list, tabs and typography. Enter a world and open the GUI with Right Shift.
2. Place a chest, double chest, trapped chest, ender chest, regular and colored shulker boxes, dropper, monster spawner, trial spawner and ancient debris. Dispensers should not match the dropper filter.
3. Enable each finder independently; switch between Outline, Filled and Both. Verify the outlines track the block shapes while walking and turning the camera.
4. Place an opaque wall between the player and targets. Toggle Through walls to compare rendering with and without depth testing.
5. Set each opacity to zero and full, and compare line widths. Change base and family colors using presets, RGB sliders and hex entry.
6. Turn each filter off and confirm only the corresponding blocks disappear. Break a highlighted block; its highlight should disappear immediately.
7. In the Nether, enable DebrisFinder and check buried debris is highlighted through netherrack. Mine one and place a new one; the mined highlight should vanish at once and the new one should appear within about 2.5 seconds.
8. Enable ActivityFinder near an untouched geode and near one with clusters on every face. Only the grown geode's chunk should get a square, resting on the highest block with corner lines down to the geode; lower Minimum growth to see weaker chunks, and toggle Surface marker to move the square to the geode. With All loaded chunks on, squares should appear out to your render distance without fading; turning it off brings back the range slider and distance fade.
9. Reduce range and walk across its boundary. Test distance fading, unload chunks, and change dimension/world; previous-world targets should disappear.
10. Scroll all settings, use favorites/search, edit the accent and HUD setting, close/reopen, and restart the game to check persistence. Test another window size and GUI scale.
11. Assign each module's key in the click GUI (DebrisFinder and ActivityFinder from their settings pages) and confirm the same binding appears in Minecraft Controls. Test keyboard and mouse bindings, Escape to cancel, Delete/Backspace to clear, conflicts, and persistence after restarting. Capturing a binding must not toggle its module; shortcuts must stay inactive in chat and menus.
12. Enable all five modules and check the array list at the top right: names only, longest first, with soft shadows and no solid box or counters. Disable modules and the Array list appearance option, and check F1 hides the HUD.
13. Inspect rounded corners, favorite/search/module icons, toggle movement, and scrolling at GUI scales 1 and 2 and a smaller window. Test with the intended shader/rendering mods separately if using them.

Only checks actually executed are reported in the delivery message; this checklist is not a claim that every renderer/mod combination has been tested.
