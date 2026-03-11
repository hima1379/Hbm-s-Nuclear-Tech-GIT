package com.hbm.render.entity.effect;

import org.lwjgl.opengl.GL11;

import com.hbm.entity.effect.EntityBlackHole;
import com.hbm.entity.effect.EntityQuasar;
import com.hbm.lib.RefStrings;
import com.hbm.main.ClientProxy;
import com.hbm.render.amlfrom1710.Vec3;
import com.hbm.render.entity.RenderBlackHole;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.registry.IRenderFactory;

/**
 * クエーサーのレンダラー
 *
 * クエーサーは超大質量ブラックホールであり、オーバーワールド全体を飲み込んだ前提で
 * 巨大な膠着円盤をレンダリングします。
 *
 * 物理的背景:
 * - クエーサーは宇宙で最も明るい天体の一つ
 * - 超大質量ブラックホール（太陽質量の数百万〜数十億倍）の周りに形成される
 * - 膠着円盤から放射されるエネルギーは銀河全体を凌駕する
 *
 * 推定値:
 * - オーバーワールド探索範囲: 10,000 x 256 x 10,000 = 25.6億ブロック
 * - 総質量: 25.6億 × 2400kg = 6.144 × 10^12 kg
 * - 膠着円盤の初期サイズ: 数千〜数万ブロック
 */
public class RenderQuasar extends RenderBlackHole {

	public static final IRenderFactory<EntityQuasar> FACTORY = man -> new RenderQuasar(man);

	protected ResourceLocation quasar = new ResourceLocation(RefStrings.MODID, "textures/entity/bholeD.png");

	// クエーサーの膠着円盤の巨大サイズ定数
	private static final double QUASAR_DISK_INNER_RADIUS = 50.0; // ブロック
	private static final double QUASAR_DISK_OUTER_RADIUS = 5000.0; // ブロック（5km）
	private static final int QUASAR_DISK_LAYERS = 25; // より多くの層で詳細に表現

	// クエーサーの膠着円盤の明るさ（通常のブラックホールより遥かに明るい）
	private static final float QUASAR_BRIGHTNESS = 2.0F; // 200%の明るさ
	private static final float QUASAR_GLOW = 1.5F; // 非常に強い光

	public RenderQuasar(RenderManager renderManager){
		super(renderManager);
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
		GL11.glTranslatef((float) x, (float) y, (float) z);
		GlStateManager.disableLighting();
		GlStateManager.disableCull();

		// クエーサーの場合、EntityBlackHoleの物理モデルではなく
		// 固定のSIZEパラメータを使用（サブクラス互換性）
		float size = entity.getDataManager().get(EntityBlackHole.SIZE);

		// 黒球本体は近距離のみレンダリング
		if(distance < 2000.0) {
			GL11.glPushMatrix();
			GL11.glScalef(size, size, size);
			bindTexture(hole);
			blastModel.renderAll();
			GL11.glPopMatrix();
		}

		// クエーサー専用の巨大膠着円盤をレンダリング（無限距離から可視）
		renderMassiveQuasarDiscWithLOD(entity, partialTicks, distance);

		// ジェットは中距離まで表示
		if(distance < 10000.0) {
			renderQuasarJets(entity, partialTicks);
		}

		GlStateManager.enableCull();
		GlStateManager.enableLighting();

		GL11.glPopMatrix();
	}

	/**
	 * LOD付きクエーサー巨大膠着円盤レンダリング
	 * 距離に応じて詳細度を変更
	 */
	protected void renderMassiveQuasarDiscWithLOD(EntityBlackHole entity, float interp, double distance) {
		// 距離に応じてLODを決定
		int layers;
		int segments;

		if(distance < 1000.0) {
			// 近距離: 最高品質
			layers = QUASAR_DISK_LAYERS;
			segments = 32;
		} else if(distance < 5000.0) {
			// 中距離: 高品質
			layers = QUASAR_DISK_LAYERS / 2;
			segments = 24;
		} else if(distance < 20000.0) {
			// 遠距離: 中品質
			layers = QUASAR_DISK_LAYERS / 4;
			segments = 16;
		} else if(distance < 50000.0) {
			// 超遠距離: 低品質
			layers = 5;
			segments = 8;
		} else {
			// 極超遠距離: 最低品質（シンプルな光点として）
			layers = 2;
			segments = 4;
		}

		renderMassiveQuasarDiscInternal(entity, interp, layers, segments);
	}

