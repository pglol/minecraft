package com.pglol.aotrpg;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * The Gas Refueler: a pressurised tank you set down anywhere you can build (in the open world
 * and in Extraction). Hold use on it and it fills the ODM gear you're wearing, about six seconds
 * from empty to full. Engineers craft them at a forge (Field Workshop); anyone can find one.
 */
public final class Refueler {
    private Refueler() {}

    public static final Identifier ID = Identifier.of("aot_rpg", "gas_refueler");
    public static Block BLOCK;
    public static Item ITEM;

    /** Each use while held (the game repeats it every 4 ticks) adds a thirtieth: ~6s from empty. */
    private static final double PER_USE = 1 / 30.0;

    public static void register() {
        BLOCK = Registry.register(Registries.BLOCK, ID, new TankBlock(AbstractBlock.Settings.create()
            .strength(2.5f, 6f).sounds(BlockSoundGroup.COPPER).nonOpaque()));
        ITEM = Registry.register(Registries.ITEM, ID, new BlockItem(BLOCK, new Item.Settings().maxCount(4)));
    }

    public static final class TankBlock extends Block {
        private static final VoxelShape SHAPE = Block.createCuboidShape(2, 0, 2, 14, 16, 14);

        public TankBlock(Settings settings) {
            super(settings);
        }

        @Override
        protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
            return SHAPE;
        }

        @Override
        protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (world.isClient) return ActionResult.SUCCESS;
            if (!(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
            if (OdmBoost.gearOn(p)) {
                if (OdmBoost.refuel(p, PER_USE)) {
                    ServerWorld w = (ServerWorld) world;
                    w.spawnParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 3, 0.15, 0.05, 0.15, 0.02);
                    if (p.age % 8 == 0) w.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.35f, 1.8f);
                    float g = OdmBoost.gas(p);
                    p.sendMessage(Text.literal("Refueling  " + bar(g) + "  " + Math.round(g * 100) + "%").formatted(Formatting.AQUA), true);
                } else {
                    p.sendMessage(Text.literal("Tank full").formatted(Formatting.GREEN), true);
                }
            } else {
                p.sendMessage(Text.literal("Wear your ODM gear to refuel it").formatted(Formatting.GRAY), true);
            }
            return ActionResult.SUCCESS;
        }
    }

    private static String bar(float f) {
        int n = Math.round(Math.max(0, Math.min(1, f)) * 12);
        return "▮".repeat(n) + "▯".repeat(12 - n);
    }
}
