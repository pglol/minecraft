package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Survival inventory in the AoT style: the same slots on cloth panels with recessed slots, a header
 * tab, the crest behind your level, and a character panel whose loadout is made of the real hotbar slots.
 */
public class RpgInventoryScreen extends InventoryScreen {
    private float mouseXf, mouseYf;
    private AotButton satchelButton, skillsButton;

    public RpgInventoryScreen(PlayerEntity player) {
        super(player);
        backgroundWidth = W;
        backgroundHeight = H;
    }

    // Loadout layout inside the panel (frame top-left of each slot). Hotbar 0-8 and the off hand (40).
    // One leather case, two columns: you and your armour on the left, the loadout on the right.
    // Sized to fit GUI scale 2 to 4 on common screens without anything overlapping.
    private static final int W = 312, H = 172, RIGHT_C = 233;
    private static final int OFFHAND = 40;
    private static final int[][] FRAMES = new int[41][];
    static {
        FRAMES[0] = new int[] {172, 38};         // Melee
        FRAMES[OFFHAND] = new int[] {192, 38};   // Off hand / twin grip
        FRAMES[1] = new int[] {256, 38};         // Ranged
        FRAMES[2] = new int[] {276, 38};         // Free
        FRAMES[3] = new int[] {198, 68};         // Free
        FRAMES[4] = new int[] {224, 68};         // Heal (centre)
        FRAMES[5] = new int[] {250, 68};         // Tool
        FRAMES[6] = new int[] {198, 94};         // Signal
        FRAMES[7] = new int[] {224, 94};         // Mount
        FRAMES[8] = new int[] {250, 94};         // Gas
    }

    /** Vanilla positions of every slot, to put back when the screen closes. */
    private final java.util.Map<Slot, int[]> original = new java.util.HashMap<>();

    private static boolean playerSlot(Slot s) {
        return s.inventory instanceof PlayerInventory && (s.getIndex() < 9 || s.getIndex() == OFFHAND);
    }

    private static boolean armorSlot(Slot s) {
        return s.inventory instanceof PlayerInventory && s.getIndex() >= 36 && s.getIndex() <= 39;
    }

    private static void move(Slot s, int x, int y) {
        ((com.pglol.aotrpg.client.mixin.SlotAccessor) s).aotrpg$setX(x);
        ((com.pglol.aotrpg.client.mixin.SlotAccessor) s).aotrpg$setY(y);
    }

    /**
     * No inventory rows and no crafting grid any more (everything you carry lives in the satchel):
     * armor sits beside your figure and the hotbar is the loadout; every other slot is put away.
     */
    private void layout() {
        for (Slot s : handler.slots) {
            original.putIfAbsent(s, new int[] {s.x, s.y});
            if (playerSlot(s)) move(s, FRAMES[s.getIndex()][0] + 1, FRAMES[s.getIndex()][1] + 1);
            else if (armorSlot(s)) move(s, 11, 30 + (39 - s.getIndex()) * 20);
            else move(s, -10000, -10000);
        }
    }

    @Override
    public void removed() {
        // The slots belong to the player's own inventory handler: put them back.
        for (var e : original.entrySet()) move(e.getKey(), e.getValue()[0], e.getValue()[1]);
        super.removed();
    }

