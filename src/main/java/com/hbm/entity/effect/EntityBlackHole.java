package com.hbm.entity.effect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.hbm.config.CompatibilityConfig;
import com.hbm.entity.mob.EntityQuackos;
import com.hbm.entity.projectile.EntityRubble;
import com.hbm.interfaces.IConstantRenderer;
import com.hbm.render.amlfrom1710.Vec3;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 物理的に正確なブラックホール実装(2025年版) - 膠着円盤実装
 *
 * 実装された物理現象:
 * 1. シュヴァルツシルト半径と事象の地平線
 * 2. 重力による時間の遅延(Gravitational Time Dilation)
 * 3. 潮汐力とスパゲッティ化(Tidal Forces & Spaghettification)
 * 4. ホーキング放射による蒸発(Hawking Radiation)
 * 5. 光子球(Photon Sphere)
 * 6. 重力赤方偏移(Gravitational Redshift)
 * 7. 質量増加による半径成長
 * 8. 膠着円盤(Accretion Disk)とその物理
 *
 * 膠着円盤の物理:
 * - 膠着円盤は最内安定円軌道(ISCO: r = 3rs)から外側に広がる
 * - 物質は膠着円盤内で角運動量を失いながら徐々に内側に落下
 * - 膠着円盤内の物質は摩擦により加熱され、X線を放射
 * - 膠着率(Accretion Rate): dM/dt = 物質が事象の地平線に落下する速度
 * - 膠着効率: η ≈ 0.1 (物質の静止質量の10%がエネルギーに変換)
 *
 * 物理定数:
 * - シュヴァルツシルト半径: rs = 1 μm = 1×10⁻⁶ m
 * - 質量: M = rs × c² / (2G) = 6.73×10²⁰ kg
 * - 重力定数: G = 6.674×10⁻¹¹ m³·kg⁻¹·s⁻²
 * - 光速: c = 2.998×10⁸ m/s
 *
 * 重要な距離:
 * - 事象の地平線: r = rs = 1×10⁻⁶ m
 * - 光子球(Photon Sphere): r = 1.5 × rs
 * - 最内安定円軌道(ISCO): r = 3 × rs
 * - 地球重力と同等になる距離: ~67,700m (67.7km)
 */
public class EntityBlackHole extends Entity implements IConstantRenderer {

	// ============================================================
	// 物理定数(サブクラスからもアクセス可能)
	// ============================================================
	protected static final double GRAVITATIONAL_CONSTANT = 6.674e-11; // G (m³·kg⁻¹·s⁻²)
	protected static final double SPEED_OF_LIGHT = 2.998e8; // c (m/s)
	protected static final double SCHWARZSCHILD_RADIUS_METERS = 1e-6; // 1 マイクロメートル
	protected static final double BLACK_HOLE_MASS; // kg
	protected static final double GM_PRODUCT; // G × M

	// 重要な半径(シュヴァルツシルト半径の倍数として)
	private static final double PHOTON_SPHERE_MULTIPLIER = 1.5; // 光子球
	private static final double ISCO_MULTIPLIER = 3.0; // 最内安定円軌道

	// Minecraftブロックの物理特性
	protected static final double BLOCK_MASS_KG = 2400.0; // 石ブロックの質量 (kg)
	protected static final double TICK_TIME = 0.05; // 1 tick = 1/20秒

	// 光速（Minecraftスケール）
	// 1ブロック = 1メートル
	// 光速 (ブロック/tick) = c (m/s) × TICK_TIME (s/tick)
	protected static final double SPEED_OF_LIGHT_BLOCKS_PER_TICK = SPEED_OF_LIGHT * TICK_TIME; // 約 1.499×10^7 ブロック/tick

	// 重力範囲管理
	private static final double INITIAL_RANGE = 100.0; // 初期範囲 (ブロック)
	protected static final double MAX_RANGE = 67700.0; // 最大範囲 (ブロック) - 地球重力と同等
	private static final double RANGE_GROWTH_RATE = 1.1; // 範囲成長率 (毎tick 10%増加)

	// 潮汐力定数
	protected static final double SPAGHETTIFICATION_THRESHOLD = 500.0; // N/m - スパゲッティ化が始まる潮汐力
	protected static final double ENTITY_LENGTH = 2.0; // エンティティの長さ(メートル)

	// 即死圏内の定義（物理演算）
	// 地球の表面重力: g_earth = 9.8 m/s²
	// 即死圏内：ブラックホールの重力が地球の1000倍になる距離
	// a = GM/r² = 1000 × g_earth
	// r = √(GM / (1000 × g_earth))
	protected static final double EARTH_SURFACE_GRAVITY = 9.8; // m/s²
	protected static final double INSTAKILL_GRAVITY_MULTIPLIER = 1000.0; // 地球重力の1000倍
	protected static final double INSTAKILL_RADIUS; // メートル単位

	// ホーキング放射定数
	protected static final double HAWKING_CONSTANT = 1.0545718e-34; // ℏ (プランク定数 / 2π)
	protected static final double BOLTZMANN_CONSTANT = 1.380649e-23; // k (ボルツマン定数)
	private static final double STEFAN_BOLTZMANN_CONSTANT = 5.670374419e-8; // σ (ステファン・ボルツマン定数)

	// シミュレーション最適化
	private static final double DESTRUCTION_LAYER_THICKNESS = 1.0; // 破壊層の厚さ(ブロック)

