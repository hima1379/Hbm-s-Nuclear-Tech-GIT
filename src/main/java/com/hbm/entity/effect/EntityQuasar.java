package com.hbm.entity.effect;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.hbm.config.CompatibilityConfig;
import com.hbm.entity.mob.EntityQuackos;
import com.hbm.entity.projectile.EntityRubble;
import com.hbm.render.amlfrom1710.Vec3;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;

/**
 * クエーサー - 超大質量ブラックホール（膠着円盤システム統合版）
 *
 * 物理定数:
 * - シュヴァルツシルト半径: rs = 0.5m (EntityBlackHoleの50万倍)
 * - 質量: M = 3.366×10²⁶ kg (地球の約56,000倍)
 * - 地球重力と同等になる距離: ~47,880km
 *
 * 膠着円盤:
 * - オーバーワールド全体（推定25.6億ブロック = 6.144×10¹² kg）を飲み込んだ前提
 * - 膠着円盤質量: 総質量の80% = 4.915×10¹² kg
 * - ブラックホール本体: 総質量の20% = 1.229×10¹² kg
 * - 膠着円盤外半径: 5,000ブロック (5km)
 *
 * 機能:
 * 1. 新規ロードチャンクを即座に完全破壊（実質的なワールド破壊）
 * 2. 既存チャンクは徐々に球体状に破壊
 * 3. 広範囲の重力による吸引
 * 4. 動的な膠着円盤サイズ（ブロック破壊に応じて成長）
 */
public class EntityQuasar extends EntityBlackHole {

	// ============================================================
	// クエーサー専用物理定数
	// ============================================================
	private static final double QUASAR_SCHWARZSCHILD_RADIUS_METERS = 0.5; // 0.5メートル
	private static final double QUASAR_MASS; // kg
	private static final double QUASAR_GM_PRODUCT; // G × M

	// 重力範囲（物理的には47,880km だが、ゲームバランスのため制限）
	private static final double QUASAR_INITIAL_RANGE = 500.0; // 初期範囲（ブロック）
	private static final double QUASAR_MAX_RANGE = 10000.0; // 最大範囲（ブロック）
	private static final double QUASAR_RANGE_GROWTH_RATE = 1.05; // 範囲成長率（毎tick 5%増加）

	// チャンク破壊管理
	private static final int CHUNK_SCAN_RADIUS = 16; // チャンクスキャン半径（既存チャンク判定用）

	// ============================================================
	// オーバーワールド全体を飲み込んだ前提の初期値
	// ============================================================
	private static final long ESTIMATED_OVERWORLD_BLOCKS = 2_560_000_000L; // 25.6億ブロック
	private static final double OVERWORLD_TOTAL_MASS = ESTIMATED_OVERWORLD_BLOCKS * BLOCK_MASS_KG; // 6.144 × 10¹² kg

	// 膠着円盤とブラックホール本体の質量比
	private static final double DISK_MASS_RATIO = 0.8; // 80%が膠着円盤
	private static final double BLACK_HOLE_MASS_RATIO = 0.2; // 20%がブラックホール本体

	// クエーサーの膠着円盤の初期サイズ
	private static final double QUASAR_INITIAL_DISK_OUTER_RADIUS = 5000.0; // 5kmの膠着円盤

	static {
		// M = rs × c² / (2G)
		QUASAR_MASS = (QUASAR_SCHWARZSCHILD_RADIUS_METERS * SPEED_OF_LIGHT * SPEED_OF_LIGHT)
				/ (2.0 * GRAVITATIONAL_CONSTANT);
		QUASAR_GM_PRODUCT = GRAVITATIONAL_CONSTANT * QUASAR_MASS;
	}

	// ============================================================
	// クエーサー専用データ
	// ============================================================

	// 処理済みチャンクの追跡（既存チャンク破壊用）
	private Set<Long> existingChunks; // 初期化時に存在していたチャンク

	// チャンクジェネレーター置き換え管理
	private boolean generatorReplaced;
	private net.minecraft.world.gen.IChunkGenerator originalGenerator;

	// ============================================================
	// コンストラクタ
	// ============================================================