	/**
	 * クエーサーの巨大膠着円盤をレンダリング（内部実装・LOD対応）
	 */
	protected void renderMassiveQuasarDiscInternal(EntityBlackHole entity, float interp, int layers, int count) {
		bindTexture(quasar);

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

		// 多層構造の巨大膠着円盤を描画
		for(int k = 0; k < layers; k++) {

			GL11.glPushMatrix();

			// 各層の回転速度（ケプラーの第3法則: v ∝ r^(-0.5)）
			// 内側ほど速く回転
			float rotationSpeed = (float)(Math.pow(k + 1, 1.5) * 0.5);
			GL11.glRotatef((entity.ticksExisted + interp % 360) * -rotationSpeed, 0, 1, 0);

			// 各層のサイズを計算
			// 内半径から外半径まで段階的に増加
			double layerProgress = k / (double)layers;
			double innerRadius = QUASAR_DISK_INNER_RADIUS +
					(QUASAR_DISK_OUTER_RADIUS - QUASAR_DISK_INNER_RADIUS) * layerProgress * 0.5;
			double outerRadius = QUASAR_DISK_INNER_RADIUS +
					(QUASAR_DISK_OUTER_RADIUS - QUASAR_DISK_INNER_RADIUS) * (layerProgress + 0.5 / layers);

			// 各層を2回描画（通常ブレンドと加算ブレンド）
			for(int j = 0; j < 2; j++) {
				GlStateManager.blendFunc(
						j == 0 ? SourceFactor.SRC_ALPHA : SourceFactor.SRC_ALPHA,
						j == 0 ? DestFactor.ONE_MINUS_SRC_ALPHA : DestFactor.ONE
				);

				buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);

				for(int i = 0; i < count; i++) {
					// 内側の色（明るい）
					if(j == 0) {
						this.setQuasarColorFromIteration(k, 1.0F, color, layers);
					} else {
						color[0] = 1;
						color[1] = 1;
						color[2] = 1;
						color[3] = QUASAR_GLOW;
					}
					buf.pos(vec.xCoord * innerRadius, 0, vec.zCoord * innerRadius)
							.tex(0.5 + vec.xCoord * 0.25, 0.5 + vec.zCoord * 0.25)
							.color(color[0], color[1], color[2], color[3])
							.endVertex();

					// 外側の色（フェードアウト）
					this.setQuasarColorFromIteration(k, 0.0F, color, layers);
					buf.pos(vec.xCoord * outerRadius, 0, vec.zCoord * outerRadius)
							.tex(0.5 + vec.xCoord * 0.5, 0.5 + vec.zCoord * 0.5)
							.color(color[0], color[1], color[2], color[3])
							.endVertex();

					// 次のセグメント
					vec.rotateAroundY((float)(Math.PI * 2 / count));

					this.setQuasarColorFromIteration(k, 0.0F, color, layers);
					buf.pos(vec.xCoord * outerRadius, 0, vec.zCoord * outerRadius)
							.tex(0.5 + vec.xCoord * 0.5, 0.5 + vec.zCoord * 0.5)
							.color(color[0], color[1], color[2], color[3])
							.endVertex();

					if(j == 0) {
						this.setQuasarColorFromIteration(k, 1.0F, color, layers);
					} else {
						color[0] = 1;
						color[1] = 1;
						color[2] = 1;
						color[3] = QUASAR_GLOW;
					}
					buf.pos(vec.xCoord * innerRadius, 0, vec.zCoord * innerRadius)
							.tex(0.5 + vec.xCoord * 0.25, 0.5 + vec.zCoord * 0.25)
							.color(color[0], color[1], color[2], color[3])
							.endVertex();
				}

				tes.draw();
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

	/**
	 * クエーサーのジェットをレンダリング
	 * 通常のブラックホールよりも遥かに長く強力
	 */
	protected void renderQuasarJets(EntityBlackHole entity, float interp){
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

		// クエーサーのジェットは非常に長い（通常の10倍）
		float jetLength = 100.0F;
		float jetRadius = 2.0F;

		for(int j = -1; j <= 1; j += 2) {
			// メインジェット
			buf.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);

			// ジェットの中心（非常に明るい）
			buf.pos(0, 0, 0).color(1, 0.8F, 0.6F, 0.8F).endVertex();

			Vec3 jet = Vec3.createVectorHelper(jetRadius, 0, 0);

			for(int i = 0; i <= 16; i++) {
				// クエーサーの色（赤みがかった白）
				buf.pos(jet.xCoord, jetLength * j, jet.zCoord)
						.color(1.0F, 0.7F, 0.5F, 0.0F)
						.endVertex();
				jet.rotateAroundY((float)(Math.PI / 8 * -j));
			}

			tes.draw();

			// 外側の拡散光
			buf.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);

			buf.pos(0, 0, 0).color(1, 0.6F, 0.4F, 0.3F).endVertex();

			jet = Vec3.createVectorHelper(jetRadius * 2, 0, 0);

			for(int i = 0; i <= 16; i++) {
				buf.pos(jet.xCoord, jetLength * j * 1.2, jet.zCoord)
						.color(1.0F, 0.5F, 0.3F, 0.0F)
						.endVertex();
				jet.rotateAroundY((float)(Math.PI / 8 * -j));
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

	@Override
	protected ResourceLocation discTex() {
		return this.quasar;
	}

	/**
	 * 旧メソッドとの互換性用（deprecated）
	 */
	@Deprecated
	protected void renderMassiveQuasarDisc(EntityBlackHole entity, float interp) {
		renderMassiveQuasarDiscWithLOD(entity, interp, 1000.0); // デフォルト距離
	}

	/**
	 * クエーサーの膠着円盤の色を計算
	 * 赤から白へのグラデーション（高温を表現）
	 *
	 * @param iteration 層のインデックス
	 * @param alpha アルファ値
	 * @param col 色配列（出力）
	 * @param totalLayers 総層数（LOD対応）
	 */
	protected void setQuasarColorFromIteration(int iteration, float alpha, float[] col, int totalLayers) {
		// クエーサーは非常に高温なので、赤→オレンジ→黄→白のグラデーション
		float progress = iteration / (float)totalLayers;

		if(progress < 0.3F) {
			// 内側: 純白（最も高温）
			col[0] = 1.0F * QUASAR_BRIGHTNESS;
			col[1] = 1.0F * QUASAR_BRIGHTNESS;
			col[2] = 1.0F * QUASAR_BRIGHTNESS;
		} else if(progress < 0.6F) {
			// 中間: 黄色→オレンジ
			float t = (progress - 0.3F) / 0.3F;
			col[0] = 1.0F * QUASAR_BRIGHTNESS;
			col[1] = (1.0F - t * 0.3F) * QUASAR_BRIGHTNESS;
			col[2] = (1.0F - t * 0.8F) * QUASAR_BRIGHTNESS;
		} else {
			// 外側: オレンジ→赤
			float t = (progress - 0.6F) / 0.4F;
			col[0] = 1.0F * QUASAR_BRIGHTNESS;
			col[1] = (0.7F - t * 0.5F) * QUASAR_BRIGHTNESS;
			col[2] = (0.2F - t * 0.2F) * QUASAR_BRIGHTNESS;
		}

		col[3] = alpha;

		// 色を正規化（1.0を超えないように）
		if(col[0] > 1.0F) col[0] = 1.0F;
		if(col[1] > 1.0F) col[1] = 1.0F;
		if(col[2] > 1.0F) col[2] = 1.0F;
	}

	@Override
	protected int steps() {
		return QUASAR_DISK_LAYERS;
	}

	// ============================================================
	// レンダリング距離設定（膠着円盤を無限距離から可視に）
	// ============================================================

	/**
	 * クエーサーの膠着円盤を無限距離から可視にする
	 */
	@Override
	public boolean shouldRender(EntityBlackHole entity, net.minecraft.client.renderer.culling.ICamera camera, double camX, double camY, double camZ) {
		// クエーサーの膠着円盤は常にレンダリング（無限距離から可視）
		return true;
	}

	@Override
	protected ResourceLocation getEntityTexture(EntityBlackHole entity){
		return super.getEntityTexture(entity);
	}
}