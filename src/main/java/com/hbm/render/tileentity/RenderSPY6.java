package com.hbm.render.tileentity;

import org.lwjgl.opengl.GL11;

import com.hbm.blocks.BlockDummyable;
import com.hbm.main.ResourceManager;
import com.hbm.main.tileentity.network.data.TileEntitySPY6;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;

/**
 * TESR for SPY-6 AESA Radar
 * Renders 3D OBJ model (SPY-6.obj) with texture (SPY6_radar_texture.png)
 *
 * Same multiblock footprint as SPY-1 (12x7x12).
 * Model is loaded from models/spy6/SPY-6.obj.
 */
public class RenderSPY6 extends TileEntitySpecialRenderer<TileEntitySPY6> {

	@Override
	public boolean isGlobalRenderer(TileEntitySPY6 te) {
		return false;
	}

	@Override
	public void render(TileEntitySPY6 te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
		GL11.glPushMatrix();

		GL11.glTranslatef((float)x + 0.5F, (float)y + 6.0F, (float)z + 0.5F);

		GlStateManager.disableCull();
		GlStateManager.enableLighting();

		// Rotate based on block metadata (facing direction) — same logic as RenderSPY1
		switch(te.getBlockMetadata() - BlockDummyable.offset) {
		case 2: GL11.glRotatef( 90, 0F, 1F, 0F); break;  // SOUTH
		case 4: GL11.glRotatef(180, 0F, 1F, 0F); break;  // WEST
		case 3: GL11.glRotatef(270, 0F, 1F, 0F); break;  // NORTH
		case 5: GL11.glRotatef(  0, 0F, 1F, 0F); break;  // EAST
		}

		GL11.glTranslated(0, 0, 0);
		GL11.glScaled(1.0, 1.0, 1.0);

		GlStateManager.shadeModel(GL11.GL_SMOOTH);

		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		bindTexture(ResourceManager.spy6_radar_tex);
		ResourceManager.spy6_radar.renderAll();

		GlStateManager.shadeModel(GL11.GL_FLAT);

		GL11.glPopMatrix();
	}
}
