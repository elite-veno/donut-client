package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MaterialsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Set<Item> HELPERS = Set.of(Items.DIRT);

    /** The hotbar and main inventory, with {@code fill} in every slot {@code stacks} doesn't set. */
    private static List<ItemStack> inventory(ItemStack fill, Map<Integer, ItemStack> stacks) {
        List<ItemStack> slots = new ArrayList<>();
        for (int slot = 0; slot < Materials.SLOTS; slot++) slots.add(stacks.getOrDefault(slot, fill).copy());
        return slots;
    }

    /**
     * A hotbar as a long build has it by the time it digs its first pits: shulker boxes, the pickaxe selected, the helper
     * blocks kept, and the blocks of the layer swapped in, with {@code more} set on top.
     */
    private static Map<Integer, ItemStack> busyHotbar(Map<Integer, ItemStack> more) {
        Map<Integer, ItemStack> stacks = new HashMap<>();
        stacks.put(0, new ItemStack(Items.SHULKER_BOX));
        stacks.put(1, new ItemStack(Items.SHULKER_BOX));
        stacks.put(2, new ItemStack(Items.SHULKER_BOX));
        stacks.put(3, new ItemStack(Items.DIAMOND_PICKAXE));
        stacks.put(4, new ItemStack(Items.DIRT, 64));
        stacks.put(5, new ItemStack(Items.OBSERVER, 30));
        stacks.put(6, new ItemStack(Items.GLASS, 40));
        stacks.put(7, new ItemStack(Items.OAK_FENCE_GATE, 10));
        stacks.put(8, new ItemStack(Items.BLUE_CONCRETE, 20));
        stacks.putAll(more);
        return stacks;
    }

    private static final Map<Item, Integer> LAYER_NEEDS = Map.of(Items.OBSERVER, 12, Items.GLASS, 30, Items.OAK_FENCE_GATE, 4,
            Items.BLUE_CONCRETE, 8, Items.SMOOTH_STONE, 4);

    @Test
    void aDropIsPickedUpIntoAnEmptySlotOrAStackOfItWithRoomLeft() {
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 64);
        assertFalse(Materials.canTake(inventory(cobble, Map.of()), Items.CHEST), "every slot full of something else");
        assertFalse(Materials.canTake(inventory(cobble, Map.of()), Items.COBBLESTONE), "every stack of it full");
        assertTrue(Materials.canTake(inventory(cobble, Map.of(30, new ItemStack(Items.CHEST, 63))), Items.CHEST), "a chest stack with room");
        assertFalse(Materials.canTake(inventory(cobble, Map.of(30, new ItemStack(Items.CHEST, 64))), Items.CHEST), "the chest stack full");
        assertTrue(Materials.canTake(inventory(cobble, Map.of(12, ItemStack.EMPTY)), Items.CHEST), "one slot free");
        assertFalse(Materials.canTake(inventory(cobble, Map.of(30, new ItemStack(Items.TRAPPED_CHEST, 10))), Items.CHEST),
                "a stack of another item has no room for it");
    }

    @Test
    void theDugBlockCarriedOutsideTheHotbarComesInForWhatIsNeededLeast() {
        // The stone dug out of a pit landed in the main inventory; the hotbar is full. It takes a shulker box's slot, which
        // goes back into the inventory, rather than one of the blocks the layer is placing.
        List<ItemStack> slots = inventory(ItemStack.EMPTY, busyHotbar(Map.of(20, new ItemStack(Items.SMOOTH_STONE, 4))));
        int[] move = Materials.fillSwap(slots, 3, List.of(Items.SMOOTH_STONE), HELPERS, LAYER_NEEDS, Set.of(Items.SMOOTH_STONE));
        assertNotNull(move);
        assertArrayEquals(new int[] {20, 0}, move);
        // An empty hotbar slot is taken before any other, the selected one first.
        slots.set(6, ItemStack.EMPTY);
        assertArrayEquals(new int[] {20, 6}, Materials.fillSwap(slots, 3, List.of(Items.SMOOTH_STONE), HELPERS, LAYER_NEEDS, Set.of()));
        slots.set(3, ItemStack.EMPTY);
        assertArrayEquals(new int[] {20, 3}, Materials.fillSwap(slots, 3, List.of(Items.SMOOTH_STONE), HELPERS, LAYER_NEEDS, Set.of()));
    }

    @Test
    void nothingMovesWhenTheDugBlockIsAtHandOrNotCarried() {
        List<ItemStack> atHand = inventory(ItemStack.EMPTY, busyHotbar(Map.of(7, new ItemStack(Items.SMOOTH_STONE, 4),
                20, new ItemStack(Items.SMOOTH_STONE, 30))));
        assertNull(Materials.fillSwap(atHand, 3, List.of(Items.SMOOTH_STONE), HELPERS, LAYER_NEEDS, Set.of(Items.SMOOTH_STONE)));
        List<ItemStack> notCarried = inventory(ItemStack.EMPTY, busyHotbar(Map.of()));
        assertNull(Materials.fillSwap(notCarried, 3, List.of(Items.SMOOTH_STONE), HELPERS, LAYER_NEEDS, Set.of(Items.SMOOTH_STONE)));
        // Of two dug blocks, the one not at hand is brought in, and not in place of the other.
        List<ItemStack> two = inventory(ItemStack.EMPTY, busyHotbar(Map.of(0, new ItemStack(Items.SMOOTH_STONE, 4),
                1, new ItemStack(Items.DIAMOND_AXE), 2, new ItemStack(Items.DIAMOND_SHOVEL), 25, new ItemStack(Items.COBBLESTONE, 9))));
        assertArrayEquals(new int[] {25, 7}, Materials.fillSwap(two, 3, List.of(Items.SMOOTH_STONE, Items.COBBLESTONE), HELPERS, LAYER_NEEDS,
                Set.of(Items.SMOOTH_STONE, Items.COBBLESTONE)));
    }

    @Test
    void toolsTheKeptHelperStackAndItemsToKeepNeverGiveUpTheirSlot() {
        Map<Integer, ItemStack> stacks = new HashMap<>();
        stacks.put(0, new ItemStack(Items.DIAMOND_PICKAXE));
        stacks.put(1, new ItemStack(Items.DIRT, 64));
        stacks.put(2, new ItemStack(Items.DIRT, 40));
        stacks.put(3, new ItemStack(Items.DIAMOND_SHOVEL));
        stacks.put(4, new ItemStack(Items.SMOOTH_STONE, 3));
        stacks.put(5, new ItemStack(Items.DIAMOND_AXE));
        stacks.put(6, new ItemStack(Items.DIAMOND_SWORD));
        stacks.put(7, new ItemStack(Items.BREAD, 10));
        stacks.put(8, new ItemStack(Items.SHEARS));
        stacks.put(30, new ItemStack(Items.COBBLESTONE, 5));
        List<ItemStack> slots = inventory(ItemStack.EMPTY, stacks);
        // The second helper stack goes; the first, the tools, the food and the stone kept for a dug cell stay.
        assertEquals(2, Materials.giveUpSlot(slots, 0, HELPERS, Map.of(), Set.of(Items.SMOOTH_STONE)));
        // With the rest of the climb on helper blocks, those stay too, and no slot is left to give up.
        assertEquals(-1, Materials.giveUpSlot(slots, 0, HELPERS, Map.of(), Set.of(Items.SMOOTH_STONE, Items.DIRT)));
        assertNull(Materials.fillSwap(slots, 0, List.of(Items.COBBLESTONE), HELPERS, Map.of(), Set.of(Items.SMOOTH_STONE, Items.COBBLESTONE, Items.DIRT)));
    }

    @Test
    void groundStillToFillOutranksABlockNoLayerNeeds() {
        // Smooth stone the build never lists, kept for four open cells of dug ground, stays at hand over glass the layers
        // being built no longer take; with no dug ground left it would be the first to go.
        Map<Integer, ItemStack> stacks = new HashMap<>();
        for (int slot = 0; slot < 9; slot++) stacks.put(slot, new ItemStack(Items.OBSERVER, 10));
        stacks.put(2, new ItemStack(Items.SMOOTH_STONE, 6));
        stacks.put(5, new ItemStack(Items.GLASS, 6));
        List<ItemStack> slots = inventory(ItemStack.EMPTY, stacks);
        assertEquals(5, Materials.giveUpSlot(slots, 0, HELPERS, Map.of(Items.OBSERVER, 40, Items.SMOOTH_STONE, 4), Set.of()));
        assertEquals(2, Materials.giveUpSlot(slots, 0, HELPERS, Map.of(Items.OBSERVER, 40, Items.GLASS, 3), Set.of()));
    }
}