	// ============================================================
	// 膠着円盤の物理定数
	// ============================================================

	// 膠着円盤のサイズ定数
	private static final double ACCRETION_DISK_INNER_RADIUS_MULTIPLIER = 3.0; // ISCO = 3rs
	private static final double ACCRETION_DISK_INITIAL_OUTER_RADIUS = 10.0; // 初期外半径 (ブロック)

	// 膠着率の定数
	// 1ブロック破壊 → 膠着円盤に質量追加 → 一定時間後に事象の地平線に落下
	private static final double ACCRETION_EFFICIENCY = 0.1; // 10%がエネルギーに変換
	private static final double DISK_MASS_TO_SIZE_RATIO = 0.01; // 1kg → 0.01ブロック増加

	// 膠着円盤から事象の地平線への落下率
	// 膠着円盤内の物質は指数関数的に内側に移動し、最終的に事象の地平線を越える
	private static final double ACCRETION_RATE_PER_TICK = 0.001; // 毎tick、膠着円盤質量の0.1%が吸収される

	// 膠着円盤の視覚的な最大サイズ(レンダリング用)
	private static final double MAX_ACCRETION_DISK_VISUAL_SIZE = 50.0; // ブロック単位

	static {
		// M = rs × c² / (2G)
		BLACK_HOLE_MASS = (SCHWARZSCHILD_RADIUS_METERS * SPEED_OF_LIGHT * SPEED_OF_LIGHT)
				/ (2.0 * GRAVITATIONAL_CONSTANT);
		GM_PRODUCT = GRAVITATIONAL_CONSTANT * BLACK_HOLE_MASS;

		// 即死圏内の半径を計算: r = √(GM / (1000 × g_earth))
		double targetAcceleration = EARTH_SURFACE_GRAVITY * INSTAKILL_GRAVITY_MULTIPLIER;
		INSTAKILL_RADIUS = Math.sqrt(GM_PRODUCT / targetAcceleration);
	}

	// ============================================================
	// データ管理
	// ============================================================

	// 後方互換性のためのSIZEパラメータを維持(サブクラス用)
	public static final DataParameter<Float> SIZE = EntityDataManager.createKey(EntityBlackHole.class, DataSerializers.FLOAT);

	// 実際のブラックホールの範囲管理用
	protected float currentGravityRange;

	// 質量はNBTのみで管理(DataSerializerにDOUBLEがないため)
	protected double accumulatedMass; // サブクラスからアクセス可能に

	// ホーキング放射による蒸発管理
	public long ticksExisted;
	protected double hawkingRadiationAccumulated; // 累積放射エネルギー

	// 球体状破壊のための現在の破壊半径
	protected double currentDestructionRadius;

	// ============================================================
	// 膠着円盤のデータ
	// ============================================================

	// 膠着円盤内の質量(まだ事象の地平線に落ちていない物質)
	protected double accretionDiskMass;

	// 膠着円盤の外半径(ブロック単位)
	protected double accretionDiskOuterRadius;

	// 累積破壊ブロック数(統計用)
	protected long totalBlocksDestroyed;

	// ============================================================
	// コンストラクタ
	// ============================================================

	public EntityBlackHole(World worldIn) {
		super(worldIn);
		this.ignoreFrustumCheck = true;
		this.isImmuneToFire = true;
		this.accumulatedMass = BLACK_HOLE_MASS;
		this.currentGravityRange = (float)INITIAL_RANGE;
		this.ticksExisted = 0;
		this.hawkingRadiationAccumulated = 0.0;
		this.currentDestructionRadius = 0.0; // 中心から破壊開始

		// 膠着円盤の初期化
		this.accretionDiskMass = 0.0;
		this.accretionDiskOuterRadius = ACCRETION_DISK_INITIAL_OUTER_RADIUS;
		this.totalBlocksDestroyed = 0;
	}

	// 後方互換性のための古いコンストラクタ(サブクラス用)
	public EntityBlackHole(World w, float size){
		this(w);
		this.getDataManager().set(SIZE, size);
	}

	public EntityBlackHole(World w, double x, double y, double z){
		this(w);
		this.setPosition(x, y, z);
	}

	// ============================================================
	// 完全無敵化
	// ============================================================

	@Override
	public boolean isImmuneToExplosions() {
		return true;
	}

	@Override
	public boolean attackEntityFrom(DamageSource source, float amount) {
		return false; // すべてのダメージを無効化
	}

	@Override
	public boolean canBeCollidedWith() {
		return false;
	}

	@Override
	public boolean canBePushed() {
		return false;
	}

	@Override
	public void setDead() {
		// setDeadを無効化 - ブラックホールは永久不滅
		// 何もしない
	}

	@Override
	public boolean isEntityInvulnerable(DamageSource source) {
		return true; // 完全無敵
	}

	@Override
	protected void dealFireDamage(int amount) {
		// 火ダメージ無効
	}

	// ============================================================
	// メイン更新処理
	// ============================================================

