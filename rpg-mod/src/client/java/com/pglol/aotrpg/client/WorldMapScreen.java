package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

import java.util.ArrayList;
import java.util.List;

/**
 * The world map: the whole island with area names, level ranges and title colours, you, your
 * party, quest markers and campfires. Drag to pan, scroll to zoom, right-click to mark a spot
 * (your party sees it too). The side panel tracks quests and highlights them for the party.
 */
public class WorldMapScreen extends Screen {
    private static final int SIDE = 150;
    private static double centerX = Double.NaN, centerZ, zoom = 0.03;
    private int mapL, mapT, mapR, mapB;
    private boolean dragging;

    public WorldMapScreen() {
        super(Text.literal("World Map"));
    }

    public void refresh() {
        clearAndInit();
    }

    public static void focus(double x, double z) {
        centerX = x;
        centerZ = z;
        zoom = Math.max(zoom, 0.12);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static long lastRequest;

    /** Ask the server again if the map or area names never arrived. */
    static void requestIfMissing() {
        long now = net.minecraft.util.Util.getMeasuringTimeMs();
        if ((ClientState.areas.isEmpty() || !MapData.received) && now - lastRequest > 5000) {
            lastRequest = now;
            ClientPlayNetworking.send(new Net.WorldDataRequest());
        }
    }

    @Override
    protected void init() {
        requestIfMissing();
        ClientPlayerEntity pl = client.player;
        if (Double.isNaN(centerX) && pl != null) {
            centerX = pl.getX();
            centerZ = pl.getZ();
        }
        mapL = 8;
        mapT = 26;
        mapR = width - SIDE - 12;
        mapB = height - 22;
        int x = width - SIDE - 4, y = mapT + 16;
        for (Net.QuestView q : sideQuests()) {
            if (y > height - 60) break;
            AotButton track = new AotButton(x + SIDE - 42, y, 18, 16, Text.literal("◎"),
                () -> ClientPlayNetworking.send(new Net.QuestAction(q.id(), "track")));
            track.selected = q.tracked();
            track.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(q.tracked() ? "Tracked" : "Track this quest")));
            AotButton star = new AotButton(x + SIDE - 21, y, 18, 16, Text.literal("★"),
                () -> ClientPlayNetworking.send(new Net.QuestAction(q.id(), "highlight")));
            star.selected = !q.highlightedBy().isEmpty();
            star.accent = 0xFFD070FF;
            star.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(q.highlightedBy().isEmpty()
                ? "Highlight for your party" : "Highlighted by " + q.highlightedBy() + " (click to remove)")));
            addDrawableChild(track);
            addDrawableChild(star);
            y += 30;
        }
        addDrawableChild(new AotButton(x, height - 44, SIDE, 16, Text.literal("Quest Journal [J]"), () -> client.setScreen(new JournalScreen())));
        addDrawableChild(new AotButton(x, height - 24, SIDE, 16, Text.literal("Centre on me"), () -> {
            if (client.player != null) {
                centerX = client.player.getX();
                centerZ = client.player.getZ();
            }
        }));
    }

    private List<Net.QuestView> sideQuests() {
        List<Net.QuestView> l = new ArrayList<>();
        for (Net.QuestView q : ClientState.quests) if (q.id().equals("main") || q.state() == 1) l.add(q);
        return l;
    }

    // -------------------------------------------------------------- coordinates

    private double cx() {
        return (mapL + mapR) / 2.0;
    }

    private double cy() {
        return (mapT + mapB) / 2.0;
    }

    private double sx(double wx) {
        return cx() + (wx - centerX) * zoom;
    }

    private double sy(double wz) {
        return cy() + (wz - centerZ) * zoom;
    }

    private double wx(double sx) {
        return centerX + (sx - cx()) / zoom;
    }

    private double wz(double sy) {
        return centerZ + (sy - cy()) / zoom;
    }

    private boolean inMap(double x, double y) {
        return x >= mapL && x <= mapR && y >= mapT && y <= mapB;
    }

    // -------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (!inMap(mx, my)) return false;
        if (button == 0) {
            dragging = true;
            return true;
        }
        if (button == 1) {
            for (Net.Marker m : ClientState.markers) {
                if (m.kind().equals("mark") && Math.abs(sx(m.x()) - mx) < 6 && Math.abs(sy(m.z()) - my) < 6) {
                    ClientPlayNetworking.send(new Net.SetWaypoint(true, 0, 0));
                    return true;
                }
            }
            ClientPlayNetworking.send(new Net.SetWaypoint(false, (int) Math.floor(wx(mx)), (int) Math.floor(wz(my))));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            centerX -= dx / zoom;
            centerZ -= dy / zoom;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (!inMap(mx, my)) return super.mouseScrolled(mx, my, h, v);
        double bx = wx(mx), bz = wz(my);
        zoom = MathHelper.clamp(zoom * (v > 0 ? 1.25 : 0.8), 0.012, 1.5);
        centerX = bx - (mx - cx()) / zoom;
        centerZ = bz - (my - cy()) / zoom;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (AotRpgClient.mapKey().matchesKey(key, scan) && !Screen.hasControlDown()) {
            close();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // -------------------------------------------------------------- drawing

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xF0141008, 0xF80A0804);
        Ui.cloth(c, 0, 0, width, height);
        Ui.text(c, Ui.title("MAP OF THE WORLD"), mapL, 8, 1.2f, Ui.GOLD, false);

        c.enableScissor(mapL, mapT, mapR, mapB);
        c.fill(mapL, mapT, mapR, mapB, 0xFF3A3020);
        MapData.Image world = MapData.world();
        if (world != null && world.ready) {
            drawImage(c, world, 1f);
            // Town plans fade in as you zoom in.
            float alpha = (float) MathHelper.clamp((zoom - 0.09) / 0.12, 0, 1);
            if (alpha > 0) {
                for (MapData.Image t : MapData.tiles()) {
                    double x0 = sx(t.info.x0()), z0 = sy(t.info.z0());
                    double size = t.width * t.info.bpp() * zoom;
                    if (x0 > mapR || z0 > mapB || x0 + size < mapL || z0 + size < mapT) continue;
                    drawImage(c, t, alpha);
                }
            }
        } else {
            String msg = !MapData.received ? "No map for this world yet" : "Loading map...";
            c.drawCenteredTextWithShadow(textRenderer, Ui.heading(msg), (int) cx(), (int) cy() - 20, Ui.GOLD);
            if (!MapData.received) {
                c.drawCenteredTextWithShadow(textRenderer, Text.literal("The server needs the map made by the world generator:"),
                    (int) cx(), (int) cy() - 6, Ui.CREAM);
                c.drawCenteredTextWithShadow(textRenderer, Text.literal("run add-titans.bat on the server's world folder, then /aotrpg reload."),
                    (int) cx(), (int) cy() + 5, Ui.CREAM);
            }
        }
        drawFires(c);
        drawAreas(c, mouseX, mouseY);
        drawMarkers(c, mouseX, mouseY);
        drawParty(c);
        drawPlayer(c, delta);
        if (MapData.outdated) {
            c.fill(mapL, mapT, mapR, mapT + 24, 0xD0301008);
            c.drawCenteredTextWithShadow(textRenderer, Text.literal("This map was made by an older world generator."), (int) cx(), mapT + 3, 0xFFFFD27A);
            c.drawCenteredTextWithShadow(textRenderer, Text.literal("Download the new aot-world.jar, run add-titans.bat on the server world, then /aotrpg reload."),
                (int) cx(), mapT + 13, Ui.CREAM);
        }
        c.disableScissor();
        Ui.border(c, mapL - 2, mapT - 2, mapR - mapL + 4, mapB - mapT + 4);

        // Side panel
        int x = width - SIDE - 8;
        Ui.panel(c, x, mapT - 2, SIDE + 6, height - mapT - 50);
        c.drawTextWithShadow(textRenderer, Ui.heading("Quests"), x + 6, mapT + 3, Ui.GOLD);
        int y = mapT + 16;
        for (Net.QuestView q : sideQuests()) {
            if (y > height - 60) break;
            String t = textRenderer.trimToWidth(q.title(), SIDE - 50);
            c.drawTextWithShadow(textRenderer, Text.literal(t), x + 6, y + 1, q.tracked() ? Ui.GOLD : Ui.CREAM);
            String sub = q.progress().isEmpty() ? q.category() : q.progress();
            if (!q.highlightedBy().isEmpty()) sub = "★ " + q.highlightedBy();
            c.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(sub, SIDE - 50)), x + 6, y + 11,
                q.highlightedBy().isEmpty() ? Ui.MUTED : 0xFFD070FF);
            y += 30;
        }
        c.drawTextWithShadow(textRenderer, Text.literal("Drag to move · Scroll to zoom · Right-click to mark a spot (again to remove)"),
            mapL, height - 14, Ui.MUTED);
    }

    private void drawImage(DrawContext c, MapData.Image img, float alpha) {
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(sx(img.info.x0()), sy(img.info.z0()), 0);
        float s = (float) (img.info.bpp() * zoom);
        m.scale(s, s, 1);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        c.setShaderColor(1f, 1f, 1f, alpha);
        c.drawTexture(img.texture, 0, 0, 0, 0, img.width, img.height, img.width, img.height);
        c.setShaderColor(1f, 1f, 1f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        m.pop();
    }

    private void drawFires(DrawContext c) {
        if (zoom < 0.12) return;
        int[] f = ClientState.campfires;
        for (int i = 0; i + 2 < f.length; i += 3) {
            int x = (int) sx(f[i] + 0.5), y = (int) sy(f[i + 2] + 0.5);
            if (!inMap(x, y)) continue;
            c.fill(x - 2, y - 2, x + 2, y + 2, 0xFF2A1406);
            c.fill(x - 1, y - 1, x + 1, y + 1, 0xFFFF9A2E);
        }
    }

    private static final net.minecraft.util.Identifier TITLE_FONT = net.minecraft.util.Identifier.of("aot_rpg", "maptitle"),
        LEVEL_FONT = net.minecraft.util.Identifier.of("aot_rpg", "maplevel");

    /** Higher shows first; lower ones only where there is room. */
    private int importance(Net.Area a) {
        return switch (a.look()) {
            case "marley", "sea" -> 9;
            case "town" -> a.name().equals("Mitras") ? 8 : 7;
            case "safe", "danger" -> a.prio() <= 40 ? (zoom < 0.05 ? 6 : 2) : 5;
            case "camp", "cave" -> 4;
            default -> 3;
        };
    }

    private boolean labelVisible(Net.Area a) {
        return switch (a.look()) {
            case "sea", "marley" -> true;
            case "town" -> zoom >= 0.014;
            case "cave", "camp", "landmark" -> zoom >= 0.03;
            default -> a.prio() <= 40 ? zoom < 0.12 : zoom >= 0.02;
        };
    }

    private void drawAreas(DrawContext c, int mouseX, int mouseY) {
        List<Net.Area> order = new ArrayList<>();
        for (Net.Area a : ClientState.areas) if (labelVisible(a)) order.add(a);
        order.sort((p, q) -> importance(q) != importance(p) ? importance(q) - importance(p) : q.prio() - p.prio());
        List<int[]> placed = new ArrayList<>();
        Net.Area hover = null;
        for (Net.Area a : order) {
            int x = (int) Math.round(sx(a.x())), y = (int) Math.round(sy(a.z()));
            if (!inMap(x, y)) continue;
            int col = Ui.lookColor(a.look());
            boolean big = a.prio() <= 40;
            Text lv = Text.literal(a.min() == a.max() ? "Lv " + a.min() : "Lv " + a.min() + "-" + a.max())
                .styled(st -> st.withFont(LEVEL_FONT));
            Text name = Text.literal(big ? a.name().toUpperCase() : a.name()).styled(st -> st.withFont(TITLE_FONT));
            int w = Math.max(textRenderer.getWidth(name), textRenderer.getWidth(lv));
            int ty = big ? y - 10 : y - 24;
            int[] r = {x - w / 2 - 3, ty - 2, x + w / 2 + 3, ty + 21};
            boolean clash = false;
            for (int[] o : placed) if (r[0] < o[2] && r[2] > o[0] && r[1] < o[3] && r[3] > o[1]) { clash = true; break; }
            // The icon always shows; the label only where it fits.
            if (a.look().equals("cave")) c.drawCenteredTextWithShadow(textRenderer, Text.literal("\u2620"), x, y - 4, col);
            else if (a.look().equals("camp")) c.drawCenteredTextWithShadow(textRenderer, Text.literal("\u25b2"), x, y - 4, col);
            else if (!big) {
                c.fill(x - 3, y - 3, x + 4, y + 4, 0xFF1A140A);
                c.fill(x - 2, y - 2, x + 3, y + 3, col);
            }
            if (clash) continue;
            placed.add(r);
            Ui.inked(c, lv, x, ty, 1f, 0xFFF4EAD2, true);
            Ui.inked(c, name, x, ty + 9, 1f, col, true);
            if (mouseX >= r[0] && mouseX <= r[2] && mouseY >= r[1] && mouseY <= r[3]) hover = a;
        }
        if (hover != null) {
            List<Text> tip = new ArrayList<>();
            tip.add(Text.literal(hover.name()).withColor(Ui.lookColor(hover.look())));
            tip.add(Text.literal(hover.sub()).formatted(Formatting.GRAY));
            tip.add(Text.literal("Level " + hover.min() + "-" + hover.max()).formatted(Formatting.YELLOW));
            if (hover.titans() > 0) tip.add(Text.literal("Titans around Lv " + hover.titans()).formatted(Formatting.RED));
            c.drawTooltip(textRenderer, tip, mouseX, mouseY);
        }
    }

    private void drawMarkers(DrawContext c, int mouseX, int mouseY) {
        for (Net.Marker m : ClientState.markers) {
            int x = (int) sx(m.x() + 0.5), y = (int) sy(m.z() + 0.5);
            int col = 0xFF000000 | m.color();
            if (m.kind().equals("home")) {
                if (x < mapL + 4 || x > mapR - 4 || y < mapT + 4 || y > mapB - 4) continue;
                Minimap.house(c, x, y, col);
                if (Math.abs(mouseX - x) < 6 && Math.abs(mouseY - y) < 6) {
                    c.drawTooltip(textRenderer, List.of(Text.literal(m.label()).withColor(col),
                        Text.literal(distance(m.x(), m.z())).formatted(Formatting.GRAY)), mouseX, mouseY);
                }
                continue;
            }
            x = MathHelper.clamp(x, mapL + 4, mapR - 4);
            y = MathHelper.clamp(y, mapT + 4, mapB - 4);
            c.fill(x - 1, y - 4, x + 2, y + 5, 0xFF101010);
            c.fill(x - 4, y - 1, x + 5, y + 2, 0xFF101010);
            c.fill(x, y - 3, x + 1, y + 4, col);
            c.fill(x - 3, y, x + 4, y + 1, col);
            c.fill(x - 1, y - 1, x + 2, y + 2, col);
            if (Math.abs(mouseX - x) < 6 && Math.abs(mouseY - y) < 6) {
                c.drawTooltip(textRenderer, List.of(Text.literal(m.label()).withColor(col),
                    Text.literal(distance(m.x(), m.z())).formatted(Formatting.GRAY)), mouseX, mouseY);
            }
        }
    }

    private String distance(int x, int z) {
        if (client.player == null) return "";
        double dx = x - client.player.getX(), dz = z - client.player.getZ();
        return (int) Math.sqrt(dx * dx + dz * dz) + " blocks away";
    }

    private void drawParty(DrawContext c) {
        for (Net.PartyMember m : ClientState.party) {
            if (!m.online() || !m.sameWorld()) continue;
            int x = (int) sx(m.x()), y = (int) sy(m.z());
            if (!inMap(x, y)) continue;
            int col = m.leader() ? 0xFFF2C14E : 0xFF5BD35B;
            c.fill(x - 3, y - 3, x + 3, y + 3, 0xFF0B0F0C);
            c.fill(x - 2, y - 2, x + 2, y + 2, col);
            c.drawCenteredTextWithShadow(textRenderer, Text.literal(m.name()), x, y + 5, col);
        }
    }

    private void drawPlayer(DrawContext c, float delta) {
        ClientPlayerEntity pl = client.player;
        if (pl == null) return;
        MatrixStack ms = c.getMatrices();
        ms.push();
        ms.translate((float) sx(pl.getX()), (float) sy(pl.getZ()), 0);
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(pl.getYaw(delta) + 180));
        ms.scale(1.3f, 1.3f, 1);
        Text arrow = Text.literal("▲");
        c.drawText(textRenderer, arrow, -textRenderer.getWidth(arrow) / 2, -4, 0xFFFFFFFF, true);
        ms.pop();
    }
}
