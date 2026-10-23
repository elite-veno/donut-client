package dev.donut.client.gametest;

import dev.donut.client.schematic.BlockRules;
import dev.donut.client.schematic.Schematic;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** What a schematic build test sets up and checks: the materials, packed in shulker boxes, and the blocks built. */
final class BuildCheck {
    private BuildCheck() { }

    /** Commands that give everything the build takes, a stack per slot, in shulker boxes of 27 slots each. */
    static List<String> shulkerGives(Map<Item, Integer> bill) {
        List<String> stacks = new ArrayList<>();
        for (Map.Entry<Item, Integer> need : bill.entrySet()) {
            Item item = need.getKey();
            int max = item.getDefaultMaxStackSize(), left = need.getValue();
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            while (left > 0) {
                int count = Math.min(max, left);
                stacks.add("{id:\"" + id + "\",count:" + count + "}");
                left -= count;
            }
        }
        List<String> commands = new ArrayList<>();
        for (int box = 0; box * 27 < stacks.size(); box++) {
            StringBuilder contents = new StringBuilder();
            for (int slot = 0; slot < 27 && box * 27 + slot < stacks.size(); slot++) {
                if (slot > 0) contents.append(',');
                contents.append("{slot:").append(slot).append(",item:").append(stacks.get(box * 27 + slot)).append('}');
            }
            commands.add("give @a minecraft:purple_shulker_box[minecraft:container=[" + contents + "]]");
        }
        return commands;
    }

    /**
     * Every block the builder places that the world doesn't hold as the schematic has it, with a count per kind first;
     * empty when all match. Run on the server thread.
     */
    static List<String> differences(ServerLevel level, Schematic schematic, BlockPos origin) {
        Map<String, Integer> byKind = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < schematic.size(); i++) {
            BlockState target = Schematic.place(schematic.state(i), Mirror.NONE, Rotation.NONE);
            BlockRules.Kind kind = BlockRules.kind(target);
            if (kind == BlockRules.Kind.UNPLACEABLE) continue;
            BlockPos pos = Schematic.place(schematic.pos(i), origin, Mirror.NONE, Rotation.NONE);
            BlockState actual = level.getBlockState(pos);
            boolean ok;
            if (kind == BlockRules.Kind.FLUID) {
                // Kelp grows up into the water above it, the farm at work; water over soul sand bubbles.
                ok = BlockRules.holdsFluid(actual, target);
            } else if (target.getBlock() instanceof LiquidBlock) {
                // Flowing water: anything wet will do; where it runs depends on what the machine does.
                ok = !actual.getFluidState().isEmpty();
            } else if (target.getBlock() instanceof PistonHeadBlock) {
                // A piston's head is out only while the machine powers it: its base standing there, facing the same way,
                // is what was built. Whether it is pushed out at the moment the check runs is the redstone's business.
                BlockState base = level.getBlockState(pos.relative(target.getValue(PistonHeadBlock.FACING).getOpposite()));
                ok = actual.getBlock() instanceof PistonHeadBlock || actual.is(Blocks.MOVING_PISTON)
                        || base.getBlock() instanceof PistonBaseBlock && base.getValue(PistonBaseBlock.FACING) == target.getValue(PistonHeadBlock.FACING);
            } else if (kind == BlockRules.Kind.AUTO) {
                ok = BlockRules.placementMatches(actual, target);
            } else {
                ok = BlockRules.satisfied(actual, target);
                if (ok && target.getBlock() instanceof ChestBlock) ok = actual.getValue(ChestBlock.TYPE) == target.getValue(ChestBlock.TYPE);
            }
            // Kelp cut by the farm's own pistons since, grown back from the kelp under it in time (see BlockRules.harvested).
            if (!ok && BlockRules.harvested(level, pos, target)) ok = true;
            if (!ok) {
                byKind.merge(target.getBlock().getName().getString(), 1, Integer::sum);
                lines.add(pos.subtract(origin).toShortString() + ": " + actual + " instead of " + target);
            }
        }
        if (lines.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "Differences per kind: %s", byKind));
        out.addAll(lines);
        return out;
    }
}
