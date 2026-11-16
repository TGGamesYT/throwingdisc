package dev.tggamesyt.throwingdisc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.tggamesyt.throwingdisc.ThrowingDisc;
import dev.tggamesyt.throwingdisc.ThrownDisc;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;

public class ThrowingDiscClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ThrowingDisc.THROWN_DISC, DiscRenderer::new);
    }

    private static final class DiscState extends EntityRenderState {
        float spin;
        float yRot;
        float xRot;
        final ItemStackRenderState item = new ItemStackRenderState();
    }

    /** Draws the disc item spinning, lying along its flight. */
    private static final class DiscRenderer extends EntityRenderer<ThrownDisc, DiscState> {
        /** Degrees per tick. */
        private static final float SPIN_SPEED = 50.0F;
        private final ItemModelResolver items;

        DiscRenderer(EntityRendererProvider.Context context) {
            super(context);
            this.items = context.getItemModelResolver();
        }

        @Override
        public DiscState createRenderState() {
            return new DiscState();
        }

        @Override
        public void extractRenderState(ThrownDisc entity, DiscState state, float partialTick) {
            super.extractRenderState(entity, state, partialTick);
            state.spin = (entity.tickCount + partialTick) * SPIN_SPEED;
            state.yRot = entity.getYRot(partialTick);
            state.xRot = entity.getXRot(partialTick);
            items.updateForNonLiving(state.item, entity.discStack(), ItemDisplayContext.NONE, entity);
        }

        @Override
        public void submit(DiscState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
            if (!state.item.isEmpty()) {
                poseStack.pushPose();
                poseStack.translate(0.0F, 0.125F, 0.0F);
                // Along the flight (tilted up or down like it flies), then spinning in its own plane.
                poseStack.rotateDegrees(Axis.YP, -state.yRot);
                poseStack.rotateDegrees(Axis.XP, state.xRot);
                poseStack.rotateDegrees(Axis.YP, state.spin);
                poseStack.rotateDegrees(Axis.XP, 90.0F);
                poseStack.scale(0.9F, 0.9F, 0.9F);
                state.item.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
                poseStack.popPose();
            }
            super.submit(state, poseStack, collector, camera);
        }
    }
}
