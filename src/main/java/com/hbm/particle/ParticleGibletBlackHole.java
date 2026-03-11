package com.hbm.particle;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.HbmParticleUtility;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleBlockDust;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/**
 * ブラックホールの重力に従う肉片パーティクル
 *
 * EntityBlackHole.javaの物理定数と同じ値を使用して、
 * パーティクルをブラックホールに向けて重力加速させ、
 * シュヴァルツシルト半径に到達したら消滅させる。
 */
public class ParticleGibletBlackHole extends ParticleGiblet {

	// ============================================================
	// 物理定数（EntityBlackHole.javaと同じ値）
	// ============================================================
	protected static final double GRAVITATIONAL_CONSTANT = 6.674e-11; // G (m³·kg⁻¹·s⁻²)
	protected static final double TICK_TIME = 0.05; // 1 tick = 1/20秒

	// ブラックホールの情報
	private final double blackHoleX;
	private final double blackHoleY;
	private final double blackHoleZ;
	private final double blackHoleMass;
	private final double schwarzschildRadius; // メートル単位

	// GM積（計算の最適化のため事前計算）
	private final double GM_PRODUCT;

	/**
	 * ブラックホールの重力に従う肉片パーティクルを生成
	 *
	 * @param worldIn ワールド
	 * @param posXIn 初期X座標
	 * @param posYIn 初期Y座標
	 * @param posZIn 初期Z座標
	 * @param mX 初期X速度
	 * @param mY 初期Y速度
	 * @param mZ 初期Z速度
	 * @param bhX ブラックホールのX座標
	 * @param bhY ブラックホールのY座標
	 * @param bhZ ブラックホールのZ座標
	 * @param bhMass ブラックホールの質量（kg）
	 * @param schwarzschildRadius シュヴァルツシルト半径（メートル）
	 */
	public ParticleGibletBlackHole(World worldIn, double posXIn, double posYIn, double posZIn,
								   double mX, double mY, double mZ,
								   double bhX, double bhY, double bhZ,
								   double bhMass, double schwarzschildRadius) {
		super(worldIn, posXIn, posYIn, posZIn, mX, mY, mZ);

		// ブラックホールの情報を保存
		this.blackHoleX = bhX;
		this.blackHoleY = bhY;
		this.blackHoleZ = bhZ;
		this.blackHoleMass = bhMass;
		this.schwarzschildRadius = schwarzschildRadius;

		// GM積を事前計算
		this.GM_PRODUCT = GRAVITATIONAL_CONSTANT * bhMass;

		// 通常の重力を無効化（ブラックホールの重力のみを使用）
		this.particleGravity = 0F;

		// パーティクルの寿命を延長（ブラックホールに到達するまで）
		this.particleMaxAge = 1200; // 60秒
	}

	@Override
	public void onUpdate() {
		// 位置を更新
		this.prevPosX = this.posX;
		this.prevPosY = this.posY;
		this.prevPosZ = this.posZ;

		// 年齢を増やす
		if(this.particleAge++ >= this.particleMaxAge) {
			this.setExpired();
		}

		// ============================================================
		// ブラックホールへの方向ベクトルを計算
		// ============================================================
		double dx = blackHoleX - this.posX;
		double dy = blackHoleY - this.posY;
		double dz = blackHoleZ - this.posZ;

		// ブラックホールまでの距離を計算
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		// ============================================================
		// シュヴァルツシルト半径チェック（事象の地平線）
		// ============================================================
		// パーティクルがシュヴァルツシルト半径内に入ったら消滅
		if(distance < schwarzschildRadius) {
			this.setExpired();
			return;
		}

		// ============================================================
		// ブラックホールの重力加速度を計算: a = GM/r²
		// ============================================================
		if(distance > 0.01) { // 0除算防止
			// 重力加速度
			double acceleration = GM_PRODUCT / (distance * distance);

			// 方向ベクトルを正規化
			double nx = dx / distance;
			double ny = dy / distance;
			double nz = dz / distance;

			// 速度変化（Δv = a × Δt）
			double deltaV = acceleration * TICK_TIME;

			// 吸引力を強化（ゲームとして見やすくするため）
			// 距離が近いほど強化倍率を上げる
			double enhancementMultiplier = 1.0;
			if(distance < 10.0) {
				enhancementMultiplier = 100.0; // 10ブロック以内では100倍
			} else if(distance < 50.0) {
				enhancementMultiplier = 50.0; // 50ブロック以内では50倍
			} else if(distance < 100.0) {
				enhancementMultiplier = 10.0; // 100ブロック以内では10倍
			} else {
				enhancementMultiplier = 5.0; // それ以外は5倍
			}

			deltaV *= enhancementMultiplier;

			// 速度に重力加速度を加算
			this.motionX += nx * deltaV;
			this.motionY += ny * deltaV;
			this.motionZ += nz * deltaV;

			// 速度制限（数値爆発防止）
			double totalSpeed = Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);
			if(totalSpeed > 50.0) {
				double scale = 50.0 / totalSpeed;
				this.motionX *= scale;
				this.motionY *= scale;
				this.motionZ *= scale;
			}
		}

		// ============================================================
		// 位置を更新
		// ============================================================
		this.posX += this.motionX;
		this.posY += this.motionY;
		this.posZ += this.motionZ;

		// ============================================================
		// 血の軌跡パーティクルを生成（距離が近い場合のみ）
		// ============================================================
		if(distance < 100.0 && !this.onGround) {
			Particle fx = new ParticleBlockDust.Factory().createParticle(-1, world, posX, posY, posZ, 0, 0, 0, Block.getStateId(Blocks.REDSTONE_BLOCK.getDefaultState()));
			HbmParticleUtility.setMaxAge(fx, 20 + rand.nextInt(20));
			Minecraft.getMinecraft().effectRenderer.addEffect(fx);
		}
	}
}
