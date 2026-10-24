package dev.donut.client.miner;

import java.util.Locale;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** What the player carries, as the miner sees it: pickaxes, how worn they are, and blocks to fill gaps with. */
public final class Kit {
    /** The main inventory and hotbar; armor and the off hand are left alone. */
    public static final int SLOTS = 36;
    /** Plain blocks mining brings in, fine to put down under a step. */
    private static final Set<Item> JUNK = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.DEEPSLATE,
            Items.DIRT, Items.NETHERRACK, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.CALCITE,
            Items.BLACKSTONE, Items.BASALT, Items.SMOOTH_BASALT, Items.END_STONE, Items.COARSE_DIRT, Items.ROOTED_DIRT);

    private Kit() { }

    public static boolean isPickaxe(ItemStack stack) { return !stack.isEmpty() && stack.is(ItemTags.PICKAXES); }

    /**
     * Whether a pickaxe says it mines an area: DonutSMP's Amethyst Pickaxe is named so, and such tools usually say 3x3
     * in their lore. The miner checks this on the first swings and goes by what really breaks.
     */
    public static boolean saysArea(ItemStack stack) {
        if (!isPickaxe(stack)) return false;
        if (mentionsArea(stack.getHoverName())) return true;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) for (Component line : lore.lines()) if (mentionsArea(line)) return true;
        return false;
    }

    private static boolean mentionsArea(Component text) {
        String plain = text.getString().toLowerCase(Locale.ROOT);
        return plain.contains("amethyst") || plain.contains("3x3") || plain.contains("3 x 3");
    }

    /** Uses left before the tool breaks; tools without durability never wear out. */
    public static int usesLeft(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }

    /** Tells one tool from another of the same kind, for remembering what each one turned out to break. */
    public static String key(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + stack.getHoverName().getString();
    }

    public static boolean isJunk(ItemStack stack) { return !stack.isEmpty() && JUNK.contains(stack.getItem()); }

    /** The inventory slot with the most of one junk block, or -1. */
    public static int junkSlot(Inventory inventory) {
        int best = -1, most = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isJunk(stack) && stack.getCount() > most) {
                most = stack.getCount();
                best = slot;
            }
        }
        return best;
    }

    public static int freeSlots(Inventory inventory) {
        int free = 0;
        for (int slot = 0; slot < SLOTS; slot++) if (inventory.getItem(slot).isEmpty()) free++;
        return free;
    }

    public static String name(ItemStack stack) { return stack.getHoverName().getString(); }
}