	@Override
	public void onUpdate() {
		// 通常のEntity.onUpdate()を呼ばずに独自の更新処理のみ実行
		// これによりエンティティの自動削除を回避

		if(!CompatibilityConfig.isWarDim(world)){
			return; // 削除しない - 永久に残る
		}

		// 年齢を増やさない(ticksExistedは通常の更新処理で増える)
		this.ticksExisted++;

		if(!world.isRemote) {
			// 範囲を徐々に拡大
			if(currentGravityRange < MAX_RANGE) {
				currentGravityRange = (float)(currentGravityRange * RANGE_GROWTH_RATE);
			}

			// 膠着円盤から事象の地平線への質量移動
			processAccretionDiskToEventHorizon();

			// ホーキング放射による蒸発を計算
			calculateHawkingRadiation();

			// ブロック破壊処理
			destroyBlocks(currentGravityRange);
		}

		// エンティティへの重力影響
		applyGravitationalForces();
	}

	// ============================================================
	// 膠着円盤の物理処理
	// ============================================================

	/**
	 * 膠着円盤から事象の地平線への質量移動を処理
	 *
	 * 膠着円盤内の物質は、粘性摩擦により角運動量を失い、
	 * 徐々に内側に螺旋状に落下していく。
	 *
	 * このプロセスは連続的だが、シミュレーションでは毎tickごとに
	 * 膠着円盤質量の一定割合が事象の地平線に落下すると仮定。
	 */
	protected void processAccretionDiskToEventHorizon() {
		if(accretionDiskMass <= 0) {
			return;
		}

		// 毎tick、膠着円盤質量の0.1%が事象の地平線に落下
		double massAccretedThisTick = accretionDiskMass * ACCRETION_RATE_PER_TICK;

		// 膠着円盤から質量を減らす
		accretionDiskMass -= massAccretedThisTick;

		// ブラックホールの質量に追加
		accumulatedMass += massAccretedThisTick * (1.0 - ACCRETION_EFFICIENCY);

		// 膠着効率分(10%)はエネルギーとして放射される
		// (これは膠着円盤の明るさとして視覚化できる)
		double radiatedEnergy = massAccretedThisTick * ACCRETION_EFFICIENCY *
				SPEED_OF_LIGHT * SPEED_OF_LIGHT;

		// 膠着円盤の外半径を質量に応じて調整
		// 質量が減ると円盤は徐々に縮小
		updateAccretionDiskSize();
	}

	/**
	 * 膠着円盤のサイズを現在の質量に基づいて更新
	 */
	private void updateAccretionDiskSize() {
		// 膠着円盤の質量から視覚的なサイズを計算
		// 1kg → 0.01ブロック増加の比率
		double additionalRadius = accretionDiskMass * DISK_MASS_TO_SIZE_RATIO;

		// 最内安定円軌道(ISCO)からの距離として外半径を計算
		double iscoRadius = calculateNewSchwarzschildRadius() * ISCO_MULTIPLIER;
		accretionDiskOuterRadius = ACCRETION_DISK_INITIAL_OUTER_RADIUS + additionalRadius;

		// 最大サイズ制限
		if(accretionDiskOuterRadius > MAX_ACCRETION_DISK_VISUAL_SIZE) {
			accretionDiskOuterRadius = MAX_ACCRETION_DISK_VISUAL_SIZE;
		}
	}

	// ============================================================
	// ホーキング放射と蒸発
	// ============================================================

	/**
	 * ホーキング放射による質量減少を計算
	 *
	 * ホーキング温度: T = (ℏc³) / (8πGMk)
	 * 放射パワー: P = (ℏc⁶) / (15360πG²M²)
	 * 蒸発時間: t ∝ M³
	 *
	 * 太陽質量のブラックホールの蒸発時間: ~10⁶⁷年
	 * このブラックホール(6.73×10²⁰ kg)の蒸発時間: 非常に短い(数時間〜数日)
	 */
	private void calculateHawkingRadiation() {
		// 現在のシュヴァルツシルト半径を計算
		double currentSchwarzschildRadius = calculateNewSchwarzschildRadius();

		// ホーキング温度を計算: T = (ℏc³) / (8πGMk)
		double hawkingTemperature = (HAWKING_CONSTANT * Math.pow(SPEED_OF_LIGHT, 3))
				/ (8.0 * Math.PI * GRAVITATIONAL_CONSTANT * this.accumulatedMass * BOLTZMANN_CONSTANT);

		// 放射パワーを計算: P = (ℏc⁶) / (15360πG²M²)
		double radiationPower = (HAWKING_CONSTANT * Math.pow(SPEED_OF_LIGHT, 6))
				/ (15360.0 * Math.PI * Math.pow(GRAVITATIONAL_CONSTANT, 2) * Math.pow(this.accumulatedMass, 2));

		// 1 tick(0.05秒)あたりの放射エネルギー
		double energyRadiatedPerTick = radiationPower * TICK_TIME;

		// E = mc² より質量減少を計算
		double massLossPerTick = energyRadiatedPerTick / (SPEED_OF_LIGHT * SPEED_OF_LIGHT);

		// 質量を減少させる
		this.accumulatedMass -= massLossPerTick;
		this.hawkingRadiationAccumulated += energyRadiatedPerTick;

		// 質量が極端に小さくなったら爆発的に蒸発
		if(this.accumulatedMass < BLACK_HOLE_MASS * 0.01) {
			// 最終的な爆発エネルギーを放出
			if(!world.isRemote) {
				// 大爆発を起こす
				world.createExplosion(this, posX, posY, posZ,
						(float)(this.hawkingRadiationAccumulated / 1e15), true);

				// 完全に蒸発 - この場合のみsetDeadを直接呼ぶ
				this.isDead = true;
			}
		}
	}

	// ============================================================
	// ブロック破壊処理
	// ============================================================