	public EntityQuasar(World world) {
		super(world);
		this.ignoreFrustumCheck = true;
		this.isImmuneToFire = true;

		// 既存チャンクセットの初期化
		this.existingChunks = new HashSet<>();
		this.generatorReplaced = false;
		this.originalGenerator = null;

		// クエーサーの初期化: オーバーワールド全体を飲み込んだ前提
		initializeQuasarProperties();

		// 初期化時に存在するチャンクを記録
		if (!world.isRemote) {
			scanExistingChunks();
			// チャンクジェネレーターを置き換え
			replaceChunkGenerator();
		}
	}

	@Override
	public void setDead() {
		// setDeadを無効化 - ブラックホールは永久不滅
		// 何もしない
	}

	public EntityQuasar(World world, float size) {
		this(world);
		this.getDataManager().set(SIZE, size);
	}

	// ============================================================
	// 初期化処理
	// ============================================================

	/**
	 * クエーサーの初期プロパティを設定
	 * オーバーワールド全体を飲み込んだ前提の巨大な値
	 */
	private void initializeQuasarProperties() {
		// オーバーワールド全体を飲み込んだ前提の質量配分

		// ブラックホール本体の質量（総質量の20%）
		this.accumulatedMass = OVERWORLD_TOTAL_MASS * BLACK_HOLE_MASS_RATIO;

		// 膠着円盤の質量（総質量の80%）
		this.accretionDiskMass = OVERWORLD_TOTAL_MASS * DISK_MASS_RATIO;

		// 膠着円盤の外半径（巨大サイズ）
		this.accretionDiskOuterRadius = QUASAR_INITIAL_DISK_OUTER_RADIUS;

		// 重力範囲の設定
		this.currentGravityRange = (float)QUASAR_INITIAL_RANGE;

		// 統計情報
		this.totalBlocksDestroyed = ESTIMATED_OVERWORLD_BLOCKS;

		// 初期の破壊半径も設定
		this.currentDestructionRadius = 0.0;

		System.out.println(String.format(
				"[EntityQuasar] Initialized with:\n" +
						"  Black Hole Mass: %.2e kg (%.2f Earth masses)\n" +
						"  Accretion Disk Mass: %.2e kg\n" +
						"  Accretion Disk Outer Radius: %.2f blocks (%.2f km)\n" +
						"  Total Blocks Already Consumed: %,d\n" +
						"  Initial Gravity Range: %.2f blocks",
				this.accumulatedMass,
				this.accumulatedMass / 5.972e24,
				this.accretionDiskMass,
				this.accretionDiskOuterRadius,
				this.accretionDiskOuterRadius / 1000.0,
				this.totalBlocksDestroyed,
				this.currentGravityRange
		));
	}

	/**
	 * 初期化時に存在するチャンクをスキャンして記録
	 */
	private void scanExistingChunks() {
		int chunkX = (int)posX >> 4;
		int chunkZ = (int)posZ >> 4;

		for (int x = chunkX - CHUNK_SCAN_RADIUS; x <= chunkX + CHUNK_SCAN_RADIUS; x++) {
			for (int z = chunkZ - CHUNK_SCAN_RADIUS; z <= chunkZ + CHUNK_SCAN_RADIUS; z++) {
				if (world.getChunkProvider().isChunkGeneratedAt(x, z)) {
					existingChunks.add(ChunkPos.asLong(x, z));
				}
			}
		}

		System.out.println("[EntityQuasar] Scanned " + existingChunks.size() + " existing chunks");
	}

	/**
	 * チャンクジェネレーターを空チャンク生成システムに置き換え
	 */
	private void replaceChunkGenerator() {
		// 現在のジェネレーターを保存
		originalGenerator = QuasarGeneratorReplacer.getCurrentGenerator(world);

		if (originalGenerator == null) {
			System.err.println("[EntityQuasar] Failed to get current chunk generator");
			return;
		}

		// チャンクジェネレーターを置き換え
		generatorReplaced = QuasarGeneratorReplacer.replaceChunkGenerator(world);

		if (generatorReplaced) {
			System.out.println("[EntityQuasar] Successfully replaced chunk generator for dimension " +
					world.provider.getDimension());
		} else {
			System.err.println("[EntityQuasar] Failed to replace chunk generator");
		}
	}

	/**
	 * チャンクジェネレーターを元に戻す
	 */
	private void restoreChunkGenerator() {
		if (generatorReplaced && originalGenerator != null) {
			boolean restored = QuasarGeneratorReplacer.restoreChunkGenerator(world, originalGenerator);
			if (restored) {
				System.out.println("[EntityQuasar] Restored original chunk generator");
				generatorReplaced = false;
			}
		}
	}

