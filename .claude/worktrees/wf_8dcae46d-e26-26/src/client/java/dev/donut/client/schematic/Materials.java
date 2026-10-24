package dev.donut.client.schematic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/** What the player carries, loose in the inventory and inside the shulker boxes in it. */
public final class Materials {
    /** Main inventory and hotbar; armor and the off hand are left alone. */
    public static final int SLOTS = 36;
    /** The slots a shulker box holds. */
    private static final int BOX_SLOTS = 27;

    private final Map<Item, Integer> loose = new HashMap<>();
    private final Map<Item, Integer> boxed = new HashMap<>();
    private int freeSlots;

    private Materials() { }

    public static Materials of(Inventory inventory) {
        Materials materials = new Materials();
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                materials.freeSlots++;
                continue;
            }
            materials.loose.merge(stack.getItem(), stack.getCount(), Integer::sum);
            if (isShulkerBox(stack)) {
                for (ItemStack inside : contents(stack)) materials.boxed.merge(inside.getItem(), inside.getCount(), Integer::sum);
            }
        }
        return materials;
    }

    public int loose(Item item) { return loose.getOrDefault(item, 0); }

    public int boxed(Item item) { return boxed.getOrDefault(item, 0); }

    public int total(Item item) { return loose(item) + boxed(item); }

    public int freeSlots() { return freeSlots; }

    public static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock;
    }

    public static Iterable<ItemStack> contents(ItemStack shulker) {
        ItemContainerContents contents = shulker.get(DataComponents.CONTAINER);
        return contents == null ? java.util.List.of() : contents.nonEmptyItemCopyStream().toList();
    }

    /** How many of {@code item} the shulker box in this stack holds. */
    public static int countInside(ItemStack shulker, Item item) {
        int count = 0;
        for (ItemStack inside : contents(shulker)) if (inside.is(item)) count += inside.getCount();
        return count;
    }

    /** The inventory slot of the shulker box holding the most of {@code item}, or -1. */
    public static int shulkerWith(Inventory inventory, Item item) {
        int best = -1, most = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isShulkerBox(stack)) continue;
            int count = countInside(stack, item);
            if (count > most) {
                most = count;
                best = slot;
            }
        }
        return best;
    }

    /** The inventory slot holding the most of {@code item} loose, hotbar first on a tie, or -1. */
    public static int slotWith(Inventory inventory, Item item) {
        int best = -1, most = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(item)) continue;
            if (stack.getCount() > most) {
                most = stack.getCount();
                best = slot;
            }
        }
        return best;
    }

    /** The inventory slot of the shulker box with the most room left in it, or -1 when none has any. */
    public static int shulkerWithRoom(Inventory inventory) {
        int best = -1, most = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isShulkerBox(stack)) continue;
            int used = 0;
            for (ItemStack ignored : contents(stack)) used++;
            if (BOX_SLOTS - used > most) {
                most = BOX_SLOTS - used;
                best = slot;
            }
        }
        return best;
    }

    /** The stacks of the hotbar and the main inventory as they are now, the hotbar's first, by slot. */
    public static List<ItemStack> slots(Inventory inventory) {
        List<ItemStack> slots = new ArrayList<>(SLOTS);
        for (int slot = 0; slot < SLOTS; slot++) slots.add(inventory.getItem(slot));
        return slots;
    }

    /**
     * Whether {@code item} lying on the ground would be picked up now: an empty slot, or a stack of it with room left. A
     * full inventory with no such stack leaves it lying there, where it is washed off, drawn into a hopper or despawns.
     */
    public static boolean canTake(List<ItemStack> slots, Item item) {
        ItemStack one = new ItemStack(item);
        for (int slot = 0; slot < Math.min(SLOTS, slots.size()); slot++) {
            ItemStack stack = slots.get(slot);
            if (stack.isEmpty()) return true;
            if (stack.getCount() < stack.getMaxStackSize() && ItemStack.isSameItemSameComponents(stack, one)) return true;
        }
        return false;
    }

    /** How many of {@code item} the hotbar holds. */
    static int inHotbar(List<ItemStack> slots, Item item) {
        int count = 0;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) if (slots.get(slot).is(item)) count += slots.get(slot).getCount();
        return count;
    }

    /** The slot outside the hotbar holding the most of {@code item}, or -1. */
    static int outsideHotbar(List<ItemStack> slots, Item item) {
        int best = -1, most = 0;
        for (int slot = Inventory.getSelectionSize(); slot < Math.min(SLOTS, slots.size()); slot++) {
            ItemStack stack = slots.get(slot);
            if (stack.is(item) && stack.getCount() > most) {
                most = stack.getCount();
                best = slot;
            }
        }
        return best;
    }

    /**
     * The hotbar slot an item is moved into: an empty one, the selected one first; else the one whose item is needed
     * least, by {@code need}, so the blocks in use stay at hand rather than taking turns in one slot. Only blocks and
     * buckets give up their slot: never a tool, the first stack of each helper block, nor an item in {@code keep}. Shulker
     * boxes and further helper stacks, fetched again when needed, go first. -1 when no slot may be given up.
     */
    static int giveUpSlot(List<ItemStack> slots, int selected, Set<Item> helperItems, Map<Item, Integer> need, Set<Item> keep) {
        int hotbar = Inventory.getSelectionSize();
        if (selected >= 0 && selected < hotbar && slots.get(selected).isEmpty()) return selected;
        for (int slot = 0; slot < hotbar; slot++) if (slots.get(slot).isEmpty()) return slot;
        Set<Item> helpersKept = new HashSet<>();
        int best = -1, bestNeed = Integer.MAX_VALUE;
        for (int slot = 0; slot < hotbar; slot++) {
            ItemStack held = slots.get(slot);
            Item item = held.getItem();
            if (!(item instanceof BlockItem) && !(item instanceof BucketItem)) continue;
            if (helperItems.contains(item) && helpersKept.add(item)) continue;
            if (keep.contains(item)) continue;
            int needed = isShulkerBox(held) || helperItems.contains(item) ? 0 : need.getOrDefault(item, 0);
            if (needed < bestNeed) {
                best = slot;
                bestNeed = needed;
            }
        }
        return best;
    }

    /**
     * Before a walk whose climbing steps fill cells dug out of the ground, each with the item it held: the move {from, to}
     * that brings one of those items, carried outside the hotbar, into a hotbar slot {@link #giveUpSlot} gives up, or null
     * when each is at hand already, isn't carried loose, or no slot may be given up for it.
     */
    static int[] fillSwap(List<ItemStack> slots, int selected, Collection<Item> fills, Set<Item> helperItems, Map<Item, Integer> need,
                          Set<Item> keep) {
        for (Item fill : fills) {
            if (inHotbar(slots, fill) > 0) continue;
            int from = outsideHotbar(slots, fill);
            if (from < 0) continue;
            int to = giveUpSlot(slots, selected, helperItems, need, keep);
            return to < 0 ? null : new int[] {from, to};
        }
        return null;
    }
}