	/**
	 * ブロック破壊処理 - 破壊範囲内のすべてのブロックを常に監視して即座に削除
	 *
	 * ブロック破壊時の物理:
	 * 1. ブロックは即座に破壊される(事象の地平線近くの強大な潮汐力)
	 * 2. ブロックの質量の一部は膠着円盤に追加される
	 * 3. 膠着円盤の質量増加により、円盤の外半径が増加
	 */
	protected void destroyBlocks(float range) {
		// 破壊半径を徐々に拡大
		currentDestructionRadius += DESTRUCTION_LAYER_THICKNESS;

		// 破壊範囲を超えたら範囲に合わせる
		if(currentDestructionRadius > range) {
			currentDestructionRadius = range;
		}

		// 破壊半径の2乗(最適化)
		double destructionRadiusSquared = currentDestructionRadius * currentDestructionRadius;

		// 球体の境界ボックスを計算
		int minX = (int)Math.floor(posX - currentDestructionRadius);
		int maxX = (int)Math.ceil(posX + currentDestructionRadius);
		int minY = (int)Math.floor(posY - currentDestructionRadius);
		int maxY = (int)Math.ceil(posY + currentDestructionRadius);
		int minZ = (int)Math.floor(posZ - currentDestructionRadius);
		int maxZ = (int)Math.ceil(posZ + currentDestructionRadius);

		// 範囲内のすべてのブロックをチェックして即座に削除
		for(int x = minX; x <= maxX; x++) {
			double dx = x + 0.5 - posX; // ブロックの中心を使用
			double dxSquared = dx * dx;

			for(int y = minY; y <= maxY; y++) {
				double dy = y + 0.5 - posY;
				double dySquared = dy * dy;

				for(int z = minZ; z <= maxZ; z++) {
					double dz = z + 0.5 - posZ;
					double dzSquared = dz * dz;

					// ブラックホール中心からの距離の2乗を計算
					double distanceSquared = dxSquared + dySquared + dzSquared;

					// 破壊範囲内にあるか確認(0 <= distance <= currentDestructionRadius)
					if(distanceSquared <= destructionRadiusSquared) {
						BlockPos pos = new BlockPos(x, y, z);

						// チャンクがロードされているかチェック
						if(!world.isBlockLoaded(pos)) {
							continue;
						}

						IBlockState state = world.getBlockState(pos);

						// 空気ブロックはスキップ
						if(state.getBlock() == Blocks.AIR || state.getBlock().isAir(state, world, pos)) {
							continue;
						}

						// ブロックを破壊(範囲内のブロックは即座に削除)
						if(state.getMaterial().isLiquid()) {
							world.setBlockState(pos, Blocks.AIR.getDefaultState());
						} else {
							world.setBlockState(pos, Blocks.AIR.getDefaultState());

							// ブロックの質量を膠着円盤に追加
							addMassToAccretionDisk(BLOCK_MASS_KG);

							// 統計更新
							this.totalBlocksDestroyed++;
						}
					}
				}
			}
		}
	}

	/**
	 * 膠着円盤に質量を追加
	 *
	 * @param mass 追加する質量(kg)
	 */
	private void addMassToAccretionDisk(double mass) {
		// 膠着円盤の質量に追加
		accretionDiskMass += mass;

		// 膠着円盤のサイズを更新
		updateAccretionDiskSize();
	}

	// ============================================================
	// エンティティへの重力影響
	// ============================================================

