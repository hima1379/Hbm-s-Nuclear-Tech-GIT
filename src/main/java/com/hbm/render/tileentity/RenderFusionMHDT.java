package com.hbm.render.tileentity;

import org.lwjgl.opengl.GL11;

import com.hbm.blocks.BlockDummyable;
import com.hbm.main.ResourceManager;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionMHDT;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;

public class RenderFusionMHDT extends TileEntitySpecialRenderer<TileEntityFusionMHDT> {

	@Override
	public boolean isGlobalRenderer(TileEntityFusionMHDT te) {
		return true;
	}

	@Override
	public void render(TileEntityFusionMHDT te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
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

		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		bindTexture(ResourceManager.fusion_mhdt_tex);
		ResourceManager.fusion_mhdt.renderPart("Turbine");

		// Rotating coils (MHD plasma flow visualization)
		// Rotation speed is based on MHD physics: plasma velocity and magnetic field strength
		GL11.glPushMatrix();
		// Use full 360-degree rotation for smooth animation
		// rotorSpeed is scaled to plasma flow rate (0-20 for fast plasma flow at 150 MK)
		float interpolatedRotor = te.prevRotor + (te.rotor - te.prevRotor) * partialTicks;
		// Convert rotor position to degrees (rotor accumulates, so we use modulo for wrap-around)
		float rotationDegrees = (interpolatedRotor * 360.0f / 20.0f) % 360.0f;
		GL11.glTranslated(0, 1.5, 0);
		GL11.glRotated(rotationDegrees, 1, 0, 0);
		GL11.glTranslated(0, -1.5, 0);
		ResourceManager.fusion_mhdt.renderPart("Coils");
		GL11.glPopMatrix();

		GlStateManager.shadeModel(GL11.GL_FLAT);
		GL11.glPopMatrix();
	}
}