    @Override
    protected void init() {
        // The recipe book has no place here (crafting is done at the forge and by vendors).
        if (client.player != null) client.player.getRecipeBook().setGuiOpen(net.minecraft.recipe.book.RecipeBookCategory.CRAFTING, false);
        super.init();
        for (var el : new java.util.ArrayList<>(children())) {
            if (el instanceof net.minecraft.client.gui.widget.TexturedButtonWidget b) remove(b);
        }
        x = (width - backgroundWidth) / 2;
        y = (height - backgroundHeight) / 2;
        satchelButton = addDrawableChild(new AotButton(x + 170, y + 146, 62, 16, Text.literal("Satchel [B]"),
            () -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Net.OpenSatchel())));
        skillsButton = addDrawableChild(new AotButton(x + 236, y + 146, 64, 16, Text.literal("Skills [K]"),
            () -> client.setScreen(new CharacterScreen(0))));
        layout();
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        mouseXf = mouseX;
        mouseYf = mouseY;
        Net.Sync p = ClientState.profile;
        skillsButton.selected = p != null && p.points() + p.skillPoints() > 0;
        super.render(c, mouseX, mouseY, delta);
        // What an empty loadout slot is for.
        Slot f = focusedSlot;
        if (f != null && playerSlot(f) && !f.hasStack() && handler.getCursorStack().isEmpty()) {
            java.util.List<Text> tip;
            if (f.getIndex() == OFFHAND) {
                tip = java.util.List.of(Text.literal("Off hand").withColor(Ui.GOLD),
                    Text.literal("Your twin ODM grip when drawn [" + AotRpgClient.sheathKey().getBoundKeyLocalizedText().getString() + "]").withColor(Ui.MUTED));
            } else {
                com.pglol.aotrpg.Loadout.Kind k = com.pglol.aotrpg.Loadout.SLOTS[f.getIndex()];
                tip = java.util.List.of(Text.literal(k.title + "  ·  " + (f.getIndex() + 1)).withColor(LoadoutUi.color(k)),
                    Text.literal(k.hint).withColor(Ui.MUTED));
            }
            c.drawTooltip(textRenderer, tip, mouseX, mouseY);
        }
        // The satchel button accepts an item dropped on it from the cursor.
        if (!handler.getCursorStack().isEmpty() && satchelButton.isMouseOver(mouseX, mouseY)) {
            c.drawTooltip(textRenderer, Text.literal("Put back in the satchel").withColor(Ui.GOLD), mouseX, mouseY);
        }
    }

    @Override
    protected void drawBackground(DrawContext c, float delta, int mouseX, int mouseY) {
        int x = this.x, y = this.y, w = backgroundWidth, h = backgroundHeight;
        Net.Sync p = ClientState.profile;
        var pl = client.player;
        var font = textRenderer;
        Ui.leather(c, x, y, w, h);

        // Header: level in a small well, name and role, the purse on the right. All centred on one line.
        int hy = y + 7;
        String lv = p != null ? String.valueOf(p.level()) : "-";
        int lw = Math.max(16, font.getWidth(lv) + 8);
        Ui.well(c, x + 8, y + 4, lw, 14);
        c.drawText(font, lv, x + 8 + (lw - font.getWidth(lv)) / 2, hy, Ui.GOLD, false);
        int nx = x + 8 + lw + 6;
        Text name = Ui.heading(p != null ? p.name() : "Inventory");
        c.drawText(font, name, nx, hy, Ui.CREAM, false);
        nx += font.getWidth(name) + 6;
        if (p != null) c.drawText(font, Text.literal(p.role().tag() + " " + p.role().title), nx, hy, p.role().color, false);
        String purse = String.format(Locale.ROOT, "%,d", ClientState.marks);
        int px = x + w - 10 - font.getWidth(purse);
        c.drawText(font, purse, px, hy, Ui.GOLD, false);
        Glyphs.draw(c, px - 10, hy, Glyphs.COIN, 0xFF8C6A2E, 0xFFE0B96A);
        c.fill(x + 6, y + 21, x + w - 6, y + 22, 0x70000000);
        c.fill(x + 6, y + 22, x + w - 6, y + 23, 0x18FFE8C0);

        // Left: armour down the side, your figure beside it, your numbers below.
        for (Slot s : handler.slots) if (armorSlot(s)) Ui.socket(c, x + s.x - 1, y + s.y - 1, 18);
        Ui.well(c, x + 34, y + 28, 116, 80);
        c.fillGradient(x + 35, y + 29, x + 149, y + 107, 0x18E0B96A, 0x00000000);
        Ui.crest(c, x + 92 - 26, y + 42, 52, 0.08f);
        InventoryScreen.drawEntity(c, x + 36, y + 30, x + 148, y + 106, 34, 0.0625f, mouseXf, mouseYf, pl);

        Ui.well(c, x + 10, y + 114, 140, 50);
        int food = pl.getHungerManager().getFoodLevel();
        float st = ClientState.stamina < 0 ? ClientState.maxStamina : ClientState.stamina;
        stat(c, x + 16, x + 76, y + 120, "Health", pl.getMaxHealth() >= 100 ? String.valueOf(Math.round(pl.getHealth())) : Math.round(pl.getHealth()) + "/" + Math.round(pl.getMaxHealth()), 0xFFD06A5A);
        stat(c, x + 84, x + 144, y + 120, "Armour", String.valueOf(pl.getArmor()), Ui.CREAM);
        stat(c, x + 16, x + 76, y + 134, "Damage", String.format(Locale.ROOT, "%.1f", pl.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE)), Ui.CREAM);
        stat(c, x + 84, x + 144, y + 134, "Speed", Math.round(pl.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) / 0.1 * 100) + "%", Ui.CREAM);
        stat(c, x + 16, x + 76, y + 148, "Food", food + "/20", food <= 6 ? Ui.RED : 0xFFC9A15A);
        stat(c, x + 84, x + 144, y + 148, "Stamina", String.valueOf(Math.round(st)), 0xFFA2BC8C);

        c.fill(x + 157, y + 28, x + 158, y + 164, 0x60000000);
        c.fill(x + 158, y + 28, x + 159, y + 164, 0x14FFE8C0);

        // Right: the loadout, named above each group.
        centre(c, Ui.heading("Melee"), x + 191, y + 27, LoadoutUi.color(com.pglol.aotrpg.Loadout.Kind.MELEE));
        centre(c, Ui.heading("Ranged"), x + 275, y + 27, LoadoutUi.color(com.pglol.aotrpg.Loadout.Kind.RANGED));
        Net.SheathState sh = ClientState.sheaths.get(pl.getUuid());
        for (Slot s : handler.slots) {
            if (!playerSlot(s)) continue;
            int fx = x + s.x - 1, fy = y + s.y - 1;
            Ui.socket(c, fx, fy, 18);
            if (s.getIndex() == OFFHAND) continue;
            com.pglol.aotrpg.Loadout.Kind k = com.pglol.aotrpg.Loadout.SLOTS[s.getIndex()];
            int col = LoadoutUi.color(k);
            if (s.getIndex() == com.pglol.aotrpg.Loadout.HEAL_SLOT) c.drawBorder(fx - 2, fy - 2, 22, 22, 0xFF8C7248);
            c.fill(fx + 2, fy + 16, fx + 16, fy + 17, 0xA0000000 | (col & 0xFFFFFF));
            if (!s.hasStack()) LoadoutUi.drawGhost(c, LoadoutUi.ghost(k), fx + 1, fy + 1);
        }
        // Sheathed grips still show where they'd be drawn to: the pair, in their rarity, dimmed, with a sheath mark.
        if (sh != null && sh.count() > 0 && pl.getInventory().main.get(0).isEmpty()) {
            ItemStack g = sh.a().isEmpty() ? sh.b() : sh.a(), g2 = sh.a().isEmpty() ? ItemStack.EMPTY : sh.b();
            sheathed(c, g, x + FRAMES[0][0] + 1, y + FRAMES[0][1] + 1);
            if (!g2.isEmpty() && pl.getOffHandStack().isEmpty()) {
                for (Slot s2 : handler.slots) {
                    if (playerSlot(s2) && s2.getIndex() == OFFHAND) sheathed(c, g2, x + s2.x, y + s2.y);
                }
            }
        }
        String key = AotRpgClient.sheathKey().getBoundKeyLocalizedText().getString();
        boolean drawn = com.pglol.aotrpg.Loadout.isGrip(pl.getOffHandStack());
        String state = drawn ? "Blades drawn  ·  " + key : sh != null && sh.count() > 0 ? "Blades sheathed  ·  " + key : "";
        if (!state.isEmpty()) centre(c, Text.literal(state), x + RIGHT_C, y + 118, drawn ? 0xFFD06A5A : Ui.GOLD);
        Ui.text(c, Text.literal("Shift-click or Q puts it back in the satchel"), x + RIGHT_C, y + 131, 0.7f, Ui.MUTED, true);
    }

    private void sheathed(DrawContext c, ItemStack g, int ix, int iy) {
        GearUi.backing(c, g, ix, iy);
        c.drawItem(g, ix, iy);
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 200);
        c.fill(ix, iy, ix + 16, iy + 16, 0x50000000);
        Ui.text(c, Text.literal("\u2694"), ix + 12, iy + 9, 0.6f, Ui.GOLD, true);
        c.getMatrices().pop();
    }

    private void centre(DrawContext c, Text t, int cx, int ty, int color) {
        c.drawText(textRenderer, t, cx - textRenderer.getWidth(t) / 2, ty, color, false);
    }

    /** A label on the left of its cell and the value against the right. */
    private void stat(DrawContext c, int lx, int rx, int sy, String k, String v, int color) {
        c.drawText(textRenderer, k, lx, sy, Ui.MUTED, false);
        c.drawText(textRenderer, v, rx - textRenderer.getWidth(v), sy, color, false);
    }

    @Override
    protected void drawForeground(DrawContext c, int mouseX, int mouseY) {
    }

    /** Where a slot is, as the satchel names it: loadout 0-8, off hand, or armor (100 feet .. 103 head). */
    private static int place(Slot s) {
        if (playerSlot(s)) return s.getIndex() == OFFHAND ? com.pglol.aotrpg.Satchel.OFF : s.getIndex();
        if (armorSlot(s)) return com.pglol.aotrpg.Satchel.ARMOR + (s.getIndex() - 36);
        return -1;
    }

    private static void store(Slot s) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Net.BagAction("store", -1, place(s)));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // Shift-click (or middle-click) anything you wear or carry to send it back to the satchel.
        if (!handler.getCursorStack().isEmpty() && satchelButton.isMouseOver(mx, my)) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Net.BagAction("storecursor", -1, 0));
            return true;
        }
        Slot f = focusedSlot;
        if (f != null && f.hasStack() && place(f) >= 0 && handler.getCursorStack().isEmpty()
            && ((button == 0 && hasShiftDown()) || button == 2)) {
            store(f);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // Q over a slot sends it to the satchel instead of dropping it on the ground.
        Slot f = focusedSlot;
        if (f != null && f.hasStack() && place(f) >= 0 && client.options.dropKey.matchesKey(key, scan)) {
            store(f);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    protected boolean isClickOutsideBounds(double mouseX, double mouseY, int left, int top, int button) {
        return mouseX < left || mouseY < top || mouseX >= left + backgroundWidth || mouseY >= top + backgroundHeight;
    }
}
