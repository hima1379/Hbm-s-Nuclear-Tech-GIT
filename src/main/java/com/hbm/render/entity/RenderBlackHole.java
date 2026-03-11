package com.hbm.render.entity;

import java.util.Random;

import org.lwjgl.opengl.GL11;

import com.hbm.entity.effect.EntityBlackHole;
import com.hbm.entity.effect.EntityRagingVortex;
import com.hbm.entity.effect.EntityVortex;
import com.hbm.forgefluid.FFUtils;
import com.hbm.lib.RefStrings;
import com.hbm.main.ClientProxy;
import com.hbm.render.amlfrom1710.AdvancedModelLoader;
import com.hbm.render.amlfrom1710.IModelCustom;
import com.hbm.render.amlfrom1710.Vec3;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.registry.IRenderFactory;

public class RenderBlackHole extends Render<EntityBlackHole> {

	public static final IRenderFactory<EntityBlackHole> FACTORY = (RenderManager man) -> {
		return new RenderBlackHole(man);
	};

	protected static final ResourceLocation objTesterModelRL = new ResourceLocation(RefStrings.MODID, "models/Sphere.obj");
	protected IModelCustom blastModel;
	protected ResourceLocation hole = new ResourceLocation(RefStrings.MODID, "textures/models/explosion/BlackHole.png");
	protected ResourceLocation swirl = new ResourceLocation(RefStrings.MODID, "textures/entity/bhole.png");
	protected ResourceLocation disc = new ResourceLocation(RefStrings.MODID, "textures/entity/bholeDisc.png");

	// シュヴァルツシルト半径が1mm以上になったら黒球を描写
	private static final double RENDER_THRESHOLD_METERS = 1e-3; // 1mm

	protected RenderBlackHole(RenderManager renderManager){
		super(renderManager);
		blastModel = AdvancedModelLoader.loadModel(objTesterModelRL);
	}

	@Override
	public void doRender(EntityBlackHole entity, double x, double y, double z, float entityYaw, float partialTicks){
		if(!ClientProxy.renderingConstant)
			return;

		// プレイヤーからの距離を計算
		double dx = entity.posX - renderManager.renderViewEntity.posX;
		double dy = entity.posY - renderManager.renderViewEntity.posY;
		double dz = entity.posZ - renderManager.renderViewEntity.posZ;
		double distanceSquared = dx * dx + dy * dy + dz * dz;
		double distance = Math.sqrt(distanceSquared);

		GL11.glPushMatrix();
		GL11.glTranslatef((float)x, (float)y, (float)z);
		GlStateManager.disableLighting();
		GlStateManager.disableCull();

		// サブクラス(Vortex等)の場合は従来通りSIZEを使用
		if(entity instanceof EntityVortex || entity instanceof EntityRagingVortex) {
			float size = entity.getDataManager().get(EntityBlackHole.SIZE);
			GL11.glScalef(size, size, size);

			bindTexture(hole);
			blastModel.renderAll();

			if(entity instanceof EntityVortex) {
				renderSwirl(entity, partialTicks);
			} else if(entity instanceof EntityRagingVortex) {
				renderSwirl(entity, partialTicks);
				renderJets(entity, partialTicks);
			}

		} else {
			// 物理ブラックホールの場合
			double currentRadius = entity.getCurrentSchwarzschildRadius();

			// 膠着円盤は常に描写(無限距離から可視)
			// 距離に応じてLOD(Level of Detail)を適用
			renderDynamicDiscWithLOD(entity, partialTicks, distance);

			// ジェットは近距離のみ描写
			if(distance < 5000.0) {
				renderJets(entity, partialTicks);
			}

			// シュヴァルツシルト半径が1mm以上で、かつ近距離でのみ黒球を描写
			if(currentRadius >= RENDER_THRESHOLD_METERS && distance < 1000.0) {
				// シュヴァルツシルト半径をMinecraftスケールに変換(1ブロック = 1m)
				float visualSize = (float)(currentRadius); // メートル単位

				GL11.glPushMatrix();
				GL11.glScalef(visualSize, visualSize, visualSize);
				bindTexture(hole);
				blastModel.renderAll();
				GL11.glPopMatrix();
			}
		}

		GlStateManager.enableCull();
		GlStateManager.enableLighting();

		GL11.glPopMatrix();
	}

