# Donut Client

A client-side Fabric mod for Minecraft Java 26.2 for seeing what is hidden: storage, spawner and ancient debris highlights, X-ray, Fullbright, dug tunnel and hole detection, player and mob ESP with tracers, amethyst-based activity detection with Auto RTP, Freecam, module keybinds, distance labels and an info HUD, a Schematic Builder that builds Litematica schematics block by block, and a Chunk Miner that digs out the room for one underground.

This is the **Builder edition**: it carries no trading bot. The GoNuts auction bot that the regular Donut Client bundles is left out entirely, and the Schematic Builder is only in this edition.

## Install

1. Install Fabric Loader 0.19.5 or newer for Minecraft 26.2 (Java 25).
2. Install Fabric API 0.158.0 or newer for Minecraft 26.2.
3. Copy `donut-client-builder-2.2.1.jar` into your Minecraft `mods` folder, removing any other Donut Client jar, including the regular edition with GoNuts: both are the `donut` mod, and Fabric refuses to start with two copies.
4. Start Minecraft with the Fabric profile.

Press **Right Shift** to open the menu. The sidebar lists the module categories (Finders, Render, Utility, Build, HUD) and the Keybinds and Theme pages. A category shows its modules as cards: the switch turns a module on or off, and clicking the card opens its settings, each with a short description; **Back** returns to the list. Settings people rarely change, such as opacity, line width and colors, sit behind **More options** at the end of a module's settings, and **Reset settings** below them puts every setting back to its default on a second click, leaving the module on or off and its key as they were. Keybinds lists every module's key, and the menu's own, in one place (a right click puts a key back to its default), and Theme sets the accent color and optional snowfall. Type anywhere to search. Text fields select their text when clicked, so typing or pasting replaces it; Enter saves and Esc leaves the field as it was. Backspace or the mouse's back button goes back from a module's settings. Freecam defaults to **F7** (F6 is vanilla's debug options key). Other module keys can be assigned from the Bind row at the top of each module's settings, or under Minecraft's keybind settings.

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

### Building schematics

**SchematicBuilder** (the Build page) builds a Litematica schematic (`.litematic`) in the world from the blocks you carry, a layer at a time from the bottom up.

1. Put the `.litematic` in the game folder's `schematics` folder, where Litematica saves them (**Open** next to Schematics folder opens it), or drag the file onto the Donut menu, which copies it there. Pick it from the list under File. Its name, size, block count and the blocks it takes, against what you carry, show below.
2. Choose where it goes: **Set origin here** takes the block at your feet, or type `x y z` under Origin; with no origin set, the first start uses where you stand. Rotation turns it clockwise about the origin and Mirror flips it before turning, the way Litematica places schematics, so a Litematica placement's origin, rotation and mirror carry over. This was checked against Litematica's file format, not side by side with Litematica in game.
3. Set **Shortest delay** and **Longest delay**: each block waits a random time between the two (0.65 to 2.30 s by default, anything from 0.05 to 10 s). Set both the same for a fixed delay.
4. Switch it on, close the menu, and walk along the build: blocks go in as they come within reach, and the info panel's Builder line counts the blocks left, with an arrow to the next one. The schematic's outline and the blocks left in the current layer show in the world (the next one white, those without materials red, spots another block holds amber).

How it builds:

- **Layers**: a layer is finished before the next one starts. Inside a layer, plain blocks go first, then redstone parts (wire, repeaters, comparators, torches, levers, buttons, hoppers, rails and the like), and pistons, observers, dispensers, droppers, crafters and TNT last, so a half-built circuit fires as little as possible (Redstone last, under More options).
- **Support across layers**: a block only goes in once something can hold it. A wall torch waits for its wall, a top slab for the block beside or above it, sand for the block under it. A lantern, lever or button hanging under a ceiling that belongs to the layer above waits while that layer goes in, and goes in right after its ceiling does. Redstone wire that climbs or drops a block from layer to layer goes in on each step's block, and the game joins the steps up. Blocks that never get anything to hold them are reported when the rest is done.
- **Facing**: every block is placed as the schematic has it. The builder asks each block's own placement code what a click on a given face, looking a given way, would place, and sends the click that matches: repeaters, comparators, observers, pistons, hoppers, stairs, trapdoors, slabs, logs, wall torches, levers, doors (with their hinge), beds, signs and the like. The look goes to the server only; your camera stays where you point it. It sneaks to place against chests, repeaters and other blocks a right click would use.
- **After placing**: it clicks a repeater to its delay, a comparator to subtract, a note block to its note, a daylight detector to inverted, a lever on, a wooden door, trapdoor or gate open, and a lone wire to a dot, and adds candles, sea pickles, snow layers and the second slab of a double slab. Door tops, bed heads and piston heads come with their block. Water, lava, fire and blocks no item places are skipped.
- **Materials**: blocks come from your inventory, swapped into the hotbar as needed. A block that is only inside a shulker box in your inventory is fetched: the box is placed beside you, everything the rest of the build needs is taken out (one slot stays free for the box), and it is broken and picked up again, with a pickaxe from the hotbar when there is one. Keep a free slot and stand on open ground for this. When something runs out it builds what it can and waits, saying what is missing. In creative every block is made as it is placed.
- It never breaks blocks: a spot that holds another block is left and reported. Air in the schematic is not cleared.
- **Strict looks** (More options) only clicks faces you can see, looking straight at the spot clicked; some blocks then need you to stand on their other side.