	// 新しいメソッド
	private void registerEventHandlers() {
		MinecraftForge.EVENT_BUS.register(QuasarServerChunkHandler.class);
		System.out.println("[EntityQuasar] Explicitly registered QuasarServerChunkHandler");
	}

	// ============================================================
	// メイン更新処理
	// ============================================================

	@Override
	public void onUpdate() {
		if (!CompatibilityConfig.isWarDim(world)) {
			return;
		}

		this.ticksExisted++;

		if (!world.isRemote) {
			// 範囲を徐々に拡大（クエーサーの成長率）
			if (currentGravityRange < QUASAR_MAX_RANGE) {
				currentGravityRange = (float)(currentGravityRange * QUASAR_RANGE_GROWTH_RATE);
			}

			// 膠着円盤から事象の地平線への質量移動
			processAccretionDiskToEventHorizon();

			// ホーキング放射による蒸発を計算（クエーサーは質量が大きいためほぼ蒸発しない）
			calculateQuasarHawkingRadiation();

			// 既存チャンクの徐々の破壊（親クラスのメソッドを使用）
			// これにより膠着円盤に質量が追加される
			super.destroyBlocks(currentGravityRange);
		}

		// エンティティへの重力影響（クエーサーの強力な重力）
		applyQuasarGravitationalForces();
	}

	// ============================================================
	// ホーキング放射（クエーサー版）
	// ============================================================

	/**
	 * クエーサーのホーキング放射計算
	 * 質量が大きいため蒸発は非常に遅い
	 */
	private void calculateQuasarHawkingRadiation() {
		// ホーキング温度: T = (ℏc³) / (8πGMk)
		double hawkingTemperature = (HAWKING_CONSTANT * Math.pow(SPEED_OF_LIGHT, 3))
				/ (8.0 * Math.PI * GRAVITATIONAL_CONSTANT * this.accumulatedMass * BOLTZMANN_CONSTANT);

		// 放射パワー: P = (ℏc⁶) / (15360πG²M²)
		double radiationPower = (HAWKING_CONSTANT * Math.pow(SPEED_OF_LIGHT, 6))
				/ (15360.0 * Math.PI * Math.pow(GRAVITATIONAL_CONSTANT, 2) * Math.pow(this.accumulatedMass, 2));

		double energyRadiatedPerTick = radiationPower * TICK_TIME;
		double massLossPerTick = energyRadiatedPerTick / (SPEED_OF_LIGHT * SPEED_OF_LIGHT);

		this.accumulatedMass -= massLossPerTick;
		this.hawkingRadiationAccumulated += energyRadiatedPerTick;

		// クエーサーは質量が大きいため、ほとんど蒸発しない
		// 蒸発までの時間は宇宙の年齢よりはるかに長い
	}

	// ============================================================
	// エンティティへの重力影響（クエーサー版）
	// ============================================================

