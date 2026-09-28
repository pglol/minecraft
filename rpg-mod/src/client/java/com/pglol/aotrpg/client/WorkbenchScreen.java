package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.Locale;

/** The balloon's workbench: field kit for the next run, a card per item, bought with Marks and Salvage. */
public final class WorkbenchScreen extends Screen {
    private static Net.BenchView v;
    private final long opened = Util.getMeasuringTimeMs();
    private int left, top, w, h;
    private static final int CARD_W = 96, CARD_H = 92;

    public WorkbenchScreen(Net.BenchView view) {
        super(Text.literal("Workbench"));
        v = view;
    }

    public static void update(Net.BenchView view) {
        v = view;
        if (MinecraftClient.getInstance().currentScreen instanceof WorkbenchScreen s) s.clearAndInit();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static ItemStack icon(String id) {
        var item = Registries.ITEM.get(Identifier.of(id));
        return new ItemStack(item == Items.AIR ? Items.CRAFTING_TABLE : item);
    }

    @Override
    protected void init() {
        int cols = 3, rows = (v.recipes().size() + cols - 1) / cols;
        w = cols * (CARD_W + 8) + 24;
        h = rows * (CARD_H + 8) + 56;
        left = (width - w) / 2;
        top = Math.max(10, (height - h) / 2);
        for (int i = 0; i < v.recipes().size(); i++) {
            Net.BenchRecipe r = v.recipes().get(i);
            int x = left + 12 + (i % cols) * (CARD_W + 8), y = top + 44 + (i / cols) * (CARD_H + 8);
            AotButton b = addDrawableChild(new AotButton(x + 8, y + CARD_H - 24, CARD_W - 16, 18, Ui.heading("Craft"),
                () -> ClientPlayNetworking.send(new Net.ExtractionAction("bench", r.id()))));
            b.active = r.ok();
            b.textScale = 0.85f;
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0x90000000, 0xC0000000);
        float k = Math.min(1, (Util.getMeasuringTimeMs() - opened) / 200f);
        int slide = (int) ((1 - k) * 12);
        Ui.panel(c, left, top + slide, w, h);
        Ui.text(c, Ui.title("WORKBENCH"), left + 14, top + 12 + slide, 1.4f, Ui.GOLD, false);
        String money = String.format(Locale.ROOT, "%,d M   %,d Salvage", v.marks(), v.salvage());
        Ui.text(c, Text.literal(money), left + w - 14 - Ui.font().getWidth(money) * 0.85f, top + 16 + slide, 0.85f, Ui.CREAM, false);
        long t = Util.getMeasuringTimeMs();
        for (int i = 0; i < v.recipes().size(); i++) {
            Net.BenchRecipe r = v.recipes().get(i);
            int x = left + 12 + (i % 3) * (CARD_W + 8), y = top + 44 + (i / 3) * (CARD_H + 8) + slide;
            boolean hov = mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + CARD_H;
            c.fill(x, y, x + CARD_W, y + CARD_H, hov ? 0xE0221E16 : 0xD0141210);
            c.drawBorder(x, y, CARD_W, CARD_H, r.ok() ? (hov ? Ui.GOLD : Ui.BORDER) : 0xFF34322C);
            // The item, bobbing gently when you can make it.
            float bob = r.ok() ? (float) Math.sin(t * 0.004 + i) * 1.5f : 0;
            Ui.item(c, icon(r.icon()), x + CARD_W / 2 - 16, (int) (y + 8 + bob), 2f);
            if (r.count() > 1) Ui.text(c, Text.literal("x" + r.count()), x + CARD_W / 2f + 16, y + 30, 0.8f, Ui.CREAM, false);
            Ui.text(c, Text.literal(r.title()), x + CARD_W / 2f, y + 45, 0.8f, r.ok() ? Ui.CREAM : Ui.DIM, true);
            String price = (r.marks() > 0 ? String.format(Locale.ROOT, "%,d M", r.marks()) : "")
                + (r.marks() > 0 && r.salvage() > 0 ? " + " : "") + (r.salvage() > 0 ? r.salvage() + " S" : "");
            Ui.text(c, Text.literal(price), x + CARD_W / 2f, y + 56, 0.75f, r.salvage() > 0 ? 0xFF9AD0FF : Ui.GOLD, true);
        }
    }
}
