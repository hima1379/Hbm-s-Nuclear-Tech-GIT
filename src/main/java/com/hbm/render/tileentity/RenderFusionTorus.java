package com.hbm.render.tileentity;

import org.lwjgl.opengl.GL11;

import com.hbm.inventory.recipes.FusionRecipe;
import com.hbm.main.MainRegistry;
import com.hbm.main.ResourceManager;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionTorus;
import com.hbm.util.BobMathUtil;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;

public class RenderFusionTorus extends TileEntitySpecialRenderer<TileEntityFusionTorus> {

	@Override
	public boolean isGlobalRenderer(TileEntityFusionTorus te) {
		return true;
	}

	@Override
	public void render(TileEntityFusionTorus te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
		GL11.glPushMatrix();
		GL11.glTranslated(x + 0.5, y, z + 0.5);
		GlStateManager.enableLighting();
		GlStateManager.enableCull();

		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		bindTexture(ResourceManager.fusion_torus_tex);
		ResourceManager.fusion_torus.renderPart("Torus");

		// Rotating magnet
		GL11.glPushMatrix();
		float rot = te.prevMagnet + (te.magnet - te.prevMagnet) * partialTicks;
		GL11.glRotatef(rot, 0, 1, 0);
		ResourceManager.fusion_torus.renderPart("Magnet");
		GL11.glPopMatrix();

		// Connection bolts
		if(te.connections[0]) ResourceManager.fusion_torus.renderPart("Bolts2");
		if(te.connections[1]) ResourceManager.fusion_torus.renderPart("Bolts4");
		if(te.connections[2]) ResourceManager.fusion_torus.renderPart("Bolts3");
		if(te.connections[3]) ResourceManager.fusion_torus.renderPart("Bolts1");

		FusionRecipe recipe = (FusionRecipe) te.fusionModule.getRecipe();

		// Plasma rendering
		if(te.plasmaEnergy > 0 && recipe != null) {
			long time = System.currentTimeMillis() + te.timeOffset;

			float plasmaAlpha = 0.35F + (float) (Math.sin(time / 1000D) * 0.25F);

			float r = recipe.r;
			float g = recipe.g;
			float b = recipe.b;

			double mainOsc = BobMathUtil.sps(time / 1000D) % 1D;
			double glowOsc = Math.sin(time / 2000D) % 1D;
			double glowExtra = time / 10000D % 1D;
			double sparkleSpin = time / 500D * -1 % 1D;
			double sparkleOsc = Math.sin(time / 1000D) * 0.5D % 1D;

			OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
			GlStateManager.disableCull();
			GlStateManager.disableLighting();

			GL11.glPushAttrib(GL11.GL_LIGHTING_BIT);
			GlStateManager.disableAlpha();
			GlStateManager.enableBlend();
			GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
			GlStateManager.depthMask(false);

			GL11.glColor4f(r, g, b, plasmaAlpha);

			// Main plasma layer
			GL11.glMatrixMode(GL11.GL_TEXTURE);
			GL11.glLoadIdentity();
			bindTexture(ResourceManager.fusion_plasma_tex);
			GL11.glTranslated(0, mainOsc, 0);
			ResourceManager.fusion_torus.renderPart("Plasma");
			GL11.glMatrixMode(GL11.GL_TEXTURE);
			GL11.glLoadIdentity();
			GL11.glMatrixMode(GL11.GL_MODELVIEW);

			// Extra layers for nearby viewing
			if(MainRegistry.proxy.me().getDistanceSq(te.getPos().getX() + 0.5, te.getPos().getY() + 2.5, te.getPos().getZ() + 0.5) < 100 * 100) {
				// Glow layer
				GL11.glColor4f(r * 2, g * 2, b * 2, plasmaAlpha * 2);

				GL11.glMatrixMode(GL11.GL_TEXTURE);
				GL11.glLoadIdentity();
				bindTexture(ResourceManager.fusion_plasma_glow_tex);
				GL11.glTranslated(0, glowOsc + glowExtra, 0);
				ResourceManager.fusion_torus.renderPart("Plasma");
				GL11.glMatrixMode(GL11.GL_TEXTURE);
				GL11.glLoadIdentity();
				GL11.glMatrixMode(GL11.GL_MODELVIEW);

				// Sparkle layer
				GL11.glColor4f(r * 2, g * 2, b * 2, 0.75F);

				GL11.glMatrixMode(GL11.GL_TEXTURE);
				GL11.glLoadIdentity();
				bindTexture(ResourceManager.fusion_plasma_sparkle_tex);
				GL11.glTranslated(sparkleSpin, sparkleOsc, 0);
				ResourceManager.fusion_torus.renderPart("Plasma");
				GL11.glMatrixMode(GL11.GL_TEXTURE);
				GL11.glLoadIdentity();
				GL11.glMatrixMode(GL11.GL_MODELVIEW);
			}

			GlStateManager.enableLighting();
			GlStateManager.enableAlpha();
			GlStateManager.disableBlend();
			GlStateManager.depthMask(true);
			GL11.glPopAttrib();

			GlStateManager.enableCull();
		}

		GlStateManager.shadeModel(GL11.GL_FLAT);
		GL11.glPopMatrix();
	}
}
