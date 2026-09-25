package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Your property on the client: the key hint while you stand on it, and furniture placing (an
 * outline of the piece where you look, facing you; right-click to set it down).
 */
public final class Property {
    private Property() {}

    public static KeyBinding key;

    public static Net.FurniturePiece placingPiece() {
        if (ClientState.placing == null || ClientState.furniture == null) return null;
        for (Net.FurniturePiece f : ClientState.furniture.pieces()) if (f.id().equals(ClientState.placing)) return f;
        return null;
    }

    public static void keyPressed(MinecraftClient mc) {
        if (ClientState.placing != null) {
            ClientState.placing = null;
            return;
        }
        if (ClientState.profile != null && mc.currentScreen == null) ClientPlayNetworking.send(new Net.HomeAction("manage", -1, ""));
    }

    private static String keyName() {
        return key == null ? "Y" : key.getBoundKeyLocalizedText().getString().toUpperCase(java.util.Locale.ROOT);
    }

    public static void renderHud(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.currentScreen != null || mc.player == null) return;
        Net.FurniturePiece f = placingPiece();
        String text;
        if (f != null) text = "Placing " + f.title() + "  ·  right-click a floor  ·  turn to rotate  ·  [" + keyName() + "] cancel";
        else if (!ClientState.property.isEmpty()) text = ClientState.property + "  ·  [" + keyName() + "] furniture & upgrades";
        else return;
        Text t = Text.literal(text);
        int w = (int) (Ui.font().getWidth(t) * 0.8f) + 16;
        int x = (c.getScaledWindowWidth() - w) / 2, y = c.getScaledWindowHeight() - 76;
        c.fill(x, y, x + w, y + 13, 0xB0101410);
        c.fill(x, y + 12, x + w, y + 13, Ui.GOLD);
        Ui.text(c, t, c.getScaledWindowWidth() / 2f, y + 3, 0.8f, f != null ? 0xFFFFE08A : Ui.CREAM, true);
    }

    private static BlockRotation rotation(Direction facing) {
        return switch (facing) {
            case EAST -> BlockRotation.CLOCKWISE_90;
            case SOUTH -> BlockRotation.CLOCKWISE_180;
            case WEST -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
    }

    /** Where the piece would go: on the face you look at (or in the block, if it is replaceable). */
    private static BlockPos target(MinecraftClient mc) {
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        return mc.world.getBlockState(pos).isReplaceable() ? pos : pos.offset(hit.getSide());
    }

    public static ActionResult use(MinecraftClient mc) {
        Net.FurniturePiece f = placingPiece();
        if (f == null) return ActionResult.PASS;
        BlockPos at = target(mc);
        if (at == null) return ActionResult.FAIL;
        ClientPlayNetworking.send(new Net.FurnitureAction("place", f.id(), at.asLong()));
        if (f.owned() <= 1) ClientState.placing = null;
        return ActionResult.FAIL; // handled here; nothing is sent to the server as a normal use
    }

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Net.FurniturePiece f = placingPiece();
        if (f == null || mc.player == null || mc.world == null) return;
        BlockPos at = target(mc);
        MatrixStack ms = ctx.matrixStack();
        if (at == null || ms == null || ctx.consumers() == null) return;
        BlockRotation rot = rotation(mc.player.getHorizontalFacing());
        BlockPos a = new BlockPos(f.x0(), 0, f.z0()).rotate(rot), b = new BlockPos(f.x1(), f.y1(), f.z1()).rotate(rot);
        Box box = new Box(Math.min(a.getX(), b.getX()), 0, Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1, f.y1() + 1, Math.max(a.getZ(), b.getZ()) + 1).offset(at).expand(0.01);
        Vec3d cam = ctx.camera().getPos();
        VertexConsumer lines = ctx.consumers().getBuffer(RenderLayer.getLines());
        ms.push();
        ms.translate(-cam.x, -cam.y, -cam.z);
        WorldRenderer.drawBox(ms, lines, box, 1f, 0.85f, 0.4f, 1f);
        // The front edge (the side facing you) in white.
        Direction front = mc.player.getHorizontalFacing().getOpposite();
        Box edge = switch (front) {
            case SOUTH -> new Box(box.minX, box.minY, box.maxZ - 0.05, box.maxX, box.minY + 0.05, box.maxZ);
            case NORTH -> new Box(box.minX, box.minY, box.minZ, box.maxX, box.minY + 0.05, box.minZ + 0.05);
            case EAST -> new Box(box.maxX - 0.05, box.minY, box.minZ, box.maxX, box.minY + 0.05, box.maxZ);
            default -> new Box(box.minX, box.minY, box.minZ, box.minX + 0.05, box.minY + 0.05, box.maxZ);
        };
        WorldRenderer.drawBox(ms, lines, edge, 1f, 1f, 1f, 1f);
        ms.pop();
    }
}
