package com.pglol.aotrpg;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;

/** Satchel screen: 4 rows of satchel slots above the player's inventory. */
public class SatchelHandler extends ScreenHandler {
    public static ScreenHandlerType<SatchelHandler> TYPE;
    public static final int BAG_Y = 18, INV_Y = 104, HOTBAR_Y = 162;
    private final Inventory bag;

    static void register() {
        TYPE = Registry.register(Registries.SCREEN_HANDLER, Identifier.of(AotRpg.MOD_ID, "satchel"),
            new ScreenHandlerType<>(SatchelHandler::new, FeatureFlags.VANILLA_FEATURES));
    }

    /** Client side. */
    public SatchelHandler(int syncId, PlayerInventory playerInv) {
        this(syncId, playerInv, new SimpleInventory(Satchel.SIZE));
    }

    public SatchelHandler(int syncId, PlayerInventory playerInv, Inventory bag) {
        super(TYPE, syncId);
        this.bag = bag;
        bag.onOpen(playerInv.player);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(bag, c + r * 9, 8 + c * 18, BAG_Y + r * 18) {
                    @Override
                    public boolean canInsert(ItemStack stack) {
                        return Satchel.accepts(stack);
                    }
                });
            }
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) addSlot(new PlayerSlot(playerInv, 9 + c + r * 9, 8 + c * 18, INV_Y + r * 18));
        }
        for (int c = 0; c < 9; c++) addSlot(new PlayerSlot(playerInv, c, 8 + c * 18, HOTBAR_Y));
    }

    /** Story items stay in the satchel. */
    private static final class PlayerSlot extends Slot {
        PlayerSlot(Inventory inv, int index, int x, int y) {
            super(inv, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return !Satchel.isStory(stack);
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasStack()) return ItemStack.EMPTY;
        ItemStack stack = slot.getStack();
        ItemStack copy = stack.copy();
        if (index < Satchel.SIZE) {
            if (Satchel.isStory(stack)) return ItemStack.EMPTY;
            if (!insertItem(stack, Satchel.SIZE, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!Satchel.accepts(stack) || !insertItem(stack, 0, Satchel.SIZE, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setStack(ItemStack.EMPTY);
        else slot.markDirty();
        return copy;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        bag.onClose(player);
        if (!player.getWorld().isClient) AotRpg.SATCHEL.save(player.getUuid());
    }
}