It pauses while a menu is open, stops when you die, change dimension or leave, and Auto RTP refuses to run while it builds. Check a server's rules before automating building.

### Digging a base

**ChunkMiner** (the Build page) digs out the room a schematic needs, so SchematicBuilder can build it underground. It digs the schematic's whole box, with room around it on every side to walk around it (Room around it, 3 blocks by default) and above it (Headroom, 2), and a lower storage room beside it for chests (Storage room: depth 6 and height 4 by default, on the side the staircase comes in, or a side you pick). The floor under the schematic is never dug, so it stays to walk on.

1. Pick the schematic in SchematicBuilder first: the room is dug for it, turned and mirrored as SchematicBuilder places it.
2. With **Dig good chunks** on (the default), switch ChunkMiner on and it waits for ChunkFinder's best good chunk. It then puts the schematic in the middle of that chunk with its lowest layer at Floor height (y -40 by default), sets SchematicBuilder's origin there, turns Auto RTP off if it was running, and starts. A good chunk whose room would be in the deep dark (digging there calls the Warden), would reach the ground, or would hold much water or lava (an underground lake, more than 2% of the room) is passed over for the next one. With Dig good chunks off, it digs where SchematicBuilder's origin is, or where you stand (**Dig here**).
3. Carry a pickaxe, food, and some cobblestone or dirt. Close the menu: it does the rest.

How it digs:

- **The way in**: a staircase from where you stand to the room's floor, coming in at floor level, so once the room is dug you can walk in and out. It winds down around a pillar until a straight run can make up the rest of the height. Of the four sides it could come in by, it takes the one with the least water, lava and digging on the way, and never one through the room itself.
- **The room**: a winding staircase inside it climbs to the top, then the room is dug from the top down in bands three or four blocks tall, standing on the band below each, and the player digs down through the block underfoot, a block at a time, to the next band. The climb and the steps down are dug away with the room. The info panel's Miner line counts the blocks left, with an arrow to the block being dug; the room's outline, where the schematic goes in it, and that block show in the world.
- **Pickaxes**: it checks what you carry. An **Amethyst Pickaxe** (any pickaxe whose name or lore says Amethyst or 3x3, as on DonutSMP) breaks 3x3 a swing, flat against the face hit, and the miner plans every swing with that square, so no swing ever takes the floor you stand on, the room's floor or the staircase. It only swings it with a look that agrees with the face, well down at a top, level at a side, since servers differ on which they go by, and its staircases have a level step before each drop or climb. On the first swings it checks what the pickaxe really breaks: one that turns out to break one block is used as a normal pickaxe, and one that breaks 5x5 is planned that way. **Use** picks Amethyst first (with a normal pickaxe for what the 3x3 can't do safely), Amethyst only, or Normal only. Blocks are broken with the fastest tool for them, a tool with Keep durability uses left (15) is put away so it never breaks, and blocks that would take more than 20 seconds are left.
- **Falls and uneven ground**: hills on the way are dug through and dips and caves get a block of cobblestone or dirt under each step (Fill gaps). If you fall, off the staircase or through a floor that gives way, it plans again from where you land: a new way in from outside, a climb back inside the room, or, down a hole it fell through, back up it the way a player towers, a block under the feet at a time. Partial blocks such as slabs, paths and snow layers count as the block under them. A walk that gets stuck tries another place.
- **Gravel and sand**: it never digs a block with sand, gravel or dripstone over it in your own column, so nothing falls on your head; it digs it from beside, and what falls in is dug again. A heap of fallen gravel you stand on is dug out from on top.
- **Water and lava**: it never breaks a block next to water or lava, nor ice, which turns to water; those blocks are left and counted when it finishes, with any it couldn't then reach safely. Water or lava met on the way in sends the staircase round another way.
- It never breaks chests, spawners or other blocks with contents, nor bedrock.

**Looking human**: the camera turns to each block by itself, at most Turn speed degrees a tick (24), easing in and out, by whole mouse steps for your sensitivity, with a little wobble, never jumping. It goes for blocks near where it already looks, sweeping along, rests on a point somewhat off the face's middle (Aim spread), drifts a little while the block breaks (Hand shake), and only breaks a block the crosshair is on. Each block waits a random time between Shortest and Longest delay (0.05 to 0.35 s, on top of the quarter second the game itself waits between blocks), and after any block there is a Pause chance (4%) of a longer pause, glancing around. With Vary order on, it doesn't always take the very best next block, so no two rooms are dug the same way. It walks with the movement keys, jumping onto steps.

It pauses while a menu is open (Minecraft opens one when its window loses focus; press F3+P to turn that off and let it dig while you use another window), while SchematicBuilder builds, while you are hungry (eat and it goes on), and while another player is within Pause for players (32 blocks), with a sound. It stops when you take damage (Stop when hurt), die, change dimension or leave, and Auto RTP refuses to run while it digs. **Build when done** starts SchematicBuilder on the room once it is dug. Check a server's rules before automating mining.

## Build

Install JDK 25, then run:

```powershell
.\gradlew.bat build
```

The finished mod is written to `build/libs/donut-client-builder-2.2.1.jar`. The regular edition, with GoNuts inside, is built from the `gonuts-update` branch.

## License

MIT. The bundled Inter font uses the SIL Open Font License.
