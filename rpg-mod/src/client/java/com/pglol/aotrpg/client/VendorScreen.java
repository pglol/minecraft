package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.Locale;

/** A trader's stall: today's stock as cards, hover for the details, buy with Marks. */
public final class VendorScreen extends Screen {
    private static Net.VendorView view;
    private static final int CW = 150, CH = 58, GAP = 8;

    public VendorScreen() {
        super(Text.literal("Trader"));
    }

    public static void on(Net.VendorView v) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof VendorScreen s) {
            view = v;
            s.clearAndInit();
        } else if (v.open()) {
            view = v;
            mc.setScreen(new VendorScreen());
        }
    }

    private int cols() { return Math.max(1, Math.min(4, (width - 40 + GAP) / (CW + GAP))); }
    private int gx() { return width / 2 - (cols() * CW + (cols() - 1) * GAP) / 2; }
    private int gy() { return Math.max(60, height / 2 - ((view.items().size() + cols() - 1) / cols() * (CH + GAP)) / 2 + 10); }

    @Override
    protected void init() {
        addDrawableChild(new AotButton(width - 30, 10, 20, 20, Text.literal("✕"), this::close));
        if (view == null) return;
        int cols = cols();
        for (int i = 0; i < view.items().size(); i++) {
            ItemStack s = view.items().get(i);
            int x = gx() + (i % cols) * (CW + GAP), y = gy() + (i / cols) * (CH + GAP);
            long price = view.prices().get(i);
            int idx = i;
            AotButton b = addDrawableChild(new AotButton(x + CW - 76, y + CH - 20, 70, 15,
                Text.literal(s.isEmpty() ? "Sold" : String.format(Locale.ROOT, "%,d M", price)),
                () -> ClientPlayNetworking.send(new Net.VendorBuy(view.entity(), idx))));
            b.active = !s.isEmpty() && ClientState.marks >= price;
            if (view.shady()) b.accent = 0xFF8A2A2A;
        }
    }

    private long lastMarks = -1;

    @Override
    public void tick() {
        if (lastMarks != ClientState.marks) {
            lastMarks = ClientState.marks;
            clearAndInit();
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        var e = view == null || mc.world == null ? null : mc.world.getEntityById(view.entity());
        if (e == null || mc.player == null || e.squaredDistanceTo(mc.player) > 10 * 10) close();
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        if (view == null) return;
        int top = gy();
        Ui.text(c, Ui.title(view.name().toUpperCase(Locale.ROOT)), width / 2f, top - 44, 1.3f, view.shady() ? 0xFFB04A4A : Ui.GOLD, true);
        Ui.text(c, Text.literal(view.sub()), width / 2f, top - 26, 0.8f, Ui.CREAM, true);
        String purse = String.format(Locale.ROOT, "%,d Marks", ClientState.marks);
        Ui.text(c, Text.literal(purse), width - 40 - Ui.font().getWidth(purse) * 0.8f, 16, 0.8f, Ui.GOLD, false);
        int cols = cols();
        for (int i = 0; i < view.items().size(); i++) {
            ItemStack s = view.items().get(i);
            int x = gx() + (i % cols) * (CW + GAP), y = gy() + (i / cols) * (CH + GAP);
            Ui.panel(c, x, y, CW, CH);
            if (s.isEmpty()) {
                Ui.text(c, Ui.heading("Sold"), x + CW / 2f, y + 16, 0.9f, Ui.DIM, true);
                continue;
            }
            int tone = Ui.rarityTone(Math.max(0, GearUi.rarity(s)));
            c.fill(x + 3, y + 3, x + 5, y + CH - 3, GearUi.rarity(s) >= 0 ? tone : Ui.GOLD);
            GearUi.backing(c, s, x + 9, y + 8);
            Ui.item(c, s, x + 8, y + 8, 1.5f);
            String name = s.getName().getString();
            while (Ui.font().getWidth(name) * 0.75f > CW - 46 && name.length() > 4) name = name.substring(0, name.length() - 2);
            Ui.text(c, Text.literal(name).setStyle(s.getName().getStyle()), x + 38, y + 9, 0.75f, 0xFFFFFFFF, false);
            if (s.getCount() > 1) Ui.text(c, Text.literal("x" + s.getCount()), x + 38, y + 21, 0.7f, Ui.CREAM, false);
            else if (GearUi.rarity(s) >= 0) Ui.text(c, Text.literal("Lv " + com.pglol.aotrpg.Gear.requiredLevel(s)), x + 38, y + 21, 0.7f, Ui.CREAM, false);
        }
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        if (view == null) return;
        int cols = cols();
        for (int i = 0; i < view.items().size(); i++) {
            ItemStack s = view.items().get(i);
            int x = gx() + (i % cols) * (CW + GAP), y = gy() + (i / cols) * (CH + GAP);
            if (!s.isEmpty() && mouseX >= x && mouseX < x + CW - 80 && mouseY >= y && mouseY < y + CH) c.drawItemTooltip(textRenderer, s, mouseX, mouseY);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