	/**
	 * エンティティへの重力影響を適用
	 *
	 * 実装された効果:
	 * 1. 重力加速度: a = GM/r²
	 * 2. 潮汐力とスパゲッティ化
	 * 3. 時間の遅延による視覚効果
	 * 4. ブロック貫通(noClip有効化)
	 */
	private void applyGravitationalForces() {
		List<Entity> entities = world.getEntitiesWithinAABBExcludingEntity(this, new AxisAlignedBB(
				posX - currentGravityRange, posY - currentGravityRange, posZ - currentGravityRange,
				posX + currentGravityRange, posY + currentGravityRange, posZ + currentGravityRange));

		for(Entity e : entities) {
			// 他のブラックホールは無視
			if(e instanceof EntityBlackHole)
				continue;

			// クリエイティブモードのプレイヤーは影響を受けない(テスト用)
			if(e instanceof EntityPlayer && ((EntityPlayer)e).capabilities.isCreativeMode)
				continue;

			// EntityFallingBlockは即座に削除
			if(e instanceof EntityFallingBlock && !world.isRemote) {
				e.setDead();
				continue;
			}

			// ブラックホールまでの距離と方向を計算
			Vec3 vec = Vec3.createVectorHelper(posX - e.posX, posY - e.posY, posZ - e.posZ);
			double distance = vec.length();

			if(distance > currentGravityRange || distance < 0.1) // 0除算防止
				continue;

			vec = vec.normalize();

			// ============================================================
			// 即死圏内の処理（地球重力の1000倍の重力範囲）
			// 処理落ち防止のため、即死圏内では加速せずに即座に削除
			// ============================================================

			if(distance < INSTAKILL_RADIUS) {
				// プレイヤーの場合、特別な即死処理
				if(e instanceof EntityPlayer) {
					EntityPlayer player = (EntityPlayer)e;

					// クリエイティブモードのプレイヤーは除外（既にチェック済みだが念のため）
					if(player.capabilities.isCreativeMode) {
						continue;
					}

					if(!world.isRemote) {
						// 装備スロットを全て空にする（アイテムをドロップ）
						net.minecraft.inventory.EntityEquipmentSlot[] slots = net.minecraft.inventory.EntityEquipmentSlot.values();
						for(net.minecraft.inventory.EntityEquipmentSlot slot : slots) {
							net.minecraft.item.ItemStack stack = player.getItemStackFromSlot(slot);
							if(!stack.isEmpty()) {
								player.entityDropItem(stack, 0.0F);
								player.setItemStackToSlot(slot, net.minecraft.item.ItemStack.EMPTY);
							}
						}

						// インベントリを全て空にする
						player.inventory.clear();

						// 肉片パーティクルを生成
						spawnGibletsParticles(player.posX, player.posY + player.height * 0.5, player.posZ, player.getEntityId());

						// プレイヤーにメッセージを送信
						player.sendMessage(new net.minecraft.util.text.TextComponentString(
								"§4§l§n即死圏内に到達しました。あなたは重力の暴力により粉砕されました。"));
					}

					// setDeadを強制実行（完全な即死）
					forcePlayerDeath(player);
				}

				// エンティティを完全に削除
				if(!world.isRemote) {
					// 生物エンティティの場合も肉片パーティクルを生成
					if(e instanceof EntityLivingBase && !(e instanceof EntityPlayer)) {
						spawnGibletsParticles(e.posX, e.posY + e.height * 0.5, e.posZ, e.getEntityId());
					}

					// Quackosの場合は専用のdespawn()メソッドを使用
					if(e instanceof EntityQuackos) {
						((EntityQuackos)e).despawn();
					} else {
						// setDeadを複数回呼び出して確実に削除
						e.setDead();
						e.isDead = true;
						e.setDead();
					}

					// 質量増加 (エンティティは軽いので影響は微小)
					double entityMass = 100.0; // 仮定値
					if(e instanceof EntityLivingBase) {
						// 生物の質量をより正確に推定
						EntityLivingBase living = (EntityLivingBase)e;
						entityMass = living.getMaxHealth() * 10.0; // 体力から推定
					}

					// エンティティの質量も膠着円盤に追加
					addMassToAccretionDisk(entityMass);
				}

				// 即死圏内のエンティティは加速せずに即座に削除したので、次のエンティティへ
				continue;
			}

			// ============================================================
			// エンティティをブロック貫通させる(重力に引き寄せられる際)
			// ============================================================

			// 重力範囲内のエンティティはブロックを貫通できる
			if(distance < currentGravityRange * 0.5) { // 範囲の半分以内
				e.noClip = true;
			}

			// ============================================================
			// 1. 相対論的重力加速度の計算（即死圏外のエンティティのみ）
			// ============================================================

			// エンティティの現在の速度を計算（ブロック/tick）
			double currentSpeedX = e.motionX;
			double currentSpeedY = e.motionY;
			double currentSpeedZ = e.motionZ;
			double currentSpeed = Math.sqrt(currentSpeedX * currentSpeedX +
			                                 currentSpeedY * currentSpeedY +
			                                 currentSpeedZ * currentSpeedZ);

			// 古典的な重力加速度を計算: a = GM/r²
			double acceleration = GM_PRODUCT / (distance * distance);

			// Minecraftスケールでの速度変化（ブロック/tick）
			double deltaV = acceleration * TICK_TIME;

			// 事象の地平線付近では吸引力を5倍に強化
			if(distance < SCHWARZSCHILD_RADIUS_METERS * 5) {
				deltaV *= 5.0;
			}
			// ISCOの内側では吸引力を3倍に強化
			else if(distance < calculateNewSchwarzschildRadius() * ISCO_MULTIPLIER) {
				deltaV *= 3.0;
			}

			// ============================================================
			// 相対論的効果の適用
			// ============================================================
			// 速度が光速に近づくにつれて、加速が困難になる
			// ローレンツ因子: γ = 1 / √(1 - v²/c²)
			// 相対論的加速度: a' = a / γ³

			// v/c の比率を計算
			double velocityRatio = currentSpeed / SPEED_OF_LIGHT_BLOCKS_PER_TICK;

			// ローレンツ因子を計算（速度が光速に近づくと無限大に近づく）
			double lorentzFactor = 1.0;
			if(velocityRatio < 0.9999) { // 光速の99.99%未満
				double vSquaredOverCSquared = velocityRatio * velocityRatio;
				lorentzFactor = 1.0 / Math.sqrt(1.0 - vSquaredOverCSquared);
			} else {
				// 光速に非常に近い場合、これ以上加速できない
				lorentzFactor = 1000.0; // 非常に大きな値で加速を極めて困難にする
			}

			// 相対論的な加速度を適用
			// 速度が光速に近づくほど、lorentzFactor³で加速が困難になる
			double relativisticDeltaV = deltaV / Math.pow(lorentzFactor, 3.0);

			// ============================================================
			// 2. 潮汐力とスパゲッティ化
			// ============================================================

			// 潮汐力を計算: ΔF = 2GM × Δr / r³
			// ここでΔrはエンティティの長さ(頭から足まで)
			double tidalForce = (2.0 * GM_PRODUCT * ENTITY_LENGTH) / Math.pow(distance, 3);

			// スパゲッティ化の閾値を超えた場合、エンティティにダメージ
			if(tidalForce > SPAGHETTIFICATION_THRESHOLD && e instanceof EntityLivingBase) {
				EntityLivingBase living = (EntityLivingBase)e;

				// 潮汐力に比例したダメージ
				float damage = (float)(tidalForce / SPAGHETTIFICATION_THRESHOLD);
				living.attackEntityFrom(DamageSource.OUT_OF_WORLD, damage);

				// エンティティを引き伸ばす視覚効果(モーションで表現)
				double stretchFactor = Math.min(tidalForce / SPAGHETTIFICATION_THRESHOLD, 5.0);
				relativisticDeltaV *= stretchFactor;
			}

			// ============================================================
			// 3. 時間の遅延効果
			// ============================================================

			// 時間の遅延係数を計算: τ = √(1 - rs/r)
			double schwarzschildRadius = calculateNewSchwarzschildRadius();
			double schwarzschildRadiusBlocks = SCHWARZSCHILD_RADIUS_METERS; // メートル = ブロック

			double timeDilationFactor = 1.0;
			if(distance > schwarzschildRadiusBlocks) {
				timeDilationFactor = Math.sqrt(1.0 - (schwarzschildRadiusBlocks / distance));
			} else {
				// 事象の地平線内では時間が無限に遅くなる
				timeDilationFactor = 0.0;
			}

			// 時間の遅延により、エンティティの動きが遅くなる
			// (遠くの観測者から見た場合)
			relativisticDeltaV *= timeDilationFactor;

			// ============================================================
			// 4. エンティティへの加速度適用と光速制限
			// ============================================================

			// ブラックホール方向への速度変化を適用
			e.motionX += vec.xCoord * relativisticDeltaV;
			e.motionY += vec.yCoord * relativisticDeltaV;
			e.motionZ += vec.zCoord * relativisticDeltaV;

			// 適用後の新しい速度を計算
			double newSpeed = Math.sqrt(e.motionX * e.motionX +
			                            e.motionY * e.motionY +
			                            e.motionZ * e.motionZ);

			// 光速制限：速度が光速を超える場合、光速に制限
			if(newSpeed > SPEED_OF_LIGHT_BLOCKS_PER_TICK) {
				double scale = SPEED_OF_LIGHT_BLOCKS_PER_TICK / newSpeed;
				e.motionX *= scale;
				e.motionY *= scale;
				e.motionZ *= scale;
			}
			// ============================================================
			// 5. 事象の地平線内に到達したエンティティを削除（即死圏外の場合）
			// ============================================================

			else if(distance < SCHWARZSCHILD_RADIUS_METERS * 5) { // 事象の地平線付近
				// プレイヤーは即死
				if(e instanceof EntityPlayer) {
					EntityPlayer player = (EntityPlayer)e;

					if(!world.isRemote) {
						player.sendMessage(new net.minecraft.util.text.TextComponentString(
								"§4§l事象の地平線を越えました。あなたはスパゲッティ化されました。"));
					}

					player.setDead();
				}

				// エンティティを完全に削除
				if(!world.isRemote) {
					// Quackosの場合は専用のdespawn()メソッドを使用
					if(e instanceof EntityQuackos) {
						((EntityQuackos)e).despawn();
					} else {
						e.setDead();
					}

					// 質量増加 (エンティティは軽いので影響は微小)
					double entityMass = 100.0; // 仮定値
					if(e instanceof EntityLivingBase) {
						// 生物の質量をより正確に推定
						EntityLivingBase living = (EntityLivingBase)e;
						entityMass = living.getMaxHealth() * 10.0; // 体力から推定
					}

					// エンティティの質量も膠着円盤に追加
					addMassToAccretionDisk(entityMass);
				}
			}
		}
	}

