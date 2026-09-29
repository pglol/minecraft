package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * The balloon's stations (stash, workbench, forge, lobby board, benches) answer a right-click
 * directly: the block you're looking at is found by a ray that ignores entities (invisible seats
 * and name-tag stands included), and the server is asked to open it. Nothing else can eat the click.
 */
public final class BalloonStations {
    private BalloonStations() {}

    private static long last;

    /** The balloons' world: their own sky (the home world on servers from before it existed). */
    public static boolean skyWorld(net.minecraft.world.World w) {
        if (w == null) return false;
        String id = w.getRegistryKey().getValue().toString();
        return id.equals("aot_rpg:sky") || id.equals("aot_rpg:homes");
    }

    public static boolean aboard(MinecraftClient mc) {
        return mc.player != null && mc.world != null && mc.player.getX() < -399_744
            && BalloonStations.skyWorld(mc.world);
    }

    static boolean station(BlockState st) {
        return st.isOf(Blocks.ENDER_CHEST) || st.isOf(Blocks.CRAFTING_TABLE) || st.isIn(BlockTags.ANVIL) || st.isOf(Blocks.BLAST_FURNACE)
            || st.isOf(Blocks.GRINDSTONE) || st.isOf(Blocks.LECTERN) || st.getBlock() instanceof StairsBlock;
    }

    /** True when a station took the click. */
    public static boolean use(MinecraftClient mc) {
        if (!aboard(mc) || mc.currentScreen != null || mc.player.isSpectator()) return false;
        HitResult hit = mc.player.raycast(5.0, 0, false);
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) return false;
        if (!station(mc.world.getBlockState(bh.getBlockPos()))) return false;
        long now = System.currentTimeMillis();
        if (now - last < 250) return true;
        last = now;
        ClientPlayNetworking.send(new Net.ExtractionAction("station", String.valueOf(bh.getBlockPos().asLong())));
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }
}