	/**
	 * 距離に応じたLOD付き動的膠着円盤レンダリング
	 * 遠距離でも見えるが、詳細度を下げる
	 */
	protected void renderDynamicDiscWithLOD(EntityBlackHole entity, float interp, double distance) {
		// 距離に応じてLODを決定
		int layers;
		int segments;

		if(distance < 500.0) {
			// 近距離: 最高品質
			layers = steps();
			segments = 32;
		} else if(distance < 2000.0) {
			// 中距離: 中品質
			layers = Math.max(steps() / 2, 5);
			segments = 16;
		} else if(distance < 10000.0) {
			// 遠距離: 低品質
			layers = Math.max(steps() / 4, 3);
			segments = 8;
		} else {
			// 超遠距離: 最低品質（単純な円盤）
			layers = 2;
			segments = 6;
		}

		renderDynamicDiscInternal(entity, interp, layers, segments);
	}

	/**
	 * 内部用の動的膠着円盤レンダリング（LOD対応）
	 */
	protected void renderDynamicDiscInternal(EntityBlackHole entity, float interp, int layers, int count) {
		// 膠着円盤の外半径を取得(ブロック単位)
		double outerRadius = entity.getAccretionDiskOuterRadius();

		// 膠着円盤の明るさを取得(膠着率に応じて変化)
		float brightness = entity.getAccretionDiskBrightness();
		float glow = 0.75F * brightness;

		bindTexture(discTex());

		GL11.glPushMatrix();
		GL11.glRotatef(entity.getEntityId() % 90 - 45, 1, 0, 0);
		GL11.glRotatef(entity.getEntityId() % 360, 0, 1, 0);
		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		GlStateManager.enableBlend();
		GlStateManager.disableAlpha();
		GlStateManager.depthMask(false);
		GlStateManager.alphaFunc(GL11.GL_GEQUAL, 0.0F);
		GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);

		Tessellator tes = Tessellator.getInstance();
		BufferBuilder buf = tes.getBuffer();

		Vec3 vec = Vec3.createVectorHelper(1, 0, 0);

		float[] color = {0, 0, 0, 0};

		// 膠着円盤を複数の層で描画(回転速度が異なる)
		for(int k = 0; k < layers; k++) {

			GL11.glPushMatrix();
			// 各層の回転速度を計算(内側ほど速く回転)
			// ケプラーの第3法則: v ∝ r^(-0.5)
			float rotationSpeed = (float)Math.pow(k + 1, 1.25);
			GL11.glRotatef((entity.ticksExisted + interp % 360) * -rotationSpeed, 0, 1, 0);
			GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);

			// 各層のサイズを計算(外側に行くほど大きくなる)
			// innerRadius = ISCO = 3rs, outerRadius = 動的に変化
			double innerRadiusNormalized = 3.0; // 最内層のサイズ(ブロック単位)
			double layerRadius = innerRadiusNormalized + (outerRadius - innerRadiusNormalized) * k / (double)layers;
			double s = layerRadius / 3.0; // 基準サイズで正規化

			for(int j = 0; j < 2; j++) {

				buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
				for(int i = 0; i < count; i++) {

					if(j == 0){
						this.setColorFromIteration(k, 1F, color, brightness);
					} else {
						color[0] = 1;
						color[1] = 1;
						color[2] = 1;
						color[3] = glow;
					}
					buf.pos(vec.xCoord * s, 0, vec.zCoord * s).tex(0.5 + vec.xCoord * 0.25, 0.5 + vec.zCoord * 0.25).color(color[0], color[1], color[2], color[3]).endVertex();
					this.setColorFromIteration(k, 0F, color, brightness);
					buf.pos(vec.xCoord * s * 2, 0, vec.zCoord * s * 2).tex(0.5 + vec.xCoord * 0.5, 0.5 + vec.zCoord * 0.5).color(color[0], color[1], color[2], color[3]).endVertex();

					vec.rotateAroundY((float)(Math.PI * 2 / count));
					this.setColorFromIteration(k, 0F, color, brightness);
					buf.pos(vec.xCoord * s * 2, 0, vec.zCoord * s * 2).tex(0.5 + vec.xCoord * 0.5, 0.5 + vec.zCoord * 0.5).color(color[0], color[1], color[2], color[3]).endVertex();

					if(j == 0){
						this.setColorFromIteration(k, 1F, color, brightness);
					} else {
						color[0] = 1;
						color[1] = 1;
						color[2] = 1;
						color[3] = glow;
					}
					buf.pos(vec.xCoord * s, 0, vec.zCoord * s).tex(0.5 + vec.xCoord * 0.25, 0.5 + vec.zCoord * 0.25).color(color[0], color[1], color[2], color[3]).endVertex();
				}
				tes.draw();

				GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
			}

