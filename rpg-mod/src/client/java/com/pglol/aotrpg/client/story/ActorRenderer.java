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
 * Villagers as drawn: an ordinary villager, unless it's a story actor in your scene, which is
 * drawn as a person (the player model) in their own skin.
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
        if (StoryClient.skin(e.getId()) != null) person.render(e, yaw, tickDelta, ms, vc, light);
        else villager.render(e, yaw, tickDelta, ms, vc, light);
    }

    @Override
    public Identifier getTexture(VillagerEntity e) {
        return StoryClient.skin(e.getId()) != null ? person.getTexture(e) : villager.getTexture(e);
    }

    /** A story character: the player model in a skin from assets/aot_rpg/textures/entity/actor. */
    static final class Person extends LivingEntityRenderer<VillagerEntity, PlayerEntityModel<VillagerEntity>> {
        Person(EntityRendererFactory.Context ctx) {
            super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        }

        @Override
        public Identifier getTexture(VillagerEntity e) {
            String skin = StoryClient.skin(e.getId());
            return Identifier.of("aot_rpg", "textures/entity/actor/" + (skin == null ? "civilian_m" : skin) + ".png");
        }

        @Override
        protected void scale(VillagerEntity e, MatrixStack ms, float tickDelta) {
            ms.scale(0.9375f, 0.9375f, 0.9375f);
        }
    }
}
