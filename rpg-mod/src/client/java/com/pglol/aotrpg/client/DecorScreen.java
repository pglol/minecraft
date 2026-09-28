package com.pglol.aotrpg.client;

import com.pglol.aotrpg.HomeDecor;
import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Furnishing your home floor by floor: each floor a row, the themes as cards along it. Pick one
 * and furnish; the walls fill with its pieces and the middle of the room stays yours to build in.
 */
public final class DecorScreen extends Screen {
    private static Net.DecorView view;
    /** The card picked on each floor (by the floor's level). */
    private final Map<Integer, String> picked = new HashMap<>();
    private int left, top, cw, ch = 54, rowH = 76, label = 120;

    public DecorScreen() {
        super(Text.literal("Furnish"));
    }

    public static void on(Net.DecorView v) {
        MinecraftClient mc = MinecraftClient.getInstance();
        view = v;
        if (mc.currentScreen instanceof DecorScreen s) s.clearAndInit();
        else if (v.open()) mc.setScreen(new DecorScreen());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        int n = HomeDecor.PACKAGES.size();
        cw = Math.max(46, Math.min(70, (width - 40 - label - 90) / n - 4));
        int w = label + n * (cw + 4) + 90;
        left = (width - w) / 2;
        int rows = view == null ? 0 : view.names().size();
        top = Math.max(56, (height - rows * rowH) / 2);
        addDrawableChild(new AotButton(width - 30, 10, 20, 20, Text.literal("✕"), this::close));
        if (view == null) return;
        for (int r = 0; r < rows; r++) {
            int y = top + r * rowH, fy = view.ys().get(r);
            String cur = view.current().get(r);
            String pick = picked.getOrDefault(fy, "");
            boolean change = !pick.isEmpty() && !pick.equals(cur);
            int bx = left + label + n * (cw + 4) + 6;
            AotButton go = addDrawableChild(new AotButton(bx, y + 8, 84, 18, Text.literal(change ? String.format(Locale.ROOT, "%,d M", view.prices().get(r)) : "Furnish"),
                () -> ClientPlayNetworking.send(new Net.HomeAction("decor_apply", view.home(), fy + ":" + picked.get(fy)))));
            go.textScale = 0.8f;
            go.active = change && ClientState.marks >= view.prices().get(r);
            if (!cur.isEmpty()) {
                AotButton clear = addDrawableChild(new AotButton(bx, y + 30, 84, 16, Text.literal("Clear"),
                    () -> ClientPlayNetworking.send(new Net.HomeAction("decor_apply", view.home(), fy + ":none"))));
                clear.textScale = 0.7f;
                clear.accent = Ui.RED;
            }
        }
    }

    private int[] cardAt(double mx, double my) {
        if (view == null) return null;
        for (int r = 0; r < view.names().size(); r++) {
            int y = top + r * rowH;
            for (int i = 0; i < HomeDecor.PACKAGES.size(); i++) {
                int x = left + label + i * (cw + 4);
                if (mx >= x && mx < x + cw && my >= y && my < y + ch) return new int[] {r, i};
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int[] hit = cardAt(mx, my);
        if (hit == null) return false;
        int fy = view.ys().get(hit[0]);
        picked.put(fy, HomeDecor.PACKAGES.get(hit[1]).id());
        if (client != null && client.player != null) client.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.3f, 1.4f);
        clearAndInit();
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("FURNISH YOUR HOME"), width / 2f, top - 40, 1.3f, Ui.GOLD, true);
        if (view == null) return;
        if (view.names().isEmpty()) {
            Ui.text(c, Text.literal("No open floors to furnish here"), width / 2f, top, 0.9f, Ui.MUTED, true);
            return;
        }
        int[] hov = cardAt(mouseX, mouseY);
        for (int r = 0; r < view.names().size(); r++) {
            int y = top + r * rowH, fy = view.ys().get(r);
            String cur = view.current().get(r), pick = picked.getOrDefault(fy, "");
            Ui.text(c, Ui.heading(view.names().get(r)), left, y + 10, 1f, Ui.CREAM, false);
            HomeDecor.Package now = HomeDecor.find(cur);
            if (now != null) Ui.text(c, Text.literal(now.name()), left, y + 24, 0.75f, 0xFF7FD06A, false);
            for (int i = 0; i < HomeDecor.PACKAGES.size(); i++) {
                HomeDecor.Package pk = HomeDecor.PACKAGES.get(i);
                int x = left + label + i * (cw + 4);
                boolean on = pk.id().equals(cur), sel = pk.id().equals(pick), over = hov != null && hov[0] == r && hov[1] == i;
                c.fill(x, y, x + cw, y + ch, sel ? 0xC0302418 : 0xA0121010);
                c.drawBorder(x, y, cw, ch, sel ? Ui.GOLD : on ? 0xFF7FD06A : over ? 0xA0FFFFFF : 0x40FFFFFF);
                ItemStack icon = new ItemStack(Registries.ITEM.get(Identifier.of(pk.icon())));
                Ui.item(c, icon, x + cw / 2 - 12, y + 6, 1.5f);
                String nm = pk.name();
                while (textRenderer.getWidth(nm) * 0.6f > cw - 4 && nm.length() > 4) nm = nm.substring(0, nm.length() - 2);
                Ui.text(c, Text.literal(nm), x + cw / 2f, y + ch - 14, 0.6f, on ? 0xFF7FD06A : 0xFFE8E0D0, true);
                if (on) c.fill(x + 2, y + 2, x + 6, y + 6, 0xFF7FD06A);
            }
        }
        if (hov != null) {
            HomeDecor.Package pk = HomeDecor.PACKAGES.get(hov[1]);
            c.drawTooltip(textRenderer, java.util.List.of(Text.literal(pk.name()).withColor(Ui.GOLD & 0xFFFFFF), Text.literal(pk.blurb())), mouseX, mouseY);
        }
        String purse = String.format(Locale.ROOT, "%,d Marks", ClientState.marks);
        Ui.text(c, Text.literal(purse), width - 40 - textRenderer.getWidth(purse) * 0.8f, 16, 0.8f, Ui.GOLD, false);
    }

    private long lastMarks = -1;

    @Override
    public void tick() {
        if (lastMarks != ClientState.marks) {
            lastMarks = ClientState.marks;
            clearAndInit();
        }
    }
}