	// ============================================================
	// NBTデータの保存・読み込み
	// ============================================================

	@Override
	protected void entityInit() {
		this.getDataManager().register(SIZE, 0.5F); // サブクラス互換性用のみ
	}

	@Override
	protected void readEntityFromNBT(NBTTagCompound compound) {
		this.currentGravityRange = compound.getFloat("currentGravityRange");
		this.accumulatedMass = compound.getDouble("accumulatedMass");
		this.ticksExisted = compound.getLong("ticksExisted");
		this.hawkingRadiationAccumulated = compound.getDouble("hawkingRadiationAccumulated");
		this.currentDestructionRadius = compound.getDouble("currentDestructionRadius");

		// 膠着円盤データの読み込み
		this.accretionDiskMass = compound.getDouble("accretionDiskMass");
		this.accretionDiskOuterRadius = compound.getDouble("accretionDiskOuterRadius");
		this.totalBlocksDestroyed = compound.getLong("totalBlocksDestroyed");

		// 後方互換性
		if(compound.hasKey("currentRange")) {
			this.currentGravityRange = compound.getFloat("currentRange");
		}
	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound compound) {
		compound.setFloat("currentGravityRange", this.currentGravityRange);
		compound.setDouble("accumulatedMass", this.accumulatedMass);
		compound.setLong("ticksExisted", this.ticksExisted);
		compound.setDouble("hawkingRadiationAccumulated", this.hawkingRadiationAccumulated);
		compound.setDouble("currentDestructionRadius", this.currentDestructionRadius);

		// 膠着円盤データの保存
		compound.setDouble("accretionDiskMass", this.accretionDiskMass);
		compound.setDouble("accretionDiskOuterRadius", this.accretionDiskOuterRadius);
		compound.setLong("totalBlocksDestroyed", this.totalBlocksDestroyed);
	}

