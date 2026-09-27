package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * A home just bought: the deed is stamped, a key comes down turning in the light, the name of the
 * place is written in, and then you choose: go inside now, or stay out and look around.
 */
public final class KeyScreen extends Screen {
    private final Net.HomeKey k;
    private final long start = Util.getMeasuringTimeMs();
    private boolean buttons, stamped, landed, fanfare;

    public KeyScreen(Net.HomeKey k) {
        super(Text.literal("Your new home"));
        this.k = k;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return buttons;
    }

    private long t() {
        return Util.getMeasuringTimeMs() - start;
    }

    private void sound(net.minecraft.sound.SoundEvent e, float v, float p) {
        if (client != null && client.player != null) client.player.playSound(e, v, p);
    }

    @Override
    public void tick() {
        long t = t();
        if (!stamped && t > 500) {
            stamped = true;
            sound(SoundEvents.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
            sound(SoundEvents.BLOCK_WOOD_PLACE, 1f, 0.6f);
        }
        if (!landed && t > 1700) {
            landed = true;
            sound(SoundEvents.ITEM_ARMOR_EQUIP_CHAIN.value(), 1f, 1.3f);
            sound(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1f, 1.2f);
        }
        if (!fanfare && t > 2300) {
            fanfare = true;
            sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
        }
        if (!buttons && t > 2600) {
            buttons = true;
            int y = height / 2 + 74;
            addDrawableChild(new AotButton(width / 2 - 124, y, 120, 24, Ui.heading("Go inside"), () -> {
                ClientPlayNetworking.send(new Net.HomeAction("enter", k.home(), ""));
                close();
            })).selected(true);
            addDrawableChild(new AotButton(width / 2 + 4, y, 120, 24, Ui.heading("Stay outside"), this::close));
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        long t = t();
        int cx = width / 2, cy = height / 2 - 20;
        c.fill(0, 0, width, height, 0xD0000000);
        // Rays of warm light behind everything once the key lands.
        if (t > 1700) {
            float k2 = Math.min(1, (t - 1700) / 500f);
            for (int i = 0; i < 20; i++) {
                double a = i * Math.PI / 10 + t * 0.0004;
                int len = (int) (110 * k2);
                for (int d = 30; d < len; d += 3) {
                    int al = (int) (110 * (1 - d / (float) len));
                    int x = cx + (int) (Math.cos(a) * d), y = cy + (int) (Math.sin(a) * d);
                    c.fill(x - 1, y - 1, x + 1, y + 1, (al << 24) | 0xF2C14E);
                }
            }
        }
        // The deed: unrolls from the middle.
        float open = Math.min(1, t / 450f);
        int dw = (int) (220 * open), dh = 120;
        c.fill(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2, 0xFFE8DDBE);
        c.fill(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy - dh / 2 + 4, 0xFFB8955A);
        c.fill(cx - dw / 2, cy + dh / 2 - 4, cx + dw / 2, cy + dh / 2, 0xFFB8955A);
        if (open >= 1) {
            Ui.text(c, Ui.title("DEED OF RESIDENCE"), cx, cy - dh / 2 + 10, 1.1f, 0xFF4A3218, true);
            // The town's name writes itself in.
            String town = k.town();
            int shown = (int) Math.min(town.length(), Math.max(0, (t - 450) / 45));
            Ui.text(c, Ui.heading(town.substring(0, shown)), cx, cy - dh / 2 + 30, 1.4f, 0xFF2A1A08, true);
            if (t > 450 + town.length() * 45L) Ui.text(c, Text.literal(k.size()), cx, cy - dh / 2 + 48, 0.8f, 0xFF6A5A40, true);
        }
        // The wax seal stamps down.
        if (t > 500) {
            float s = Math.max(1, 2.2f - (t - 500) / 120f);
            int r = (int) (12 * s), sx = cx + 80, sy = cy + 36;
            c.fill(sx - r, sy - r, sx + r, sy + r, 0xFFA02A1A);
            c.fill(sx - r + 3, sy - r + 3, sx + r - 3, sy + r - 3, 0xFFC0402A);
            Ui.text(c, Text.literal("✦"), sx, sy - 4, 1f, 0xFFF2C14E, true);
        }
        // The key comes down, turning, and settles on the deed.
        if (t > 900) {
            float p = Math.min(1, (t - 900) / 800f);
            double ease = 1 - Math.pow(1 - p, 3);
            float sc = 3.2f;
            int ky = (int) (cy - 160 + ease * 150);
            float spin = (float) ((1 - ease) * 540);
            c.getMatrices().push();
            c.getMatrices().translate(cx, ky, 50);
            c.getMatrices().multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(spin + 45));
            c.getMatrices().scale(sc, sc, 1);
            c.drawItem(new ItemStack(Items.TRIAL_KEY), -8, -8);
            c.getMatrices().pop();
            // Glints around it once it's down.
            if (p >= 1) {
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4 + t * 0.003;
                    int d = 26 + (int) (Math.sin(t * 0.006 + i) * 4);
                    int al = (int) (160 + 90 * Math.sin(t * 0.01 + i));
                    c.fill(cx + (int) (Math.cos(a) * d), ky + (int) (Math.sin(a) * d), cx + (int) (Math.cos(a) * d) + 2,
                        ky + (int) (Math.sin(a) * d) + 2, (Math.max(0, Math.min(255, al)) << 24) | 0xFFF6C0);
                }
            }
        }
        if (t > 2300) {
            float a = Math.min(1, (t - 2300) / 300f);
            Ui.text(c, Ui.title("WELCOME HOME"), cx, cy + dh / 2 + 12, 1.6f, ((int) (255 * a) << 24) | 0xF2C14E, true);
            Ui.text(c, Text.literal("The house in town stays as it is: behind its door is your own private copy."), cx, cy + dh / 2 + 34, 0.7f,
                ((int) (220 * a) << 24) | 0xEDE3C8, true);
        }
    }
}
