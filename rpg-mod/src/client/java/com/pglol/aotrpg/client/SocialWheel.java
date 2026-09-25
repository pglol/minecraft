package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Hold the social key (Left Alt), point at an option, let go. Clicking also works.
 * Empty slots are reserved for future actions.
 */
public final class SocialWheel extends Screen {
    private record Option(String name, String hint, ItemStack icon, boolean ready, Runnable run) { }

    private final Option[] options;
    private int hovered = -1;
    private final long opened = System.currentTimeMillis();

    public SocialWheel() {
        super(Text.literal("Social"));
        MinecraftClient mc = MinecraftClient.getInstance();
        options = new Option[] {
            new Option("Party", "Invite, leave, see your squad", new ItemStack(Items.WHITE_BANNER), true,
                () -> mc.setScreen(new PartyScreen())),
            new Option("Emote", "Coming soon", new ItemStack(Items.ARMOR_STAND), false,
                () -> soon(mc, "Emotes")),
            new Option("Trade", "Coming soon", new ItemStack(Items.EMERALD), false,
                () -> soon(mc, "Trading")),
            new Option("Cosmetics", "Trails and looks", new ItemStack(Items.AMETHYST_SHARD), true,
                () -> mc.setScreen(new CosmeticsScreen())),
            new Option("Market", "Trade at the town market", new ItemStack(Items.GOLD_NUGGET), true, () -> {
                mc.setScreen(null);
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.pglol.aotrpg.Net.MarketAction("open", "", 0, 0));
            }),
            new Option("Factions", "Sectors and work orders", new ItemStack(Items.WHITE_BANNER), true, () -> {
                mc.setScreen(null);
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.pglol.aotrpg.Net.FactionAction("open", ""));
            }),
        };
    }

    private static void soon(MinecraftClient mc, String what) {
        mc.setScreen(null);
        if (mc.player != null) mc.player.sendMessage(Text.literal(what + " are coming soon.").formatted(Formatting.GRAY), true);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        // Released the key: pick what the cursor points at.
        InputUtil.Key key = InputUtil.fromTranslationKey(AotRpgClient.socialKey().getBoundKeyTranslationKey());
        if (key.getCategory() == InputUtil.Type.KEYSYM
            && !InputUtil.isKeyPressed(client.getWindow().getHandle(), key.getCode())
            && System.currentTimeMillis() - opened > 120) {
            choose();
        }
    }

    private void choose() {
        if (hovered >= 0 && (options[hovered].ready() || !options[hovered].name().isEmpty())) options[hovered].run().run();
        else close();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && hovered >= 0) {
            choose();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0x60000000, 0x90000000);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        float cx = width / 2f, cy = height / 2f;
        int n = options.length;
        float r = 78;
        double dx = mouseX - cx, dy = mouseY - cy;
        hovered = -1;
        if (dx * dx + dy * dy > 22 * 22) {
            double ang = Math.atan2(dy, dx) + Math.PI / 2; // 0 = straight up
            if (ang < 0) ang += Math.PI * 2;
            hovered = (int) Math.round(ang / (Math.PI * 2 / n)) % n;
        }
        // Hub
        Ui.crest(c, (int) cx - 16, (int) cy - 16, 32, 0.9f);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n - Math.PI / 2;
            int ox = (int) (cx + Math.cos(a) * r), oy = (int) (cy + Math.sin(a) * r);
            Option o = options[i];
            boolean h = i == hovered;
            int bw = 76, bh = 44;
            int x = ox - bw / 2, y = oy - bh / 2;
            if (h) {
                // A thin spoke from the hub to the choice.
                for (int s = 1; s < 12; s++) {
                    double t = 22 + s * (r - 44) / 12.0;
                    c.fill((int) (cx + Math.cos(a) * t), (int) (cy + Math.sin(a) * t),
                        (int) (cx + Math.cos(a) * t) + 2, (int) (cy + Math.sin(a) * t) + 2, 0xC0E0B96A);
                }
            }
            c.fill(x, y, x + bw, y + bh, h ? 0xF0202820 : 0xD00B0F0C);
            c.drawBorder(x, y, bw, bh, h ? Ui.GOLD : o.name().isEmpty() ? 0x6055524A : Ui.BORDER);
            if (o.name().isEmpty()) {
                Ui.text(c, Text.literal("—"), ox, oy - 4, 1f, Ui.DIM, true);
                continue;
            }
            if (!o.icon().isEmpty()) c.drawItem(o.icon(), ox - 8, y + 5);
            Ui.text(c, Ui.heading(o.name()), ox, y + 24, 0.9f, o.ready() ? (h ? Ui.GOLD : Ui.CREAM) : Ui.MUTED, true);
            Ui.text(c, Text.literal(o.hint()), ox, y + 34, 0.55f, Ui.MUTED, true);
        }
        Ui.text(c, Text.literal("Release to choose"), cx, cy + r + 34, 0.7f, Ui.MUTED, true);
    }
}