			GL11.glPopMatrix();
		}

		GlStateManager.shadeModel(GL11.GL_FLAT);
		GlStateManager.disableBlend();
		GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
		GlStateManager.depthMask(true);
		GlStateManager.enableAlpha();
		GL11.glPopMatrix();
	}

	protected ResourceLocation discTex(){
		return this.disc;
	}

	/**
	 * 動的サイズの膠着円盤をレンダリング
	 * ブロック破壊に応じてサイズが増加する
	 *
	 * @deprecated この機能はrenderDynamicDiscWithLODに統合されました
	 */
	@Deprecated
	protected void renderDynamicDisc(EntityBlackHole entity, float interp){
		renderDynamicDiscWithLOD(entity, interp, 1000.0); // デフォルト距離で呼び出し
	}

	/**
	 * 従来の固定サイズの膠着円盤をレンダリング(サブクラス用)
	 */
	protected void renderDisc(EntityBlackHole entity, float interp){

		float glow = 0.75F;

		bindTexture(discTex());

		GL11.glPushMatrix();
		GL11.glRotatef(entity.getEntityId() % 90 - 45, 1, 0, 0);
		GL11.glRotatef(entity.getEntityId() % 360, 0, 1, 0);
		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		GlStateManager.enableBlend();
		GlStateManager.disableAlpha();
		GlStateManager.depthMask(false);
		GlStateManager.alphaFunc(GL11.GL_GEQUAL, 0.0F);
		GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);

		Tessellator tes = Tessellator.getInstance();
		BufferBuilder buf = tes.getBuffer();

		int count = 16;

		Vec3 vec = Vec3.createVectorHelper(1, 0, 0);

		float[] color = {0, 0, 0, 0};
		for(int k = 0; k < steps(); k++) {

			GL11.glPushMatrix();
			GL11.glRotatef((entity.ticksExisted + interp % 360) * -((float)Math.pow(k + 1, 1.25)), 0, 1, 0);
			GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
			double s = 3 - k * 0.175D;

			for(int j = 0; j < 2; j++) {

				buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
				for(int i = 0; i < count; i++) {

					if(j == 0){
						this.setColorFromIteration(k, 1F, color, 1.0F);
					} else {
						color[0] = 1;
						color[1] = 1;
						color[2] = 1;
						color[3] = glow;
					}
					buf.pos(vec.xCoord * s, 0, vec.zCoord * s).tex(0.5 + vec.xCoord * 0.25, 0.5 + vec.zCoord * 0.25).color(color[0], color[1], color[2], color[3]).endVertex();
					this.setColorFromIteration(k, 0F, color, 1.0F);
					buf.pos(vec.xCoord * s * 2, 0, vec.zCoord * s * 2).tex(0.5 + vec.xCoord * 0.5, 0.5 + vec.zCoord * 0.5).color(color[0], color[1], color[2], color[3]).endVertex();

					vec.rotateAroundY((float)(Math.PI * 2 / count));
					this.setColorFromIteration(k, 0F, color, 1.0F);
					buf.pos(vec.xCoord * s * 2, 0, vec.zCoord * s * 2).tex(0.5 + vec.xCoord * 0.5, 0.5 + vec.zCoord * 0.5).color(color[0], color[1], color[2], color[3]).endVertex();

					if(j == 0){
						this.setColorFromIteration(k, 1F, color, 1.0F);
					} else {
						color[0] = 1;
						color[1] = 1;
						color[2] = 1;
						color[3] = glow;
					}
					buf.pos(vec.xCoord * s, 0, vec.zCoord * s).tex(0.5 + vec.xCoord * 0.25, 0.5 + vec.zCoord * 0.25).color(color[0], color[1], color[2], color[3]).endVertex();
				}
				tes.draw();

				GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
			}

			GL11.glPopMatrix();
		}

		GlStateManager.shadeModel(GL11.GL_FLAT);
		GlStateManager.disableBlend();
		GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
		GlStateManager.depthMask(true);
		GlStateManager.enableAlpha();
		GL11.glPopMatrix();
	}

	protected int steps(){
		return 15;
	}

	/**
	 * 膠着円盤の色を計算(明るさパラメータ付き)
	 */
	protected void setColorFromIteration(int iteration, float alpha, float[] col, float brightness){

		if(iteration < 5) {
			float g = 0.125F + iteration * (1F / 10F);
			col[0] = 1 * brightness;
			col[1] = g * brightness;
			col[2] = 0;
			col[3] = alpha;
			return;
		}

		if(iteration == 5) {
			col[0] = 1.0F * brightness;
			col[1] = 1.0F * brightness;
			col[2] = 1.0F * brightness;
			col[3] = alpha;
			return;
		}

		if(iteration > 5) {
			int i = iteration - 6;
			float r = 1.0F - i * (1F / 9F);
			float g = 1F - i * (1F / 9F);
			float b = i * (1F / 5F);
			col[0] = r * brightness;
			col[1] = g * brightness;
			col[2] = b * brightness;
			col[3] = alpha;
		}
	}

	protected void renderSwirl(EntityBlackHole entity, float interp){

		float glow = 0.75F;

		if(entity instanceof EntityRagingVortex)
			glow = 0.25F;

		bindTexture(swirl);

		GL11.glPushMatrix();
		GL11.glRotatef(entity.getEntityId() % 90 - 45, 1, 0, 0);
		GL11.glRotatef(entity.getEntityId() % 360, 0, 1, 0);
		GL11.glRotatef((entity.ticksExisted + interp % 360) * -5, 0, 1, 0);
		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		GlStateManager.enableBlend();
		GlStateManager.disableAlpha();
		GlStateManager.depthMask(false);
		GlStateManager.alphaFunc(GL11.GL_GEQUAL, 0.0F);
		GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
		Vec3 vec = Vec3.createVectorHelper(1, 0, 0);

		Tessellator tes = Tessellator.getInstance();
		BufferBuilder buf = tes.getBuffer();

		double s = 3;
		int count = 16;

		float[] color = {0, 0, 0, 0};

		//swirl, inner part (solid)
		for(int j = 0; j < 2; j++) {
			buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
			for(int i = 0; i < count; i++) {
				color[0] = 0;
				color[1] = 0;
				color[2] = 0;
				color[3] = 1;
				buf.pos(vec.xCoord * 0.9, 0, vec.zCoord * 0.9).tex(0.5 + vec.xCoord * 0.25 / s * 0.9, 0.5 + vec.zCoord * 0.25 / s * 0.9).color(color[0], color[1], color[2], color[3]).endVertex();

				if(j == 0){
					this.setColorFull(entity, color);
				} else {
					color[0] = 1;
					color[1] = 1;
					color[2] = 1;
					color[3] = glow;
				}

				buf.pos(vec.xCoord * s, 0, vec.zCoord * s).tex(0.5 + vec.xCoord * 0.25, 0.5 + vec.zCoord * 0.25).color(color[0], color[1], color[2], color[3]).endVertex();

				vec.rotateAroundY((float)(Math.PI * 2 / count));

				if(j == 0){
					this.setColorFull(entity, color);
				} else {
					color[0] = 1;
					color[1] = 1;
					color[2] = 1;
					color[3] = glow;
				}

				buf.pos(vec.xCoord * s, 0, vec.zCoord * s).tex(0.5 + vec.xCoord * 0.25, 0.5 + vec.zCoord * 0.25).color(color[0], color[1], color[2], color[3]).endVertex();
				color[0] = 0;
				color[1] = 0;
				color[2] = 0;
				color[3] = 1;
				buf.pos(vec.xCoord * 0.9, 0, vec.zCoord * 0.9).tex(0.5 + vec.xCoord * 0.25 / s * 0.9, 0.5 + vec.zCoord * 0.25 / s * 0.9).color(color[0], color[1], color[2], color[3]).endVertex();
			}

			tes.draw();

			GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
		}

		GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);

		//swirl, outer part (fade)
		for(int j = 0; j < 2; j++) {

			buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
			for(int i = 0; i < count; i++) {

				if(j == 0){
					this.setColorFull(entity, color);
				}else {
					color[0] = 1;
					color[1] = 1;
					color[2] = 1;
					color[3] = glow;
				}
				buf.pos(vec.xCoord * s, 0, vec.zCoord * s).tex(0.5 + vec.xCoord * 0.25, 0.5 + vec.zCoord * 0.25).color(color[0], color[1], color[2], color[3]).endVertex();
				this.setColorNone(entity, color);
				buf.pos(vec.xCoord * s * 2, 0, vec.zCoord * s * 2).tex(0.5 + vec.xCoord * 0.5, 0.5 + vec.zCoord * 0.5).color(color[0], color[1], color[2], color[3]).endVertex();

				vec.rotateAroundY((float)(Math.PI * 2 / count));
				this.setColorNone(entity, color);
				buf.pos(vec.xCoord * s * 2, 0, vec.zCoord * s * 2).tex(0.5 + vec.xCoord * 0.5, 0.5 + vec.zCoord * 0.5).color(color[0], color[1], color[2], color[3]).endVertex();

				if(j == 0)
					this.setColorFull(entity, color);
				else {
					color[0] = 1;
					color[1] = 1;
					color[2] = 1;
					color[3] = glow;
				}
				buf.pos(vec.xCoord * s, 0, vec.zCoord * s).tex(0.5 + vec.xCoord * 0.25, 0.5 + vec.zCoord * 0.25).color(color[0], color[1], color[2], color[3]).endVertex();
			}
			tes.draw();

			GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
		}

		GlStateManager.shadeModel(GL11.GL_FLAT);
		GlStateManager.disableBlend();
		GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
		GlStateManager.depthMask(true);
		GlStateManager.enableAlpha();

		GL11.glPopMatrix();
	}

	protected void renderJets(EntityBlackHole entity, float interp){

		Tessellator tes = Tessellator.getInstance();
		BufferBuilder buf = tes.getBuffer();

		GL11.glPushMatrix();
		GL11.glRotatef(entity.getEntityId() % 90 - 45, 1, 0, 0);
		GL11.glRotatef(entity.getEntityId() % 360, 0, 1, 0);

		GlStateManager.disableAlpha();
		GlStateManager.depthMask(false);
		GlStateManager.alphaFunc(GL11.GL_GEQUAL, 0.0F);
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		GlStateManager.disableTexture2D();

		for(int j = -1; j <= 1; j += 2) {
			buf.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);

			buf.pos(0, 0, 0).color(1, 1, 1, 0.35F).endVertex();

			Vec3 jet = Vec3.createVectorHelper(0.5, 0, 0);

			for(int i = 0; i <= 12; i++) {
				buf.pos(jet.xCoord, 10 * j, jet.zCoord).color(1.0F, 1.0F, 1.0F, 0.0F).endVertex();
				jet.rotateAroundY((float)(Math.PI / 6 * -j));
			}

			tes.draw();
		}
		GlStateManager.enableTexture2D();
		GlStateManager.shadeModel(GL11.GL_FLAT);
		GlStateManager.disableBlend();
		GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
		GlStateManager.depthMask(true);
		GlStateManager.enableAlpha();
		GL11.glPopMatrix();
	}

	protected void renderFlare(EntityBlackHole entity){

		GL11.glPushMatrix();
		GL11.glScalef(0.2F, 0.2F, 0.2F);

		Tessellator tes = Tessellator.getInstance();
		BufferBuilder buf = tes.getBuffer();
		RenderHelper.disableStandardItemLighting();
		int j = 75;
		float f1 = (j + 2.0F) / 200.0F;
		float f2 = 0.0F;
		int count = 250;

		count = j;

		if(f1 > 0.8F) {
			f2 = (f1 - 0.8F) / 0.2F;
		}

		Random random = new Random(432L);
		GlStateManager.disableTexture2D();
		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
		GlStateManager.disableAlpha();
		GlStateManager.enableCull();
		GlStateManager.depthMask(false);

		float[] color = {0, 0, 0, 0};
		for(int i = 0; i < count; i++) {
			GL11.glRotatef(random.nextFloat() * 360.0F, 1.0F, 0.0F, 0.0F);
			GL11.glRotatef(random.nextFloat() * 360.0F, 0.0F, 1.0F, 0.0F);
			GL11.glRotatef(random.nextFloat() * 360.0F, 0.0F, 0.0F, 1.0F);
			GL11.glRotatef(random.nextFloat() * 360.0F, 1.0F, 0.0F, 0.0F);
			GL11.glRotatef(random.nextFloat() * 360.0F, 0.0F, 1.0F, 0.0F);
			GL11.glRotatef(random.nextFloat() * 360.0F + f1 * 90.0F, 0.0F, 0.0F, 1.0F);
			buf.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
			float f3 = random.nextFloat() * 20.0F + 5.0F + f2 * 10.0F;
			float f4 = random.nextFloat() * 2.0F + 1.0F + f2 * 2.0F;
			setColorFull(entity, color);
			buf.pos(0.0D, 0.0D, 0.0D).color(color[0], color[1], color[2], color[3]).endVertex();
			setColorNone(entity, color);
			buf.pos(-0.866D * f4, f3, -0.5F * f4).color(color[0], color[1], color[2], color[3]).endVertex();
			buf.pos(0.866D * f4, f3, -0.5F * f4).color(color[0], color[1], color[2], color[3]).endVertex();
			buf.pos(0.0D, f3, 1.0F * f4).color(color[0], color[1], color[2], color[3]).endVertex();
			buf.pos(-0.866D * f4, f3, -0.5F * f4).color(color[0], color[1], color[2], color[3]).endVertex();
			tes.draw();
		}

		GlStateManager.depthMask(true);
		GlStateManager.disableCull();
		GlStateManager.disableBlend();
		GlStateManager.shadeModel(GL11.GL_FLAT);
		GlStateManager.color(1, 1, 1, 1);
		GlStateManager.enableTexture2D();
		GlStateManager.enableAlpha();
		RenderHelper.enableStandardItemLighting();
		GL11.glPopMatrix();
	}

	protected void setColorFull(EntityBlackHole e, float[] color){
		if(e instanceof EntityVortex) {
			com.hbm.render.RenderHelper.unpackColor(0x3898b3, color);
		} else if(e instanceof EntityRagingVortex) {
			com.hbm.render.RenderHelper.unpackColor(0xe8390d, color);
		} else {
			com.hbm.render.RenderHelper.unpackColor(0xFFB900, color);
		}
		color[3] = 1;
	}

	protected void setColorNone(EntityBlackHole e, float[] color){
		if(e instanceof EntityVortex) {
			com.hbm.render.RenderHelper.unpackColor(0x3898b3, color);
		} else if(e instanceof EntityRagingVortex) {
			com.hbm.render.RenderHelper.unpackColor(0xe8390d, color);
		} else {
			com.hbm.render.RenderHelper.unpackColor(0xFFB900, color);
		}
		color[3] = 0;
	}

	// ============================================================
	// レンダリング距離設定（膠着円盤を無限距離から可視に）
	// ============================================================

	/**
	 * 膠着円盤を無限距離から可視にする
	 * EntityBlackHoleクラスのisInRangeToRenderDistをオーバーライドせず、
	 * レンダラー側で距離判定を行う
	 */
	@Override
	public boolean shouldRender(EntityBlackHole entity, net.minecraft.client.renderer.culling.ICamera camera, double camX, double camY, double camZ) {
		// 膠着円盤は常にレンダリング（無限距離から可視）
		return true;
	}

	@Override
	protected ResourceLocation getEntityTexture(EntityBlackHole entity){
		return hole;
	}

}