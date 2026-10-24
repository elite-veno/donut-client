package dev.donut.client.schematic;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/** What the player carries, loose in the inventory and inside the shulker boxes in it. */
public final class Materials {
    /** Main inventory and hotbar; armor and the off hand are left alone. */
    public static final int SLOTS = 36;

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
}
