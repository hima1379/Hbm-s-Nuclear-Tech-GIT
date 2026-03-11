package com.hbm.render.tileentity;

import org.lwjgl.opengl.GL11;
import com.hbm.main.ResourceManager;
import com.hbm.main.tileentity.network.data.TileEntitySPG62;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;

public class RenderSPG62 extends TileEntitySpecialRenderer<TileEntitySPG62> {

	@Override
	public void render(TileEntitySPG62 te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
		GL11.glPushMatrix();

		// Initial setup: translate to block center and fix orientation
		GL11.glTranslated(x + 0.5D, y, z + 0.5D);
		GlStateManager.enableLighting();
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glRotatef(180, 0F, 1F, 0F);  // Fix model orientation

		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		this.bindTexture(ResourceManager.spg62_tex);

		// Part 1: Render SPG62_base (static, no rotation)
		ResourceManager.spg62.renderPart("SPG62_base");

		// Part 2: Render SPG62_roll (azimuth rotation only)
		GL11.glPushMatrix();
		GL11.glRotated(te.azimuthAngle, 0F, -1F, 0F);  // Apply azimuth rotation
		ResourceManager.spg62.renderPart("SPG62_roll");
		GL11.glPopMatrix();

		// Part 3: Render SPG62_antenna (azimuth + elevation rotation)
		// Antenna pivot point (bottom center): (0, 1.296799, 0.702313)
		// This is where the antenna connects to the roll mounting bracket
		GL11.glPushMatrix();

		// Apply azimuth rotation around base center first
		GL11.glRotated(te.azimuthAngle, 0F, -1F, 0F);

		// Translate to antenna's pivot point for elevation rotation
		// The antenna's bottom center is at (0, 1.296799, 0.702313) in model space
		GL11.glTranslated(0.0D, 1.296799D, 0.702313D);

		// Apply elevation rotation around the antenna's pivot point
		GL11.glRotated(te.elevationAngle, 1F, 0F, 0F);

		// Translate back so the antenna geometry renders at correct position
		GL11.glTranslated(0.0D, -1.296799D, -0.702313D);

		ResourceManager.spg62.renderPart("SPG62_antenna");
		GL11.glPopMatrix();

		GlStateManager.shadeModel(GL11.GL_FLAT);
		GL11.glPopMatrix();
	}
}
