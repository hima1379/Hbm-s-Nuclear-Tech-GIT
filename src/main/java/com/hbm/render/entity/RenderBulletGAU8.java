package com.hbm.render.entity;

import org.lwjgl.opengl.GL11;

import com.hbm.entity.projectile.EntityBulletGAU8;
import com.hbm.lib.RefStrings;
import com.hbm.render.model.ModelBullet;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.registry.IRenderFactory;

/**
 * GAU-8 30mm弾丸のレンダラー
 * 30mm弾として適切なサイズと外観でレンダリング
 */
public class RenderBulletGAU8 extends Render<EntityBulletGAU8> {

    public static final IRenderFactory<EntityBulletGAU8> FACTORY = (RenderManager man) -> {
        return new RenderBulletGAU8(man);
    };

    private ModelBullet bullet;
    private ResourceLocation bulletTexture = new ResourceLocation(RefStrings.MODID + ":textures/models/projectiles/bullet.png");

    protected RenderBulletGAU8(RenderManager renderManager) {
        super(renderManager);
        bullet = new ModelBullet();
    }

    @Override
    public void doRender(EntityBulletGAU8 entity, double x, double y, double z, float entityYaw, float partialTicks) {
        GL11.glPushMatrix();

        // 位置に移動
        GL11.glTranslatef((float) x, (float) y, (float) z);

        // 弾丸の向きに回転
        float yaw = entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
        float pitch = entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;

        GL11.glRotatef(yaw - 90.0F, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(pitch + 180.0F, 0.0F, 0.0F, 1.0F);

        // 30mm弾として適切なサイズにスケール（通常の2倍）
        GL11.glScalef(2.0F, 2.0F, 2.0F);

        // テクスチャをバインド
        bindTexture(bulletTexture);

        // ライティングを有効化
        GlStateManager.enableLighting();

        // モデルをレンダリング
        bullet.renderAll(0.0625F);

        GL11.glPopMatrix();
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityBulletGAU8 entity) {
        return bulletTexture;
    }
}
