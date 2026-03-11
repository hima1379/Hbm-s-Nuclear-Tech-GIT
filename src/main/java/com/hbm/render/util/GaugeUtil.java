package com.hbm.render.util;

import org.lwjgl.opengl.GL11;

import com.hbm.lib.RefStrings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;

public class GaugeUtil {

	public static enum Gauge {

		ROUND_SMALL(new ResourceLocation(RefStrings.MODID + ":textures/gui/gauges/small_round.png"), 18, 18, 13),
		ROUND_LARGE(new ResourceLocation(RefStrings.MODID + ":textures/gui/gauges/large_round.png"), 36, 36, 13),
		BOW_SMALL(new ResourceLocation(RefStrings.MODID + ":textures/gui/gauges/small_bow.png"), 18, 18, 13),
		BOW_LARGE(new ResourceLocation(RefStrings.MODID + ":textures/gui/gauges/large_bow.png"), 36, 36, 13),
		WIDE_SMALL(new ResourceLocation(RefStrings.MODID + ":textures/gui/gauges/small_wide.png"), 18, 12, 7),
		WIDE_LARGE(new ResourceLocation(RefStrings.MODID + ":textures/gui/gauges/large_wide.png"), 36, 24, 11),
		BAR_SMALL(new ResourceLocation(RefStrings.MODID + ":textures/gui/gauges/small_bar.png"), 36, 12, 16);

		ResourceLocation texture;
		int width;
		int height;
		int count;

		private Gauge(ResourceLocation texture, int width, int height, int count) {
			this.texture = texture;
			this.width = width;
			this.height = height;
			this.count = count;
		}
	}

	/**
	 * 
	 * @param gauge The gauge enum to use
	 * @param x The x coord in the GUI (left)
	 * @param y The y coord in the GUI (top)
	 * @param z The z-level (from GUI.zLevel)
	 * @param progress Double from 0-1 how far the gauge has progressed
	 */
	public static void renderGauge(Gauge gauge, double x, double y, double z, double progress) {

		Minecraft.getMinecraft().renderEngine.bindTexture(gauge.texture);

		int frameNum = (int) Math.round((gauge.count - 1) * progress);
		double singleFrame = 1D / (double)gauge.count;
		double frameOffset = singleFrame * frameNum;

		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();
		buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
		buf.pos(x, 				 y + gauge.height, 	z).tex(0, 	frameOffset + singleFrame).endVertex();
		buf.pos(x + gauge.width, y + gauge.height,  z).tex(1, 	frameOffset + singleFrame).endVertex();
		buf.pos(x + gauge.width, y, 				z).tex(1, 	frameOffset).endVertex();
		buf.pos(x, 				 y, 				z).tex(0, 	frameOffset).endVertex();
		tess.draw();
	}

	/**
	 * Draws a smooth rotating gauge needle (used for analog-style meters)
	 * @param x Center x coordinate
	 * @param y Center y coordinate
	 * @param z Z-level
	 * @param progress Progress from 0-1
	 * @param tipLength Length of needle tip
	 * @param backLength Length of needle back
	 * @param backSide Width of needle base
	 * @param color Inner needle color
	 */
	public static void drawSmoothGauge(int x, int y, double z, double progress, double tipLength, double backLength, double backSide, int color) {
		drawSmoothGauge(x, y, z, progress, tipLength, backLength, backSide, color, 0x000000);
	}

	/**
	 * Draws a smooth rotating gauge needle with outer glow
	 */
	public static void drawSmoothGauge(int x, int y, double z, double progress, double tipLength, double backLength, double backSide, int color, int colorOuter) {
		GL11.glDisable(GL11.GL_TEXTURE_2D);

		progress = net.minecraft.util.math.MathHelper.clamp(progress, 0, 1);

		// Rotate from -45° to -315° (270° range)
		float angle = (float) Math.toRadians(-progress * 270 - 45);
		double cosAngle = Math.cos(angle);
		double sinAngle = Math.sin(angle);

		// Calculate rotated vertices (needle shape)
		double tipX = -sinAngle * tipLength;
		double tipY = cosAngle * tipLength;
		double leftX = -sinAngle * (-backLength) + cosAngle * backSide;
		double leftY = cosAngle * (-backLength) - sinAngle * backSide;
		double rightX = -sinAngle * (-backLength) + cosAngle * (-backSide);
		double rightY = cosAngle * (-backLength) - sinAngle * (-backSide);

		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();

		// Outer glow triangle
		buf.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
		double mult = 1.5;
		int outerR = (colorOuter >> 16) & 0xFF;
		int outerG = (colorOuter >> 8) & 0xFF;
		int outerB = colorOuter & 0xFF;
		buf.pos(x + tipX * mult, y + tipY * mult, z).color(outerR, outerG, outerB, 255).endVertex();
		buf.pos(x + leftX * mult, y + leftY * mult, z).color(outerR, outerG, outerB, 255).endVertex();
		buf.pos(x + rightX * mult, y + rightY * mult, z).color(outerR, outerG, outerB, 255).endVertex();
		tess.draw();

		// Inner needle triangle
		buf.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
		int innerR = (color >> 16) & 0xFF;
		int innerG = (color >> 8) & 0xFF;
		int innerB = color & 0xFF;
		buf.pos(x + tipX, y + tipY, z).color(innerR, innerG, innerB, 255).endVertex();
		buf.pos(x + leftX, y + leftY, z).color(innerR, innerG, innerB, 255).endVertex();
		buf.pos(x + rightX, y + rightY, z).color(innerR, innerG, innerB, 255).endVertex();
		tess.draw();

		GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
	}

}