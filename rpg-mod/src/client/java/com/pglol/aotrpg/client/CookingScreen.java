package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Cooking;
import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

/** Campfire cooking: pick a recipe, see what you have, cook. */
public class CookingScreen extends Screen {
    private List<Net.RecipeStatus> recipes;
    private int selected;
    private int left, top, w, h, listW;

    public CookingScreen(List<Net.RecipeStatus> recipes) {
        super(Text.literal("Campfire"));
        this.recipes = recipes;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void update(List<Net.RecipeStatus> recipes) {
        this.recipes = recipes;
        clearAndInit();
    }

    private Net.RecipeStatus status(int recipe) {
        for (Net.RecipeStatus r : recipes) if (r.recipe() == recipe) return r;
        return null;
    }

    @Override
    protected void init() {
        w = Math.min(420, width - 20);
        left = (width - w) / 2;
        top = 44;
        h = Math.min(Cooking.Recipe.values().length * 26 + 10, height - top - 10);
        listW = Math.min(190, w / 2);
        int y = top + 5;
        int step = Math.min(26, (h - 10) / Cooking.Recipe.values().length);
        for (Cooking.Recipe r : Cooking.Recipe.values()) {
            Net.RecipeStatus st = status(r.ordinal());
            int can = st == null ? 0 : st.craftable();
            AotButton b = new AotButton(left + 5, y, listW - 10, step - 3, Text.literal(r.title), () -> {
                selected = r.ordinal();
                clearAndInit();
            }).icon(new ItemStack(r.base)).sub(Text.literal(can > 0 ? "Can cook " + can : "Missing ingredients"))
                .selected(selected == r.ordinal());
            addDrawableChild(b);
            y += step;
        }
        Cooking.Recipe r = Cooking.Recipe.values()[selected];
        Net.RecipeStatus st = status(r.ordinal());
        int can = st == null ? 0 : st.craftable();
        int bx = left + listW + 10, bw = (w - listW - 30) / 2;
        AotButton one = new AotButton(bx, top + h - 28, bw, 20, Ui.heading("Cook"), () -> cook(r.ordinal(), 1));
        AotButton all = new AotButton(bx + bw + 10, top + h - 28, bw, 20, Ui.heading("Cook all" + (can > 1 ? " (" + Math.min(16, can) + ")" : "")),
            () -> cook(r.ordinal(), 16));
        one.active = can > 0;
        all.active = can > 1;
        addDrawableChild(one);
        addDrawableChild(all);
    }

    /** One quick minigame per batch: the result sets the meal quality. */
    private void cook(int recipe, int times) {
        client.setScreen(new MinigameScreen(MinigameScreen.Kind.STRIKE, "Cooking", "Flip at the right moment: Space or click, three times", 0.3f, q -> {
            ClientPlayNetworking.send(new Net.Cook(recipe, times, q));
            client.setScreen(this);
        }).sound(net.minecraft.sound.SoundEvents.BLOCK_CAMPFIRE_CRACKLE));
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        c.fillGradient(0, height / 2, width, height, 0x00000000, 0x40B0401A);
        Ui.text(c, Ui.title("CAMPFIRE"), width / 2f, 10, 2f, Ui.GOLD, true);
        c.drawCenteredTextWithShadow(textRenderer, Text.literal("Cook with supplies from your satchel and inventory"), width / 2, 30, Ui.MUTED);

        Ui.panel(c, left, top, listW, h);
        int dx = left + listW + 5, dw = w - listW - 5;
        Ui.panel(c, dx, top, dw, h);
        Cooking.Recipe r = Cooking.Recipe.values()[selected];
        Net.RecipeStatus st = status(r.ordinal());
        Ui.item(c, new ItemStack(r.base), dx + 8, top + 8, 2f);
        Ui.text(c, Ui.heading(r.title), dx + 44, top + 12, 1.1f, Ui.GOLD, false);
        c.drawTextWithShadow(textRenderer, Text.literal("+" + r.nutrition + " hunger"), dx + 44, top + 26, Ui.CREAM);
        Ui.divider(c, dx + 8, top + 44, dw - 16);
        int y = top + 52;
        c.drawTextWithShadow(textRenderer, Ui.heading("Ingredients"), dx + 8, y, Ui.GOLD);
        y += 12;
        for (int i = 0; i < r.ingredients.size(); i++) {
            Cooking.Ingredient ing = r.ingredients.get(i);
            int have = st == null || i >= st.have().length ? 0 : st.have()[i];
            c.drawItem(new ItemStack(ing.group().icon), dx + 8, y - 4);
            c.drawTextWithShadow(textRenderer, Text.literal(ing.count() + "x " + ing.group().title), dx + 28, y, Ui.CREAM);
            String hv = "have " + have;
            c.drawTextWithShadow(textRenderer, Text.literal(hv), dx + dw - 8 - textRenderer.getWidth(hv), y, have >= ing.count() ? 0xFF8FCB6A : Ui.RED);
            y += 18;
        }
        y += 4;
        c.drawTextWithShadow(textRenderer, Ui.heading("Effects"), dx + 8, y, Ui.GOLD);
        Ui.wrapped(c, Text.literal(r.buffText()), dx + 8, y + 12, dw - 16, 0xFF8FCB6A);
    }
}
