package com.hbm.render.entity.missile;

import org.lwjgl.opengl.GL11;

import com.hbm.entity.missile.EntityMissileSM6;
import com.hbm.main.ResourceManager;
import com.hbm.render.RenderHelper;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.registry.IRenderFactory;

/**
 * Renderer for SM-6 (RIM-174 Standard ERAM) Missile
 *
 * Features:
 * - Two-stage rendering (booster + main body)
 * - Booster separates after 60 ticks (3 seconds)
 * - Proper orientation based on flight path
 */
public class RenderMissileSM6 extends Render<EntityMissileSM6> {

    public static final IRenderFactory<EntityMissileSM6> FACTORY = (RenderManager man) -> {
        return new RenderMissileSM6(man);
    };

    // Booster separation time (ticks)
    private static final int BOOSTER_SEPARATION_TIME = 60;

    protected RenderMissileSM6(RenderManager renderManager) {
        super(renderManager);
    }

    @Override
    public void doRender(EntityMissileSM6 missile, double x, double y, double z, float entityYaw, float partialTicks) {
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_LIGHTING_BIT);

        // Enable lighting for realistic shading
        GlStateManager.enableLighting();

        // Get interpolated render position for smooth movement
        double[] pos = RenderHelper.getRenderPosFromMissile(missile, partialTicks);
        x = pos[0];
        y = pos[1];
        z = pos[2];

        // Translate to missile position
        GL11.glTranslated(x, y, z);

        // Rotate to match missile orientation
        // Yaw rotation (horizontal direction)
        GL11.glRotatef(
            missile.prevRotationYaw + (missile.rotationYaw - missile.prevRotationYaw) * partialTicks - 90.0F,
            0.0F, 1.0F, 0.0F
        );

        // Pitch rotation (vertical angle)
        GL11.glRotatef(
            missile.prevRotationPitch + (missile.rotationPitch - missile.prevRotationPitch) * partialTicks,
            0.0F, 0.0F, 1.0F
        );

        // Scale to realistic size
        // SM-6: 6.55m actual length, model is ~197 units tall (Y-axis)
        // Other missiles like missile_strong are ~12 units for similar physical size
        // Scale factor: 12/197 ≈ 0.061
        GL11.glScalef(0.061F, 0.061F, 0.061F);

        // Enable smooth shading for better visual quality
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        // Render main missile body
        renderMainBody(missile);

        // Render booster if still attached (first 60 ticks)
        if (missile.ticksExisted < BOOSTER_SEPARATION_TIME) {
            renderBooster(missile);
        }

        // Restore flat shading
        GlStateManager.shadeModel(GL11.GL_FLAT);

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    /**
     * Render the main SM-6 body (always visible)
     */
    private void renderMainBody(EntityMissileSM6 missile) {
        GL11.glPushMatrix();

        // Bind main body texture
        bindTexture(ResourceManager.sm6_main_tex);

        // Render main body model
        ResourceManager.sm6_main.renderAll();

        GL11.glPopMatrix();
    }

    /**
     * Render the Mk 72 booster stage (visible only during boost phase)
     */
    private void renderBooster(EntityMissileSM6 missile) {
        GL11.glPushMatrix();

        // Booster model is already positioned correctly relative to main body
        // Main body: Y from 0 to 197
        // Booster: Y from -52.3 to 0.4 (attached below main body)
        // No additional translation needed

        // Bind booster texture
        bindTexture(ResourceManager.sm6_booster_tex);

        // Render booster model
        ResourceManager.sm6_booster.renderAll();

        GL11.glPopMatrix();
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityMissileSM6 entity) {
        // Return main texture as default
        // (Individual textures are bound in render methods)
        return ResourceManager.sm6_main_tex;
    }
}
