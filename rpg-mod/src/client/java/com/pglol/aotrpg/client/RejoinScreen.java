package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/** Back after a dropped connection with a match still going: rejoin it, or leave it. */
public final class RejoinScreen extends Screen {
    private final Net.Rejoin r;
    private final long shown = Util.getMeasuringTimeMs();
    private boolean answered;

    public RejoinScreen(Net.Rejoin r) {
        super(Text.literal("Rejoin"));
        this.r = r;
    }

    @Override
    protected void init() {
        int w = 300, x = width / 2 - w / 2, y = height / 2 + 14;
        AotButton yes = addDrawableChild(new AotButton(x + 10, y, 170, 24, Ui.heading("Rejoin"), () -> answer(true)));
        yes.selected(true);
        AotButton no = addDrawableChild(new AotButton(x + 186, y, 104, 24, Text.literal("Leave match"), () -> answer(false)));
        no.accent = Ui.RED;
    }

    private void answer(boolean yes) {
        if (answered) return;
        answered = true;
        ClientPlayNetworking.send(new Net.ExtractionAction(yes ? "rejoin_yes" : "rejoin_no", r.kind()));
        close();
    }

    @Override
    public void tick() {
        // No answer in time: back in.
        if (Util.getMeasuringTimeMs() - shown > r.seconds() * 1000L) answer(true);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        int w = 300, h = 110, x = width / 2 - w / 2, y = height / 2 - 60;
        Ui.panel(c, x, y, w, h);
        Ui.text(c, Ui.title("MATCH IN PROGRESS"), width / 2f, y + 10, 1.1f, Ui.GOLD, true);
        Ui.text(c, Ui.heading(r.title()), width / 2f, y + 30, 0.95f, Ui.CREAM, true);
        Ui.text(c, Text.literal(r.detail()), width / 2f, y + 44, 0.75f, Ui.CREAM, true);
        long left = Math.max(0, r.seconds() - (Util.getMeasuringTimeMs() - shown) / 1000);
        Ui.text(c, Text.literal("Rejoining in " + left + "s"), width / 2f, y + 58, 0.7f, Ui.GOLD, true);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