	/**
	 * クエーサーの強力な重力による吸引
	 */
	private void applyQuasarGravitationalForces() {
		List<Entity> entities = world.getEntitiesWithinAABBExcludingEntity(this, new AxisAlignedBB(
				posX - currentGravityRange, posY - currentGravityRange, posZ - currentGravityRange,
				posX + currentGravityRange, posY + currentGravityRange, posZ + currentGravityRange));

		for (Entity e : entities) {
			if (e instanceof EntityBlackHole)
				continue;

			if (e instanceof EntityPlayer && ((EntityPlayer)e).capabilities.isCreativeMode)
				continue;

			if (e instanceof EntityFallingBlock && !world.isRemote) {
				e.setDead();
				continue;
			}

			Vec3 vec = Vec3.createVectorHelper(posX - e.posX, posY - e.posY, posZ - e.posZ);
			double distance = vec.length();

			if (distance > currentGravityRange || distance < 0.1)
				continue;

			vec = vec.normalize();

			// ブロック貫通を有効化
			if (distance < currentGravityRange * 0.5) {
				e.noClip = true;
			}

			// クエーサーの重力加速度: a = GM/r²
			double acceleration = QUASAR_GM_PRODUCT / (distance * distance);
			double speed = acceleration * TICK_TIME;
			speed = Math.min(speed, 20.0); // クエーサーはより速い吸引

			// 潮汐力計算
			double tidalForce = (2.0 * QUASAR_GM_PRODUCT * ENTITY_LENGTH) / Math.pow(distance, 3);

			if (tidalForce > SPAGHETTIFICATION_THRESHOLD && e instanceof EntityLivingBase) {
				EntityLivingBase living = (EntityLivingBase)e;
				float damage = (float)(tidalForce / SPAGHETTIFICATION_THRESHOLD * 2.0);
				living.attackEntityFrom(DamageSource.OUT_OF_WORLD, damage);

				double stretchFactor = Math.min(tidalForce / SPAGHETTIFICATION_THRESHOLD, 10.0);
				speed *= stretchFactor;
			}

			// 時間の遅延効果
			double schwarzschildRadiusBlocks = QUASAR_SCHWARZSCHILD_RADIUS_METERS;
			double timeDilationFactor = 1.0;

			if (distance > schwarzschildRadiusBlocks) {
				timeDilationFactor = Math.sqrt(1.0 - (schwarzschildRadiusBlocks / distance));
			} else {
				timeDilationFactor = 0.0;
			}

			speed *= timeDilationFactor;

			// 重力を適用
			e.motionX += vec.xCoord * speed;
			e.motionY += vec.yCoord * speed;
			e.motionZ += vec.zCoord * speed;

			// 事象の地平線内に到達したエンティティを削除
			if (distance < schwarzschildRadiusBlocks * 10) {
				if (e instanceof EntityPlayer) {
					EntityPlayer player = (EntityPlayer)e;
					player.setDead();

					if (!world.isRemote) {
						player.sendMessage(new net.minecraft.util.text.TextComponentString(
								"§4§lクエーサーの事象の地平線を越えました。あなたはスパゲッティ化されました。"));
					}
				}

				if (!world.isRemote) {
					if (e instanceof EntityQuackos) {
						((EntityQuackos)e).despawn();
					} else {
						e.setDead();
					}

					// エンティティの質量を膠着円盤に追加
					double entityMass = 100.0;
					if (e instanceof EntityLivingBase) {
						EntityLivingBase living = (EntityLivingBase)e;
						entityMass = living.getMaxHealth() * 10.0;
					}

					// 親クラスのメソッドを使用して膠着円盤に追加
					this.accretionDiskMass += entityMass;
					updateAccretionDiskSize();
				}
			}
		}
	}

	/**
	 * 膠着円盤のサイズを現在の質量に基づいて更新
	 */
	private void updateAccretionDiskSize() {
		// 膠着円盤の質量から視覚的なサイズを計算
		// クエーサーの場合、基準サイズがすでに巨大なので、
		// 追加の質量による成長は控えめに
		double additionalRadius = (accretionDiskMass - OVERWORLD_TOTAL_MASS * DISK_MASS_RATIO) * 0.001;

		accretionDiskOuterRadius = QUASAR_INITIAL_DISK_OUTER_RADIUS + additionalRadius;

		// 最大サイズ制限（レンダリング負荷を考慮）
		if(accretionDiskOuterRadius > 10000.0) {
			accretionDiskOuterRadius = 10000.0;
		}
	}

	// ============================================================
	// NBTデータの保存・読み込み
	// ============================================================

	@Override
	protected void readEntityFromNBT(NBTTagCompound compound) {
		super.readEntityFromNBT(compound);

		// 既存チャンクを復元
		if (compound.hasKey("existingChunksSize")) {
			int size = compound.getInteger("existingChunksSize");
			this.existingChunks = new HashSet<>();
			for (int i = 0; i < size; i++) {
				this.existingChunks.add(compound.getLong("existingChunk_" + i));
			}
		}

		this.generatorReplaced = compound.getBoolean("generatorReplaced");

		// チャンクジェネレーターを再度置き換え（ワールド再読み込み後）
		if (!world.isRemote && generatorReplaced) {
			replaceChunkGenerator();
		}
	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound compound) {
		super.writeEntityToNBT(compound);

		// 既存チャンクを保存
		compound.setInteger("existingChunksSize", existingChunks.size());
		int index = 0;
		for (Long chunkKey : existingChunks) {
			compound.setLong("existingChunk_" + index, chunkKey);
			index++;
		}

		compound.setBoolean("generatorReplaced", generatorReplaced);
	}

