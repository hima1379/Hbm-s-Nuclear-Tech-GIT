package com.hbm.render.tileentity;

import org.lwjgl.opengl.GL11;

import com.hbm.blocks.BlockDummyable;
import com.hbm.main.ResourceManager;
import com.hbm.main.tileentity.network.data.TileEntitySPY1;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;

/**
 * TESR for SPY-1 Phased Array Radar
 * Renders 3D OBJ model with texture
 *
 * Model dimensions: 12×6×12 units (XYZ)
 * Block dimensions: 12×7×12 blocks (realistic SPY-1 scale)
 * Scale: ~1.0 for realistic size (12.2m × 12.2m array)
 */
public class RenderSPY1 extends TileEntitySpecialRenderer<TileEntitySPY1> {

	@Override
	public boolean isGlobalRenderer(TileEntitySPY1 te) {
		return false;
	}

	@Override
	public void render(TileEntitySPY1 te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
		GL11.glPushMatrix();

		// Translate to block position
		// Y offset adjusted to align model with ground level
		GL11.glTranslatef((float)x + 0.5F, (float)y + 1.0F, (float)z + 0.5F);

		GlStateManager.disableCull();  // Disable culling to show all faces including ceiling
		GlStateManager.enableLighting();

		// Rotate based on block metadata (facing direction)
		// Same rotation logic as FCS Console
		switch(te.getBlockMetadata() - BlockDummyable.offset) {
		case 2: GL11.glRotatef(90, 0F, 1F, 0F); break;   // SOUTH
		case 4: GL11.glRotatef(180, 0F, 1F, 0F); break;  // WEST
		case 3: GL11.glRotatef(270, 0F, 1F, 0F); break;  // NORTH
		case 5: GL11.glRotatef(0, 0F, 1F, 0F); break;    // EAST
		}

		// Offset for multiblock positioning
		// Model is centered, offset to align with 12×7×12 multiblock
		// Core is at center, so offset by -6 blocks in X and Z
		GL11.glTranslated(0, 0, 0);

		// Scale model to realistic size
		// Model is 12×6×12 units, we want ~12 blocks width (realistic SPY-1)
		// Scale factor: 12 blocks / 12 units = 1.0
		GL11.glScaled(1.0, 1.0, 1.0);

		GlStateManager.shadeModel(GL11.GL_SMOOTH);

		// Bind texture and render model
		bindTexture(ResourceManager.spy1_radar_tex);
		ResourceManager.spy1_radar.renderAll();

		GlStateManager.shadeModel(GL11.GL_FLAT);

		GL11.glPopMatrix();
	}
}
