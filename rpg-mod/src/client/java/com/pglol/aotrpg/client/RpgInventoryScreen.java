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
    private static final int PANEL_W = 132;
    private float mouseXf, mouseYf;
    private AotButton satchelButton, skillsButton;

    public RpgInventoryScreen(PlayerEntity player) {
        super(player);
    }

    /** The recipe book pushes the inventory right; the side panel only fits when it is closed. */
    private boolean panel() {
        return x == (width - backgroundWidth) / 2 && x - PANEL_W - 4 >= 2;
    }

    // Loadout layout inside the side panel, relative to the main panel (frame top-left of each slot).
    private static final int PX = -PANEL_W - 4;
    private static final int OFFHAND = 40;
    private static final int[][] FRAMES = new int[41][];
    static {
        FRAMES[0] = new int[] {PX + 7, 75};                 // Melee
        FRAMES[OFFHAND] = new int[] {PX + 27, 75};          // Off hand / twin grip
        FRAMES[1] = new int[] {PX + PANEL_W - 45, 75};      // Ranged
        FRAMES[2] = new int[] {PX + PANEL_W - 25, 75};      // Sidearm
        FRAMES[3] = new int[] {PX + 27, 101};               // Tool
        FRAMES[4] = new int[] {PX + 57, 101};               // Heal (centre)
        FRAMES[5] = new int[] {PX + 87, 101};               // Mount
        FRAMES[6] = new int[] {PX + 27, 124};               // Signal
        FRAMES[7] = new int[] {PX + 57, 124};               // Free
        FRAMES[8] = new int[] {PX + 87, 124};               // Free
    }

    private boolean laidOut;

    private static boolean playerSlot(Slot s) {
        return s.inventory instanceof PlayerInventory && (s.getIndex() < 9 || s.getIndex() == OFFHAND);
    }

    /** Moves the real hotbar and off-hand slots into the loadout, or back to where vanilla has them. */
    private void layout(boolean loadout) {
        if (loadout == laidOut) return;
        laidOut = loadout;
        for (Slot s : handler.slots) {
            if (!playerSlot(s)) continue;
            int i = s.getIndex();
            int sx, sy;
            if (loadout) {
                sx = FRAMES[i][0] + 1;
                sy = FRAMES[i][1] + 1;
            } else if (i == OFFHAND) {
                sx = 77;
                sy = 62;
            } else {
                sx = 8 + i * 18;
                sy = 142;
            }
            ((com.pglol.aotrpg.client.mixin.SlotAccessor) s).aotrpg$setX(sx);
            ((com.pglol.aotrpg.client.mixin.SlotAccessor) s).aotrpg$setY(sy);
        }
    }

    @Override
    public void removed() {
        // The slots belong to the player's own inventory handler: put them back.
        layout(false);
        super.removed();
    }

    @Override
    protected void init() {
        super.init();
        satchelButton = addDrawableChild(new AotButton(0, 0, 60, 14, Text.literal("Satchel [B]"),
            () -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Net.OpenSatchel())));
        skillsButton = addDrawableChild(new AotButton(0, 0, 60, 14, Text.literal("Skills [K]"),
            () -> client.setScreen(new CharacterScreen(0))));
        layout(panel());
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        mouseXf = mouseX;
        mouseYf = mouseY;
        boolean show = panel();
        layout(show);
        int lx = x - PANEL_W - 4;
        satchelButton.visible = skillsButton.visible = show;
        satchelButton.setPosition(lx + 4, y + backgroundHeight - 19);
        skillsButton.setPosition(lx + PANEL_W - 64, y + backgroundHeight - 19);
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
        int x = this.x, y = this.y;
        boolean side = panel();
        int left = side ? x - PANEL_W - 4 : x;
        int right = x + backgroundWidth;
        Net.Sync p = ClientState.profile;

        // Header tab across both panels
        int tabW = 150, tabX = (left + right) / 2 - tabW / 2;
        Ui.panel(c, tabX, y - 17, tabW, 15);
        Text head = Ui.heading(p != null ? p.name() + "  ·  Inventory" : "Inventory");
        c.drawTextWithShadow(textRenderer, head, tabX + (tabW - textRenderer.getWidth(head)) / 2, y - 13, Ui.GOLD);
        // Purse on the right of the header.
        Text purse = Text.literal(String.format(Locale.ROOT, "%,d", ClientState.marks)).withColor(0xFFE0B96A)
            .append(Text.literal(" Marks").withColor(Ui.MUTED));
        int pw = textRenderer.getWidth(purse) + 12;
        Ui.panel(c, right - pw, y - 17, pw, 15);
        c.drawTextWithShadow(textRenderer, purse, right - pw + 6, y - 13, Ui.GOLD);

        if (side) drawCharacterPanel(c, x - PANEL_W - 4, y);

        Ui.panel(c, x, y, backgroundWidth, backgroundHeight);
        c.fillGradient(x + 1, y + 1, x + backgroundWidth - 1, y + 40, 0x18E0B96A, 0x00000000);

        // Equipment rail next to the armour slots
        c.fill(x + 4, y + 8, x + 5, y + 78, 0x80B8955A);
        for (Slot s : handler.slots) Ui.slot(c, x + s.x - 1, y + s.y - 1);
        // Section rule above the main inventory, clear of the gear column
        c.fill(x + 8, y + 81, x + backgroundWidth - 8, y + 82, 0x607A6139);
        // Crafting arrow
        c.drawTextWithShadow(textRenderer, Text.literal("→"), x + 138, y + 32, Ui.TRIM);

        // Loadout slots: framed in their purpose colour, with a ghost of what belongs there.
        for (Slot s : handler.slots) {
            if (!playerSlot(s) || s.getIndex() == OFFHAND) continue;
            com.pglol.aotrpg.Loadout.Kind k = com.pglol.aotrpg.Loadout.SLOTS[s.getIndex()];
            int col = LoadoutUi.color(k);
            int fx = x + s.x - 1, fy = y + s.y - 1;
            boolean heal = s.getIndex() == com.pglol.aotrpg.Loadout.HEAL_SLOT;
            if (heal) c.drawBorder(fx - 2, fy - 2, 22, 22, Ui.TRIM);
            c.drawBorder(fx, fy, 18, 18, (heal ? 0xFF : 0xA0) << 24 | (col & 0xFFFFFF));
            c.fill(fx + 1, fy + 16, fx + 17, fy + 17, 0xC0000000 | (col & 0xFFFFFF));
            if (!s.hasStack()) LoadoutUi.drawGhost(c, LoadoutUi.ghost(k), fx + 1, fy + 1);
        }

        // Crest behind the level, where the small vanilla preview used to be.
        int cx = x + 51;
        Ui.crest(c, cx - 24, y + 10, 48, 0.35f);
        if (p != null) {
            Ui.text(c, Ui.title(String.valueOf(p.level())), cx, y + 24, 2f, Ui.GOLD, true);
            Ui.text(c, Ui.heading("Level"), cx, y + 44, 0.8f, Ui.MUTED, true);
            Ui.text(c, Text.literal(p.disciplineEnum().title), cx, y + 58, 0.9f, Ui.disciplineColor(p.discipline()), true);
        }

        // With the hotbar in the loadout, its old row holds your stats.
        if (side) {
            var pl = client.player;
            c.fill(x + 7, y + 140, x + backgroundWidth - 7, y + 160, 0x40000000);
            stat(c, x + 12, y + 143, "Health", Math.round(pl.getHealth()) + "/" + Math.round(pl.getMaxHealth()), Ui.HP);
            stat(c, x + 92, y + 143, "Damage", String.format(Locale.ROOT, "%.1f", pl.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE)), Ui.CREAM);
            stat(c, x + 12, y + 152, "Speed", Math.round(pl.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) / 0.1 * 100) + "%", Ui.CREAM);
            stat(c, x + 92, y + 152, "Food", pl.getHungerManager().getFoodLevel() + "/20",
                pl.getHungerManager().getFoodLevel() <= 6 ? Ui.RED : Ui.FOOD);
        }
    }

    private void stat(DrawContext c, int sx, int sy, String k, String v, int color) {
        Ui.text(c, Text.literal(k), sx, sy, 0.75f, Ui.MUTED, false);
        Ui.text(c, Text.literal(v), sx + 72 - textRenderer.getWidth(v) * 0.75f, sy, 0.75f, color, false);
    }

    /** Viewer on top, then the Baldur's Gate style loadout made of the real hotbar slots. */
    private void drawCharacterPanel(DrawContext c, int lx, int y) {
        int h = backgroundHeight;
        Ui.panel(c, lx, y, PANEL_W, h);
        c.fillGradient(lx + 4, y + 4, lx + PANEL_W - 4, y + 60, 0x30B8955A, 0x08000000);
        Ui.crest(c, lx + PANEL_W / 2 - 22, y + 8, 44, 0.12f);
        InventoryScreen.drawEntity(c, lx + 4, y + 4, lx + PANEL_W - 4, y + 60, 24, 0.0625f, mouseXf, mouseYf, client.player);
        Ui.divider(c, lx + 8, y + 62, PANEL_W - 16);

        Ui.text(c, Ui.heading("Melee"), lx + 25, y + 66, 0.8f, LoadoutUi.color(com.pglol.aotrpg.Loadout.Kind.MELEE), true);
        Ui.text(c, Ui.heading("Ranged"), lx + PANEL_W - 25, y + 66, 0.8f, LoadoutUi.color(com.pglol.aotrpg.Loadout.Kind.RANGED), true);
        // Sheath state under the melee pair.
        Net.SheathState st = ClientState.sheaths.get(client.player.getUuid());
        String key = AotRpgClient.sheathKey().getBoundKeyLocalizedText().getString();
        String state = com.pglol.aotrpg.Loadout.isGrip(client.player.getOffHandStack()) ? "Drawn · " + key
            : st != null && st.count() > 0 ? "Sheathed · " + key : "";
        if (!state.isEmpty()) Ui.text(c, Text.literal(state), lx + 25, y + 95, 0.55f, state.startsWith("Drawn") ? Ui.RED : Ui.GOLD, true);
        if (st != null && st.count() > 0 && client.player.getInventory().main.get(0).isEmpty()) {
            // Sheathed grips: show them faintly in the melee pair.
            ItemStack g = st.a().isEmpty() ? st.b() : st.a();
            LoadoutUi.drawGhost(c, g, lx + 8, y + 76);
            if (st.count() > 1 && client.player.getOffHandStack().isEmpty()) LoadoutUi.drawGhost(c, st.b(), lx + 28, y + 76);
        }

        // Shield with the armour value.
        int sx = lx + PANEL_W / 2 - 14, sy = y + 68;
        c.fill(sx, sy, sx + 28, sy + 20, 0xF0151A16);
        LoadoutUi.chevronDown(c, sx, sy + 20, 28, 0xF0151A16);
        c.drawBorder(sx, sy, 28, 20, Ui.TRIM);
        Ui.text(c, Ui.heading("AR"), sx + 14, sy + 3, 0.6f, Ui.MUTED, true);
        Ui.text(c, Ui.title(String.valueOf(client.player.getArmor())), sx + 14, sy + 10, 1.1f, Ui.CREAM, true);
        c.fill(lx + 10, y + 120, lx + PANEL_W - 10, y + 121, 0x407A6139);
    }

    private void row(DrawContext c, int lx, int y, String k, String v, int color) {
        c.drawTextWithShadow(textRenderer, Text.literal(k), lx + 8, y, Ui.MUTED);
        c.drawTextWithShadow(textRenderer, Text.literal(v), lx + PANEL_W - 8 - textRenderer.getWidth(v), y, color);
    }

    @Override
    protected void drawForeground(DrawContext c, int mouseX, int mouseY) {
        c.drawTextWithShadow(textRenderer, Ui.heading("Crafting"), 97, 7, Ui.GOLD);
    }

    @Override
    protected boolean isClickOutsideBounds(double mouseX, double mouseY, int left, int top, int button) {
        // Clicks on the character panel must not drop the held item.
        if (panel() && mouseX >= left - PANEL_W - 4 && mouseX < left && mouseY >= top && mouseY < top + backgroundHeight) return false;
        return super.isClickOutsideBounds(mouseX, mouseY, left, top, button);
    }
}