	// ============================================================
	// ユーティリティメソッド
	// ============================================================

	@Override
	public String getStats() {
		double currentSchwarzschildRadius = getQuasarSchwarzschildRadius();
		double hawkingTemperature = calculateQuasarHawkingTemperature();
		double photonSphereRadius = currentSchwarzschildRadius * 1.5;
		double iscoRadius = currentSchwarzschildRadius * 3.0;
		double evaporationTime = calculateQuasarEvaporationTime();

		// 膠着率の計算 (kg/tick)
		double currentAccretionRate = accretionDiskMass * 0.001; // クエーサー用の膠着率
		double accretionRatePerSecond = currentAccretionRate / TICK_TIME;

		return String.format(
				"=== Quasar Statistics ===\n" +
						"Initial Schwarzschild Radius: %.2f meters (%.2f blocks)\n" +
						"Initial Mass: %.2e kg (%.2f Earth masses)\n" +
						"\n" +
						"=== Current Status ===\n" +
						"Current Mass: %.2e kg\n" +
						"Absorbed Mass (beyond initial): %.2e kg\n" +
						"Current Schwarzschild Radius: %.2f meters\n" +
						"Current Gravitational Range: %.2f blocks (%.2f km)\n" +
						"Existing Chunks (at initialization): %d\n" +
						"Generator Replaced: %s\n" +
						"\n" +
						"=== Accretion Disk ===\n" +
						"Accretion Disk Mass: %.2e kg\n" +
						"Accretion Disk Outer Radius: %.2f blocks (%.2f km)\n" +
						"Accretion Rate: %.2e kg/tick (%.2e kg/s)\n" +
						"Total Blocks Destroyed: %,d\n" +
						"\n" +
						"=== Important Radii ===\n" +
						"Event Horizon: %.2f meters\n" +
						"Photon Sphere (1.5 rs): %.2f meters\n" +
						"ISCO (3 rs): %.2f meters\n" +
						"\n" +
						"=== Hawking Radiation ===\n" +
						"Hawking Temperature: %.2e K\n" +
						"Accumulated Radiation Energy: %.2e J\n" +
						"Estimated Evaporation Time: %.2e seconds (%.2e years)\n" +
						"\n" +
						"=== Relativistic Effects ===\n" +
						"Ticks Existed: %d\n",
				QUASAR_SCHWARZSCHILD_RADIUS_METERS,
				QUASAR_SCHWARZSCHILD_RADIUS_METERS,
				QUASAR_MASS,
				QUASAR_MASS / 5.972e24, // Earth mass
				this.accumulatedMass,
				this.accumulatedMass - (OVERWORLD_TOTAL_MASS * BLACK_HOLE_MASS_RATIO),
				currentSchwarzschildRadius,
				this.currentGravityRange,
				this.currentGravityRange / 1000.0,
				this.existingChunks.size(),
				this.generatorReplaced ? "Yes" : "No",
				this.accretionDiskMass,
				this.accretionDiskOuterRadius,
				this.accretionDiskOuterRadius / 1000.0,
				currentAccretionRate,
				accretionRatePerSecond,
				this.totalBlocksDestroyed,
				currentSchwarzschildRadius,
				photonSphereRadius,
				iscoRadius,
				hawkingTemperature,
				this.hawkingRadiationAccumulated,
				evaporationTime,
				evaporationTime / (365.25 * 24 * 3600),
				this.ticksExisted
		);
	}

	private double getQuasarSchwarzschildRadius() {
		return (2.0 * GRAVITATIONAL_CONSTANT * this.accumulatedMass) / (SPEED_OF_LIGHT * SPEED_OF_LIGHT);
	}

	private double calculateQuasarHawkingTemperature() {
		return (HAWKING_CONSTANT * Math.pow(SPEED_OF_LIGHT, 3))
				/ (8.0 * Math.PI * GRAVITATIONAL_CONSTANT * this.accumulatedMass * BOLTZMANN_CONSTANT);
	}

	private double calculateQuasarEvaporationTime() {
		double evaporationTimeConstant = 2.1e67;
		double solarMass = 1.989e30;
		double timeRatio = Math.pow(this.accumulatedMass / solarMass, 3);
		return evaporationTimeConstant * timeRatio;
	}
}