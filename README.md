# Donut Client

A client-side Fabric mod for Minecraft Java 26.2 for seeing what is hidden: storage, spawner and ancient debris highlights, X-ray, Fullbright, dug tunnel and hole detection, player and mob ESP with tracers, amethyst-based activity detection with Auto RTP, Freecam, module keybinds, distance labels and an info HUD, and a Schematic Builder that builds Litematica schematics block by block.

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

### Finding a base spot

**ChunkFinder** (Finders page) looks for a chunk to dig a base into on a server like DonutSMP. A good chunk has no player build or structure in or near it, no sign that players spent time nearby (such as where people landed with RTP), and little cave under it. It marks the best chunk with a green square and a beam, puts squares on the other good chunks, and announces each new area's best chunk in chat with its coordinates. With Show signs on, chunks with signs of players get red squares and structures get amber outlines, each labelled with why.

**Player builds.** Any block only players or structures place counts once it is open to air: planks, stairs, slabs, fences, doors, glass, wool, bricks, polished and cut stone, concrete, torches, lanterns, rails, beds, chests, workstations, redstone parts and the like ("Built: crafting table"). Buried blocks don't count, because anti-xray fills buried stone with fake chests and ores. Cobblestone, obsidian, mossy cobblestone, smooth basalt and terracotta don't count either, since nature makes them too. Item frames, paintings, armor stands, boats and minecarts count, as do tunnels, holes and staircases players dig: TunnelFinder's detector, from 16 blocks long, since natural shafts reach 11.

**Structures.** A chunk with blocks that give a structure away is part of one: spawners, rails and cobwebs, bells and dirt paths, trial chambers' tuff, copper and vaults, ancient cities' deepslate, cracked, mossy and chiseled stone bricks, desert sandstone, prismarine, crying obsidian and suspicious sand. Chunks of plain built blocks joined to it belong to it too, as does a buried chunk of nothing but mineshaft supports, chains, rails and torches. A structure only rules out its own chunk and the chunks touching it. Built blocks far above or below what gives it away, such as a crafting table on the ground over a mineshaft, are still someone's build. Checked on DonutSMP's seed: of 3185 freshly generated chunks, about one in six is part of a structure, mostly mineshafts; none showed signs of players, and 13 mineshaft pieces with nothing that gives them away (0.4%) read as builds.

**Caves.** Max caves (5% by default) is how much of a spot's underground, from the ground down to the bedrock floor, may be open cave, water or lava, so a base can be dug without falling or flooding. Half of all chunks are under 5%. Caves only rule out the chunk itself.

**Signs of players.** The server only grows plants in chunks within a player's simulation distance, so plants show whether someone spent time nearby:

- **Sugar cane and cactus** generate at age 0 and age on every random tick, about once a minute at the default speed. The server never sends that change on its own, so any age seen in a freshly sent chunk means a player was within range for a while. All sugar cane grown to 3 tall says the same.
- **Kelp** generates at age 20 to 23, at most 11 blocks tall and never up to the water's surface; older, taller or surface kelp grew.
- **Vines** hang at most 4 blocks below a leaf when generated; longer hanging vines grew.
- **Amethyst** grown past worldgen (as ActivityFinder judges it) and **players** in sight are signs too.

Growth only counts when chance and your own time nearby can't explain it: plants are judged as the server first sent them, and the time you spent within simulation distance before that is allowed for. Growth from stays shorter than **Ignore visits under** (2 minutes by default), such as someone flying past, doesn't count. A player build or a sign of players keeps spots **Clearance** chunks away (4 by default), and every chunk within the clearance must be loaded. A spot also needs enough cane, cactus, kelp or vines around it that a player staying **Max unseen time** (10 minutes by default) would have grown at least one, 19 times in 20. One sugar cane covers a visit of about 3½ minutes; kelp and vines take more. Ground without such plants can't vouch for anything, so it gives no spots. The best chunk is the one deepest inside clear ground, then the one with the most telling plants, then the nearest. A server that changes its random tick speed shifts all these times.

Only the Overworld has these plants, and ChunkFinder only sees chunks your client holds; a higher render distance judges a wider area.

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

## Build

Install JDK 25, then run:

```powershell
.\gradlew.bat build
```

The finished mod is written to `build/libs/donut-client-builder-2.2.1.jar`. The regular edition, with GoNuts inside, is built from the `gonuts-update` branch.

## License

MIT. The bundled Inter font uses the SIL Open Font License.
