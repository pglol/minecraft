package com.pglol.aotrpg;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A chest-style menu rendered entirely on the server: players see a normal chest screen whose
 * items act as buttons. Works with an unmodified client.
 */
public class Menu {
    public final Text title;
    public final int rows;
    final SimpleInventory inv;
    private final Map<Integer, Consumer<ServerPlayerEntity>> actions = new HashMap<>();
    /** Called when the player closes the screen themselves (Esc). */
    Consumer<ServerPlayerEntity> onClose = p -> { };

    public Menu(Text title, int rows) {
        this.title = title;
        this.rows = rows;
        this.inv = new SimpleInventory(rows * 9);
        ItemStack pane = icon(Items.GRAY_STAINED_GLASS_PANE, Text.literal(" "));
        for (int i = 0; i < rows * 9; i++) inv.setStack(i, pane.copy());
    }

    public Menu button(int slot, ItemStack stack, Consumer<ServerPlayerEntity> action) {
        inv.setStack(slot, stack);
        if (action != null) actions.put(slot, action);
        return this;
    }

    public Menu onClose(Consumer<ServerPlayerEntity> c) {
        onClose = c;
        return this;
    }

    public void open(ServerPlayerEntity player) {
        ScreenHandlerType<?> type = switch (rows) {
            case 1 -> ScreenHandlerType.GENERIC_9X1;
            case 2 -> ScreenHandlerType.GENERIC_9X2;
            case 3 -> ScreenHandlerType.GENERIC_9X3;
            case 4 -> ScreenHandlerType.GENERIC_9X4;
            case 5 -> ScreenHandlerType.GENERIC_9X5;
            default -> ScreenHandlerType.GENERIC_9X6;
        };
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInv, p) -> new Handler(type, syncId, playerInv, this), title));
    }

    /** An item with a coloured, non-italic name and lore lines. */
    public static ItemStack icon(Item item, Text name, Text... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.CUSTOM_NAME, name.copy().styled(st -> st.withItalic(false)));
        if (lore.length > 0) {
            List<Text> lines = new ArrayList<>();
            for (Text t : lore) lines.add(t.copy().styled(st -> st.withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(lines));
        }
        s.set(DataComponentTypes.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
        return s;
    }

    public static ItemStack glow(ItemStack s) {
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    public static Text line(String text, Formatting... color) {
        return Text.literal(text).formatted(color);
    }

    static final class Handler extends GenericContainerScreenHandler {
        private final Menu menu;
        private boolean clicked;

        Handler(ScreenHandlerType<?> type, int syncId, PlayerInventory playerInv, Menu menu) {
            super(type, syncId, playerInv, menu.inv, menu.rows);
            this.menu = menu;
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity player) {
            // Items never move: every click is a button press.
            if (player instanceof ServerPlayerEntity sp && slot >= 0 && slot < menu.rows * 9) {
                Consumer<ServerPlayerEntity> a = menu.actions.get(slot);
                if (a != null) {
                    clicked = true;
                    a.accept(sp);
                    return;
                }
            }
            syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void onClosed(PlayerEntity player) {
            super.onClosed(player);
            if (!clicked && player instanceof ServerPlayerEntity sp) menu.onClose.accept(sp);
        }
    }
}
