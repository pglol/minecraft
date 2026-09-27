package com.pglol.aotrpg.client;

import com.pglol.aotrpg.client.mixin.ChatHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.network.message.ChatVisibility;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A quieter chat. At rest it shows only what other players say (a few lines, gone after a few
 * seconds) on a soft shade, never a stack of boxes. Server and mod messages don't fill it: each
 * becomes a small notice, and stays readable in the chat history. Replies to a command you just
 * typed, and messages with something to click, still show. Open, it's one clean panel with a thin
 * brass edge; lines sit exactly where vanilla puts them, so hovering and clicking still work.
 */
public final class CleanChat {
    private CleanChat() {}

    private static final int REST_LINES = 4, REST_TICKS = 160;
    private static int commandTick = -10_000;
    /** The chat ticks at which clickable server messages arrived (always shown). */
    private static final Set<Integer> loud = new HashSet<>();

    public static void commandSent() {
        commandTick = MinecraftClient.getInstance().inGameHud.getTicks();
    }

    private static boolean recentCommand(int addedTime) {
        return addedTime >= commandTick && addedTime - commandTick <= 60;
    }

    /** A server or mod message arrived. */
    public static void onSystem(Text msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null || msg.getString().isBlank()) return;
        int now = mc.inGameHud.getTicks();
        if (clickable(msg)) {
            loud.add(now);
            if (loud.size() > 64) loud.clear();
            return;
        }
        if (mc.currentScreen instanceof ChatScreen || recentCommand(now)) return;
        // A notice instead of a chat line: the first part as the title, the rest under it.
        String s = msg.getString().replace('\n', ' ').trim();
        String head = s, tail = "";
        if (s.length() > 44) {
            int cut = s.lastIndexOf(' ', 44);
            if (cut < 20) cut = 44;
            head = s.substring(0, cut).trim();
            tail = s.substring(cut).trim();
            if (tail.length() > 70) tail = tail.substring(0, 69).trim() + "…";
        }
        Integer col = msg.getStyle().getColor() != null ? msg.getStyle().getColor().getRgb() : null;
        Toasts.push(Text.literal(head).withColor(col == null ? 0xEDE3C8 : col), tail.isEmpty() ? null : Text.literal(tail).withColor(0xFF8F8A7A),
            0xB8955A, null, "sys:" + s.hashCode());
    }

    private static boolean clickable(Text t) {
        Optional<Boolean> found = t.visit((Style style, String str) ->
            style.getClickEvent() != null ? Optional.of(true) : Optional.empty(), Style.EMPTY);
        return found.orElse(false);
    }

    private static boolean system(ChatHudLine.Visible v) {
        MessageIndicator i = v.indicator();
        return i == MessageIndicator.system() || i == MessageIndicator.singlePlayer();
    }

    /** Draws the chat; false to leave it to vanilla (if our hooks aren't in place). */
    public static boolean render(ChatHud hud, DrawContext c, int tick, boolean focused) {
        if (!((Object) hud instanceof ChatHudAccessor acc)) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.getChatVisibility().getValue() == ChatVisibility.HIDDEN) return true;
        List<ChatHudLine.Visible> lines;
        int scrolled;
        try {
            lines = acc.aotrpg$visible();
            scrolled = acc.aotrpg$scrolled();
        } catch (RuntimeException e) {
            return false;
        }
        if (lines.isEmpty()) return true;
        var tr = mc.textRenderer;
        float f = (float) hud.getChatScale();
        int width = MathHelper.ceil(hud.getWidth() / f);
        double spacing = mc.options.getChatLineSpacing().getValue();
        int lh = (int) (9.0 * (spacing + 1.0));
        int textOff = (int) Math.round(-8.0 * (spacing + 1.0) + 4.0 * spacing);
        int base = MathHelper.floor((c.getScaledWindowHeight() - 40) / f);
        var ms = c.getMatrices();
        ms.push();
        ms.scale(f, f, 1);
        ms.translate(4, 0, 0);
        if (focused) {
            int count = Math.min(hud.getVisibleLineCount(), lines.size() - scrolled);
            int top = base - count * lh;
            // One panel, a thin brass edge on the left.
            c.fill(-4, top - 3, width + 8, base + 1, 0xC00D0F0D);
            c.fill(-4, top - 3, -3, base + 1, 0xFFB8955A);
            for (int r = 0; r < count; r++) {
                ChatHudLine.Visible v = lines.get(r + scrolled);
                if (v == null) continue;
                int y = base - r * lh + textOff;
                ms.push();
                ms.translate(0, 0, 50);
                c.drawTextWithShadow(tr, v.content(), 0, y, system(v) ? 0xFFCFC6B0 : 0xFFFFFFFF);
                ms.pop();
            }
            // A slim scroll bar when there's more above.
            if (lines.size() > hud.getVisibleLineCount()) {
                int total = lines.size(), shown = hud.getVisibleLineCount();
                int h = count * lh, bar = Math.max(6, h * shown / total);
                int by = base - bar - (h - bar) * scrolled / Math.max(1, total - shown);
                c.fill(width + 6, by, width + 7, by + bar, 0xA0B8955A);
            }
        } else {
            int drawn = 0;
            for (ChatHudLine.Visible v : lines) {
                if (drawn >= REST_LINES) break;
                int age = tick - v.addedTime();
                if (age > REST_TICKS) break;
                if (system(v) && !recentCommand(v.addedTime()) && !loud.contains(v.addedTime())) continue;
                float a = age > REST_TICKS - 40 ? (REST_TICKS - age) / 40f : 1f;
                a *= (float) (mc.options.getChatOpacity().getValue() * 0.9 + 0.1);
                if (a < 0.02f) continue;
                int y = base - drawn * lh;
                int w = tr.getWidth(v.content());
                // A soft shade just behind the words (no boxes stacked up the screen).
                c.fillGradient(-4, y - lh, w + 6, y, (int) (a * 110) << 24, (int) (a * 40) << 24);
                ms.push();
                ms.translate(0, 0, 50);
                c.drawTextWithShadow(tr, v.content(), 0, y + textOff, (Math.max(4, (int) (a * 255)) << 24) | 0xFFFFFF);
                ms.pop();
                drawn++;
            }
        }
        ms.pop();
        return true;
    }

    /** The input bar at the bottom while typing: dark, a brass line along its top, a small prompt. */
    public static void inputBar(DrawContext c, int x1, int y1, int x2, int y2) {
        c.fill(x1, y1, x2, y2, 0xE00D0F0D);
        c.fill(x1, y1 - 1, x2, y1, 0xFFB8955A);
    }
}
