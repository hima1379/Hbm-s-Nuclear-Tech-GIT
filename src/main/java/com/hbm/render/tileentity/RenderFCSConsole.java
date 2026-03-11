package com.hbm.render.tileentity;

import org.lwjgl.opengl.GL11;

import com.hbm.blocks.BlockDummyable;
import com.hbm.main.ResourceManager;
import com.hbm.main.tileentity.network.data.TileEntityFCSConsole;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;

/**
 * TESR for FCS Console - renders 3D OBJ model with textures
 * Uses SAME rendering as RBMK Console for consistency
 */
public class RenderFCSConsole extends TileEntitySpecialRenderer<TileEntityFCSConsole> {

	@Override
	public boolean isGlobalRenderer(TileEntityFCSConsole te) {
		return false;
	}

	@Override
	public void render(TileEntityFCSConsole te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
		GL11.glPushMatrix();

		// EXACT SAME as RBMKConsole rendering
		GL11.glTranslatef((float)x + 0.5F, (float)y, (float)z + 0.5F);

		GlStateManager.enableCull();
		GlStateManager.enableLighting();

		// EXACT SAME rotation logic as RBMK Console
		switch(te.getBlockMetadata() - BlockDummyable.offset) {
		case 2: GL11.glRotatef(90, 0F, 1F, 0F); break;   // SOUTH
		case 4: GL11.glRotatef(180, 0F, 1F, 0F); break;  // WEST
		case 3: GL11.glRotatef(270, 0F, 1F, 0F); break;  // NORTH
		case 5: GL11.glRotatef(0, 0F, 1F, 0F); break;    // EAST
		}

		// EXACT SAME offset as RBMK Console
		GL11.glTranslated(0.5, 0, 0);

		GlStateManager.shadeModel(GL11.GL_SMOOTH);

		// Render main console body with main texture
		bindTexture(ResourceManager.fcs_console_main_tex);
		ResourceManager.fcs_console.renderPart("Cube");
		ResourceManager.fcs_console.renderPart("Main");

		// Render sub screens with sub texture
		bindTexture(ResourceManager.fcs_console_sub_tex);
		ResourceManager.fcs_console.renderPart("Sub1");
		ResourceManager.fcs_console.renderPart("Sub2");
		ResourceManager.fcs_console.renderPart("Sub3");
		ResourceManager.fcs_console.renderPart("Sub4");

		GlStateManager.shadeModel(GL11.GL_FLAT);

		GL11.glPopMatrix();
	}
}
