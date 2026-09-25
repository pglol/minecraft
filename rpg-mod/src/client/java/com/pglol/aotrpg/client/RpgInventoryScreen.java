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
        backgroundWidth = 176;
        backgroundHeight = 212;
    }

    // Loadout layout inside the panel (frame top-left of each slot). Hotbar 0-8 and the off hand (40).
    private static final int OFFHAND = 40;
    private static final int[][] FRAMES = new int[41][];
    static {
        FRAMES[0] = new int[] {29, 97};          // Melee
        FRAMES[OFFHAND] = new int[] {49, 97};    // Off hand / twin grip
        FRAMES[1] = new int[] {109, 97};         // Ranged
        FRAMES[2] = new int[] {129, 97};         // Sidearm
        FRAMES[3] = new int[] {49, 123};         // Tool
        FRAMES[4] = new int[] {79, 123};         // Heal (centre)
        FRAMES[5] = new int[] {109, 123};        // Mount
        FRAMES[6] = new int[] {49, 146};         // Signal
        FRAMES[7] = new int[] {79, 146};         // Free
        FRAMES[8] = new int[] {109, 146};        // Free
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
            else if (armorSlot(s)) move(s, 8, 8 + (39 - s.getIndex()) * 18);
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
        y = (height - backgroundHeight) / 2 + 6;
        satchelButton = addDrawableChild(new AotButton(x + 8, y + 170, 76, 14, Text.literal("Satchel [B]"),
            () -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Net.OpenSatchel())));
        skillsButton = addDrawableChild(new AotButton(x + backgroundWidth - 84, y + 170, 76, 14, Text.literal("Skills [K]"),
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
    }

    @Override
    protected void drawBackground(DrawContext c, float delta, int mouseX, int mouseY) {
        int x = this.x, y = this.y, w = backgroundWidth, h = backgroundHeight;
        Net.Sync p = ClientState.profile;

        // Header: level crest on the left, name in the middle, purse on the right.
        Ui.panel(c, x, y - 19, 34, 17);
        Ui.crest(c, x + 5, y - 19, 17, 0.35f);
        if (p != null) Ui.text(c, Ui.title(String.valueOf(p.level())), x + 17, y - 15, 1f, Ui.GOLD, true);
        int tabW = 104, tabX = x + 38;
        Ui.panel(c, tabX, y - 19, tabW, 17);
        Text head = Ui.heading(p != null ? p.name() : "Inventory");
        c.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(head.getString(), tabW - 8)).setStyle(head.getStyle()),
            tabX + 4, y - 14, Ui.GOLD);
        Text purse = Text.literal(String.format(Locale.ROOT, "%,d", ClientState.marks)).withColor(0xFFE0B96A);
        int pw = Math.max(30, textRenderer.getWidth(purse) + 10);
        Ui.panel(c, x + w - pw, y - 19, pw, 17);
        c.drawTextWithShadow(textRenderer, purse, x + w - pw + 5, y - 14, Ui.GOLD);

        Ui.panel(c, x, y, w, h);
        // Your figure between the armor column and the armor rating.
        c.fillGradient(x + 28, y + 4, x + w - 28, y + 82, 0x30B8955A, 0x08000000);
        Ui.crest(c, x + w / 2 - 26, y + 14, 52, 0.12f);
        InventoryScreen.drawEntity(c, x + 30, y + 4, x + w - 30, y + 82, 32, 0.0625f, mouseXf, mouseYf, client.player);
        c.fill(x + 4, y + 8, x + 5, y + 78, 0x80B8955A);
        for (Slot s : handler.slots) if (armorSlot(s)) Ui.slot(c, x + s.x - 1, y + s.y - 1);
        if (p != null) {
            Ui.text(c, Text.literal(p.disciplineEnum().title), x + w - 16, y + 70, 0.7f, Ui.disciplineColor(p.discipline()), true);
        }
        Ui.divider(c, x + 8, y + 84, w - 16);

        Ui.text(c, Ui.heading("Melee"), x + 47, y + 88, 0.8f, LoadoutUi.color(com.pglol.aotrpg.Loadout.Kind.MELEE), true);
        Ui.text(c, Ui.heading("Ranged"), x + 129, y + 88, 0.8f, LoadoutUi.color(com.pglol.aotrpg.Loadout.Kind.RANGED), true);
        // Shield with the armor value.
        int sx = x + w / 2 - 14, sy = y + 90;
        c.fill(sx, sy, sx + 28, sy + 20, 0xF0151A16);
        LoadoutUi.chevronDown(c, sx, sy + 20, 28, 0xF0151A16);
        c.drawBorder(sx, sy, 28, 20, Ui.TRIM);
        Ui.text(c, Ui.heading("AR"), sx + 14, sy + 3, 0.6f, Ui.MUTED, true);
        Ui.text(c, Ui.title(String.valueOf(client.player.getArmor())), sx + 14, sy + 10, 1.1f, Ui.CREAM, true);
        // Sheath state under the melee pair.
        Net.SheathState st = ClientState.sheaths.get(client.player.getUuid());
        String key = AotRpgClient.sheathKey().getBoundKeyLocalizedText().getString();
        String state = com.pglol.aotrpg.Loadout.isGrip(client.player.getOffHandStack()) ? "Drawn · " + key
            : st != null && st.count() > 0 ? "Sheathed · " + key : "";
        if (!state.isEmpty()) Ui.text(c, Text.literal(state), x + 29, y + 118, 0.55f, state.startsWith("Drawn") ? Ui.RED : Ui.GOLD, false);

        for (Slot s : handler.slots) {
            if (!playerSlot(s)) continue;
            int fx = x + s.x - 1, fy = y + s.y - 1;
            if (s.getIndex() == OFFHAND) {
                Ui.slot(c, fx, fy);
                continue;
            }
            com.pglol.aotrpg.Loadout.Kind k = com.pglol.aotrpg.Loadout.SLOTS[s.getIndex()];
            int col = LoadoutUi.color(k);
            boolean heal = s.getIndex() == com.pglol.aotrpg.Loadout.HEAL_SLOT;
            Ui.slot(c, fx, fy);
            if (heal) c.drawBorder(fx - 2, fy - 2, 22, 22, Ui.TRIM);
            c.drawBorder(fx, fy, 18, 18, (heal ? 0xFF : 0xA0) << 24 | (col & 0xFFFFFF));
            c.fill(fx + 1, fy + 16, fx + 17, fy + 17, 0xC0000000 | (col & 0xFFFFFF));
            if (!s.hasStack()) LoadoutUi.drawGhost(c, LoadoutUi.ghost(k), fx + 1, fy + 1);
        }
        if (st != null && st.count() > 0 && client.player.getInventory().main.get(0).isEmpty()) {
            ItemStack g = st.a().isEmpty() ? st.b() : st.a();
            LoadoutUi.drawGhost(c, g, x + FRAMES[0][0] + 1, y + FRAMES[0][1] + 1);
        }
        c.fill(x + 8, y + 166, x + w - 8, y + 167, 0x407A6139);

        // Stats along the bottom.
        var pl = client.player;
        c.fill(x + 7, y + 187, x + w - 7, y + 207, 0x40000000);
        stat(c, x + 12, y + 190, "Health", Math.round(pl.getHealth()) + "/" + Math.round(pl.getMaxHealth()), Ui.HP);
        stat(c, x + 92, y + 190, "Damage", String.format(Locale.ROOT, "%.1f", pl.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE)), Ui.CREAM);
        stat(c, x + 12, y + 199, "Speed", Math.round(pl.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) / 0.1 * 100) + "%", Ui.CREAM);
        stat(c, x + 92, y + 199, "Food", pl.getHungerManager().getFoodLevel() + "/20",
            pl.getHungerManager().getFoodLevel() <= 6 ? Ui.RED : Ui.FOOD);
    }

    private void stat(DrawContext c, int sx, int sy, String k, String v, int color) {
        Ui.text(c, Text.literal(k), sx, sy, 0.75f, Ui.MUTED, false);
        Ui.text(c, Text.literal(v), sx + 72 - textRenderer.getWidth(v) * 0.75f, sy, 0.75f, color, false);
    }

    @Override
    protected void drawForeground(DrawContext c, int mouseX, int mouseY) {
    }

    @Override
    protected boolean isClickOutsideBounds(double mouseX, double mouseY, int left, int top, int button) {
        return mouseX < left || mouseY < top - 20 || mouseX >= left + backgroundWidth || mouseY >= top + backgroundHeight;
    }
}