	// ============================================================
	// レンダリング設定（膠着円盤を無限距離から可視に）
	// ============================================================

	@Override
	@SideOnly(Side.CLIENT)
	public boolean isInRangeToRenderDist(double distance) {
		// 膠着円盤を無限距離から見えるようにする
		// レンダラー側でLODを適用して負荷を軽減
		return true;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public int getBrightnessForRender() {
		return 15728880;
	}

	@Override
	public float getBrightness() {
		return 1.0F;
	}

	// ============================================================
	// ユーティリティメソッド
	// ============================================================

	/**
	 * ブラックホールの現在の統計情報を取得(デバッグ用)
	 */
	public String getStats() {
		double currentSchwarzschildRadius = calculateNewSchwarzschildRadius();
		double hawkingTemperature = calculateHawkingTemperature();
		double timeDilationAtEventHorizon = calculateTimeDilationAtEventHorizon();
		double photonSphereRadius = currentSchwarzschildRadius * PHOTON_SPHERE_MULTIPLIER;
		double iscoRadius = currentSchwarzschildRadius * ISCO_MULTIPLIER;
		double evaporationTime = calculateEvaporationTime();

		// 膠着率の計算 (kg/tick)
		double currentAccretionRate = accretionDiskMass * ACCRETION_RATE_PER_TICK;

		// 膠着率を秒単位に変換 (kg/s)
		double accretionRatePerSecond = currentAccretionRate / TICK_TIME;

		return String.format(
				"=== Black Hole Statistics ===\n" +
						"Initial Schwarzschild Radius: %.2e meters (%.2e blocks)\n" +
						"Initial Mass: %.2e kg\n" +
						"\n" +
						"=== Current Status ===\n" +
						"Current Mass: %.2e kg\n" +
						"Absorbed Mass: %.2e kg\n" +
						"Current Schwarzschild Radius: %.2e meters\n" +
						"Current Gravitational Range: %.2f blocks\n" +
						"Current Destruction Radius: %.2f blocks\n" +
						"\n" +
						"=== Important Radii ===\n" +
						"Event Horizon: %.2e meters\n" +
						"Photon Sphere (1.5 rs): %.2e meters\n" +
						"ISCO (3 rs): %.2e meters\n" +
						"\n" +
						"=== Accretion Disk ===\n" +
						"Accretion Disk Mass: %.2e kg\n" +
						"Accretion Disk Outer Radius: %.2f blocks\n" +
						"Accretion Rate: %.2e kg/tick (%.2e kg/s)\n" +
						"Total Blocks Destroyed: %d\n" +
						"Average Mass per Block: %.2f kg\n" +
						"\n" +
						"=== Hawking Radiation ===\n" +
						"Hawking Temperature: %.2e K\n" +
						"Accumulated Radiation Energy: %.2e J\n" +
						"Estimated Evaporation Time: %.2e seconds (%.2e years)\n" +
						"\n" +
						"=== Relativistic Effects ===\n" +
						"Time Dilation at Event Horizon: %.2f%%\n" +
						"Ticks Existed: %d\n",
				SCHWARZSCHILD_RADIUS_METERS,
				SCHWARZSCHILD_RADIUS_METERS,
				BLACK_HOLE_MASS,
				this.accumulatedMass,
				this.accumulatedMass - BLACK_HOLE_MASS,
				currentSchwarzschildRadius,
				this.currentGravityRange,
				this.currentDestructionRadius,
				currentSchwarzschildRadius,
				photonSphereRadius,
				iscoRadius,
				this.accretionDiskMass,
				this.accretionDiskOuterRadius,
				currentAccretionRate,
				accretionRatePerSecond,
				this.totalBlocksDestroyed,
				this.totalBlocksDestroyed > 0 ? (this.accretionDiskMass + (this.accumulatedMass - BLACK_HOLE_MASS)) / this.totalBlocksDestroyed : 0.0,
				hawkingTemperature,
				this.hawkingRadiationAccumulated,
				evaporationTime,
				evaporationTime / (365.25 * 24 * 3600),
				timeDilationAtEventHorizon * 100.0,
				this.ticksExisted
		);
	}

	/**
	 * 現在の質量から新しいシュヴァルツシルト半径を計算
	 */
	private double calculateNewSchwarzschildRadius() {
		return (2.0 * GRAVITATIONAL_CONSTANT * this.accumulatedMass) / (SPEED_OF_LIGHT * SPEED_OF_LIGHT);
	}

	/**
	 * ホーキング温度を計算
	 */
	private double calculateHawkingTemperature() {
		return (HAWKING_CONSTANT * Math.pow(SPEED_OF_LIGHT, 3))
				/ (8.0 * Math.PI * GRAVITATIONAL_CONSTANT * this.accumulatedMass * BOLTZMANN_CONSTANT);
	}

	/**
	 * 事象の地平線での時間の遅延を計算
	 */
	private double calculateTimeDilationAtEventHorizon() {
		// 事象の地平線では時間の遅延は無限大(0に近づく)
		return 0.0;
	}

	/**
	 * 蒸発時間を推定
	 */
	private double calculateEvaporationTime() {
		// 蒸発時間は質量の3乗に比例: t ∝ M³
		double evaporationTimeConstant = 2.1e67; // 太陽質量のブラックホールの蒸発時間(秒)
		double solarMass = 1.989e30; // kg

		double timeRatio = Math.pow(this.accumulatedMass / solarMass, 3);
		return evaporationTimeConstant * timeRatio;
	}

	/**
	 * レンダリング用:現在のシュヴァルツシルト半径を取得(メートル単位)
	 */
	public double getCurrentSchwarzschildRadius() {
		return calculateNewSchwarzschildRadius();
	}

	/**
	 * レンダリング用:現在の質量を取得
	 */
	public double getCurrentMass() {
		return this.accumulatedMass;
	}

	/**
	 * レンダリング用:光子球の半径を取得
	 */
	public double getPhotonSphereRadius() {
		return calculateNewSchwarzschildRadius() * PHOTON_SPHERE_MULTIPLIER;
	}

	/**
	 * レンダリング用:最内安定円軌道の半径を取得
	 */
	public double getISCORadius() {
		return calculateNewSchwarzschildRadius() * ISCO_MULTIPLIER;
	}

	/**
	 * レンダリング用:膠着円盤の内半径を取得(ブロック単位)
	 */
	public double getAccretionDiskInnerRadius() {
		// 膠着円盤はISCO(r = 3rs)から始まる
		return calculateNewSchwarzschildRadius() * ACCRETION_DISK_INNER_RADIUS_MULTIPLIER;
	}

	/**
	 * レンダリング用:膠着円盤の外半径を取得(ブロック単位)
	 */
	public double getAccretionDiskOuterRadius() {
		return this.accretionDiskOuterRadius;
	}

	/**
	 * レンダリング用:膠着円盤の質量を取得
	 */
	public double getAccretionDiskMass() {
		return this.accretionDiskMass;
	}

	/**
	 * レンダリング用:膠着円盤の相対的な明るさを計算(0.0〜1.0)
	 * 膠着率が高いほど明るい
	 */
	public float getAccretionDiskBrightness() {
		// 膠着率に基づいて明るさを計算
		double currentAccretionRate = accretionDiskMass * ACCRETION_RATE_PER_TICK;

		// 正規化: 1ブロック/tickの膠着率を最大明度とする
		float brightness = (float)Math.min(currentAccretionRate / BLOCK_MASS_KG, 1.0);

		// 最低でも10%の明度を保つ(膠着円盤が見えるように)
		return Math.max(brightness, 0.1f);
	}

	/**
	 * 指定された距離での重力時間の遅延係数を計算
	 * @param distance ブラックホールからの距離(ブロック単位)
	 * @return 時間の遅延係数(0.0〜1.0)
	 */
	public double getTimeDilationFactor(double distance) {
		double schwarzschildRadius = calculateNewSchwarzschildRadius();

		if(distance <= schwarzschildRadius) {
			return 0.0; // 事象の地平線内
		}

		return Math.sqrt(1.0 - (schwarzschildRadius / distance));
	}

	/**
	 * 指定された距離での潮汐力を計算
	 * @param distance ブラックホールからの距離(ブロック単位)
	 * @return 潮汐力(N/m)
	 */
	public double getTidalForce(double distance) {
		return (2.0 * GM_PRODUCT * ENTITY_LENGTH) / Math.pow(distance, 3);
	}

	// ============================================================
	// 即死システムの補助メソッド
	// ============================================================

	/**
	 * プレイヤーを強制的に死亡させる
	 * すべての無敵・復活システムを無視
	 *
	 * @param player 対象プレイヤー
	 */
	private void forcePlayerDeath(EntityPlayer player) {
		if(world.isRemote) return;

		// 体力を0にする
		player.setHealth(0.0F);

		// capabilities.disableDamageを一時的に無効化
		boolean wasInvulnerable = player.capabilities.disableDamage;
		player.capabilities.disableDamage = false;

		// setDeadを複数回呼び出して確実に削除
		player.setDead();
		player.isDead = true;
		player.setDead();

		// 強制的に死亡処理を実行
		player.onDeath(net.minecraft.util.DamageSource.OUT_OF_WORLD);

		// さらにsetDeadを呼び出す
		player.setDead();

		// capabilities.disableDamageを元に戻す（念のため）
		player.capabilities.disableDamage = wasInvulnerable;
	}

	/**
	 * 肉片パーティクルを生成
	 *
	 * @param x X座標
	 * @param y Y座標
	 * @param z Z座標
	 * @param entityId エンティティID
	 */
	private void spawnGibletsParticles(double x, double y, double z, int entityId) {
		if(world.isRemote) return;

		// 肉片パーティクルを生成
		net.minecraft.nbt.NBTTagCompound vdat = new net.minecraft.nbt.NBTTagCompound();
		vdat.setString("type", "giblets");
		vdat.setInteger("ent", entityId);

		// ブラックホールの位置を設定（パーティクルが吸い込まれる先）
		vdat.setDouble("bhX", this.posX);
		vdat.setDouble("bhY", this.posY);
		vdat.setDouble("bhZ", this.posZ);
		vdat.setDouble("bhMass", this.accumulatedMass);
		vdat.setDouble("schwarzschildRadius", calculateNewSchwarzschildRadius());

		com.hbm.packet.PacketDispatcher.wrapper.sendToAllAround(
			new com.hbm.packet.AuxParticlePacketNT(vdat, x, y, z),
			new net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint(
				world.provider.getDimension(), x, y, z, 300)
		);
	}
}