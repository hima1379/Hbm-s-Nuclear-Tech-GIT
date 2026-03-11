package com.hbm.render.tileentity;

import org.lwjgl.opengl.GL11;

import com.hbm.blocks.BlockDummyable;
import com.hbm.main.ResourceManager;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionKlystron;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;

public class RenderFusionKlystron extends TileEntitySpecialRenderer<TileEntityFusionKlystron> {

	@Override
	public boolean isGlobalRenderer(TileEntityFusionKlystron te) {
		return true;
	}

	@Override
	public void render(TileEntityFusionKlystron te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
		GL11.glPushMatrix();
		GL11.glTranslated(x + 0.5, y, z + 0.5);
		GlStateManager.enableLighting();
		GlStateManager.enableCull();

		switch(te.getBlockMetadata() - BlockDummyable.offset) {
		case 2: GL11.glRotatef(90, 0F, 1F, 0F); break;
		case 4: GL11.glRotatef(180, 0F, 1F, 0F); break;
		case 3: GL11.glRotatef(270, 0F, 1F, 0F); break;
		case 5: GL11.glRotatef(0, 0F, 1F, 0F); break;
		}

		GL11.glTranslated(-1, 0, 0);

		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		bindTexture(ResourceManager.fusion_klystron_tex);
		ResourceManager.fusion_klystron.renderPart("Klystron");

		// Fan animation
		GL11.glPushMatrix();
		float rot = te.prevFan + (te.fan - te.prevFan) * partialTicks;
		GL11.glTranslated(0, 2.5, 0);
		GL11.glRotated(rot, 1, 0, 0);
		GL11.glTranslated(0, -2.5, 0);
		ResourceManager.fusion_klystron.renderPart("Rotor");
		GL11.glPopMatrix();

		GlStateManager.shadeModel(GL11.GL_FLAT);
		GL11.glPopMatrix();
	}
}
