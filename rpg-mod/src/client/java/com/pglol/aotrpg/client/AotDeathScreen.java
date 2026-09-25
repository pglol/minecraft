package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/** The death screen: what happened, what you kept, and a way back into the fight. */
public class AotDeathScreen extends Screen {
    private static final String[] QUOTES = {
        "“If you don't fight, you can't win.”",
        "“Dedicate your heart.”",
        "“The world is cruel, but it is also very beautiful.”",
        "“Give up on your dreams and die.”",
        "“We are born free.”"
    };
    private final String quote = QUOTES[(int) (Util.getMeasuringTimeMs() / 1000 % QUOTES.length)];
    private int ticks;
    private boolean respawning;
    private AotButton respawn, leave;

    public AotDeathScreen() {
        super(Text.literal("You have fallen"));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        int bw = 150;
        respawn = new AotButton(width / 2 - bw - 5, height - 50, bw, 24, Ui.title("RISE AGAIN"), () -> {
            if (client.player != null) client.player.requestRespawn();
            respawning = true;
            respawn.active = false;
        });
        respawn.textScale = 1.3f;
        leave = new AotButton(width / 2 + 5, height - 50, bw, 24, Ui.heading("Leave the server"), this::leave);
        respawn.active = !respawning && ticks >= 20;
        leave.active = ticks >= 20;
        addDrawableChild(respawn);
        addDrawableChild(leave);
    }

    private void leave() {
        boolean single = client.isInSingleplayer();
        if (client.world != null) client.world.disconnect();
        if (single) client.disconnect(new MessageScreen(Text.translatable("menu.savingLevel")));
        else client.disconnect();
        client.setScreen(single ? new TitleScreen() : new MultiplayerScreen(new TitleScreen()));
    }

    @Override
    public void tick() {
        ticks++;
        if (ticks == 20 && respawn != null) {
            respawn.active = !respawning;
            leave.active = true;
        }
        // Close once the server has respawned us.
        if (respawning && client.player != null && !client.player.isDead()) {
            ClientState.death = null;
            close();
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xE8200606, 0xF8050202);
        c.fillGradient(0, 0, width, height / 2, 0x40801010, 0x00000000);
        float in = Math.min(1f, (ticks + delta) / 30f);
        int a = Math.max(8, (int) (255 * in));

        Ui.text(c, Ui.title("YOU HAVE FALLEN"), width / 2f, height / 5f, 3f, (a << 24) | 0xC0302A, true);
        Net.DeathInfo d = ClientState.death;
        int y = height / 5 + 40;
        if (d != null) {
            c.drawCenteredTextWithShadow(textRenderer, Text.literal(d.message()), width / 2, y, (a << 24) | 0xEDE3C8);
            y += 20;
            int pw = Math.min(280, width - 40), px = width / 2 - pw / 2;
            Ui.panel(c, px, y, pw, 62);
            boolean story = d.mode().equals("story");
            c.drawTextWithShadow(textRenderer, Ui.heading(story ? "Story mode" : "Extraction"), px + 10, y + 8, story ? 0xFF8FCB6A : Ui.RED);
            String gear = story ? "Your gear is protected" : "Your gear was dropped";
            c.drawTextWithShadow(textRenderer, Text.literal(gear), px + pw - 10 - textRenderer.getWidth(gear), y + 8, Ui.CREAM);
            line(c, px, pw, y + 22, "XP lost", d.xpLost() > 0 ? "-" + d.xpLost() : "none");
            line(c, px, pw, y + 33, "Gear worn", story ? (d.gearWorn() > 0 ? d.gearWorn() + " items, -10% durability" : "none") : "all dropped");
            line(c, px, pw, y + 44, "Satchel", "safe");
            y += 74;
        }
        c.drawCenteredTextWithShadow(textRenderer, Text.literal(quote), width / 2, Math.max(y, height - 80), (a << 24) | 0x8F8A7A);
    }

    private void line(DrawContext c, int px, int pw, int y, String k, String v) {
        c.drawTextWithShadow(textRenderer, Text.literal(k), px + 10, y, Ui.MUTED);
        c.drawTextWithShadow(textRenderer, Text.literal(v), px + pw - 10 - textRenderer.getWidth(v), y, Ui.CREAM);
    }
}
