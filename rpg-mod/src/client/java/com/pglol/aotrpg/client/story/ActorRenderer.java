package com.pglol.aotrpg.client.story;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.VillagerEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Identifier;

/**
 * Villagers as drawn: always as a person (the player model). Story actors wear their character's skin,
 * everyone else one of the townsfolk skins, or a working skin for a job (commander, ferryman...).
 */
public final class ActorRenderer extends EntityRenderer<VillagerEntity> {
    private final VillagerEntityRenderer villager;
    private final Person person;

    public ActorRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.villager = new VillagerEntityRenderer(ctx);
        this.person = new Person(ctx);
    }

    @Override
    public void render(VillagerEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider vc, int light) {
        // Everyone is a person now: story characters in their own skins, everyone else one of the
        // townsfolk (the same look for the same person, every time).
        person.render(e, yaw, tickDelta, ms, vc, light);
    }

    @Override
    public Identifier getTexture(VillagerEntity e) {
        return person.getTexture(e);
    }

    /** The skin for a villager that isn't in a story scene: by their job title if they have one, else a townsperson. */
    static String skinOf(VillagerEntity e) {
        String s = StoryClient.skin(e.getId());
        if (s != null) return s;
        String name = e.hasCustomName() ? e.getCustomName().getString().toLowerCase(java.util.Locale.ROOT) : "";
        if (name.contains("marleyan officer")) return "marley_officer";
        if (name.contains("marleyan")) return "marley_soldier";
        if (name.contains("raid commander")) return "garrison_captain";
        if (name.contains("ferry")) return "farmer";
        if (name.contains("stable")) return "farmer";
        if (name.contains("builder")) return "civilian_m";
        if (name.contains("hooded stranger")) return "smuggler";
        if (name.contains("armorer")) return "merchant";
        if (name.contains("provisioner")) return "baker";
        if (name.contains("toolmaker")) return "farmer";
        if (name.contains("bladesmith")) return "garrison_soldier";
        if (name.contains("merchant")) return "merchant";
        long h = e.getUuid().getLeastSignificantBits() ^ e.getUuid().getMostSignificantBits();
        // Townsfolk skins alternate men and women; match the name the server gives them.
        int i = (int) Math.floorMod(h >> 5, 40L) * 2 + (int) (h & 1);
        return String.format("folk_%02d", i);
    }

    /** A story character: the player model in a skin from assets/aot_rpg/textures/entity/actor. */
    static final class Person extends LivingEntityRenderer<VillagerEntity, PlayerEntityModel<VillagerEntity>> {
        Person(EntityRendererFactory.Context ctx) {
            super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
            // What they wear and hold, drawn like a player's: uniform, harness, boots and blades.
            addFeature(new net.minecraft.client.render.entity.feature.ArmorFeatureRenderer<>(this,
                new net.minecraft.client.render.entity.model.ArmorEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
                new net.minecraft.client.render.entity.model.ArmorEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
            addFeature(new net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer<>(this, ctx.getHeldItemRenderer()));
        }

        @Override
        public Identifier getTexture(VillagerEntity e) {
            return Identifier.of("aot_rpg", "textures/entity/actor/" + skinOf(e) + ".png");
        }

        @Override
        public void render(VillagerEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider vc, int light) {
            this.model.sneaking = e.isInSneakingPose();
            // Blades held out in front, like a cadet in the air, rather than hanging.
            boolean armed = !e.getMainHandStack().isEmpty();
            this.model.rightArmPose = armed ? net.minecraft.client.render.entity.model.BipedEntityModel.ArmPose.ITEM : net.minecraft.client.render.entity.model.BipedEntityModel.ArmPose.EMPTY;
            this.model.leftArmPose = !e.getOffHandStack().isEmpty() ? net.minecraft.client.render.entity.model.BipedEntityModel.ArmPose.ITEM : net.minecraft.client.render.entity.model.BipedEntityModel.ArmPose.EMPTY;
            super.render(e, yaw, tickDelta, ms, vc, light);
        }

        @Override
        protected void scale(VillagerEntity e, MatrixStack ms, float tickDelta) {
            float k = e.isBaby() ? 0.55f : 0.9375f;
            ms.scale(k, k, k);
        }
    }
}
