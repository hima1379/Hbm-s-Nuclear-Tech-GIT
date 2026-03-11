package com.hbm.main.tileentity.turret;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.hbm.config.WeaponConfig;
import com.hbm.entity.logic.EntityTurretDecoy;
import com.hbm.entity.projectile.EntityBulletGAU8;
import com.hbm.handler.BulletConfigSyncingUtil;
import com.hbm.handler.BulletConfiguration;
import com.hbm.lib.HBMSoundHandler;
import com.hbm.lib.Library;
import com.hbm.main.tileentity.turret.FireControlSystemGAU8;
import com.hbm.lib.ModDamageSource;
import com.hbm.packet.AuxParticlePacketNT;
import com.hbm.packet.PacketDispatcher;
import com.hbm.render.amlfrom1710.Vec3;
import com.hbm.util.EntityDamageUtil;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;
import org.jetbrains.annotations.NotNull;



public class TileEntityTurretHoward extends TileEntityTurretBaseNT {
	static List<Integer> configs = new ArrayList<>();

	static {
		configs.add(BulletConfigSyncingUtil.DGK_NORMAL);
	}

	// Goalkeeper CIWSスタイルの高度な追跡システム
	private TargetTrackingSystem trackingSystem = new TargetTrackingSystem();

	// 射撃管制システム統合（Goalkeeper CIWSの高精度計算を使用）
	private Vec3d lastFireSolutionAimPoint = null;
	private double lastFireSolutionError = Double.MAX_VALUE;
	private int shotsFiredInBurst = 0;
	private static final int BURST_EVALUATION_INTERVAL = 10; // 10発ごとに精度評価

	// ========================================
	// 密集ターゲット連射モード（CIWS本来の運用）
	// ========================================
	// 敵が密集している場合、全ターゲットを殲滅するため
	// 射撃しながら照準変更を行い、連続射撃を優先する
	// （射撃精度は維持したまま、照準完了を待たずに射撃）
	private int clusterEnemyCount = 0; // 密集範囲内の敵数
	private boolean burstFireMode = false; // 連射モード（密集時に有効化）
	private static final double CLUSTER_DETECTION_RANGE = 100.0D; // 密集判定距離（100m）
	private static final int CLUSTER_ENEMY_THRESHOLD = 3; // 密集判定の最低敵数（3体以上で密集と判定）
	private static final double BURST_FIRE_ALIGNMENT_TOLERANCE = 2.0; // 連射モード時の照準許容誤差（2度）

	// ========================================
	// STTロックオン型チャンクロードシステム
	// Radar NTの軽量スキャン + 戦闘機STTロック方式
	// ========================================
	private net.minecraftforge.common.ForgeChunkManager.Ticket turretTicket = null;
	private Set<ChunkPos> trajectoryLoadedChunks = new HashSet<>(); // 弾道専用チャンク
	private java.util.Map<net.minecraft.util.math.ChunkPos, Integer> trajectoryChunkLoadTime = new java.util.HashMap<>(); // ロード時刻（tick）
	private int tickCount = 0; // タレットのtick数
	private static final int TRAJECTORY_CHUNK_TIMEOUT = 200; // 10秒でアンロード（長距離射撃対応）
	private boolean chunkLoaderInitialized = false;

	// STTロックオンステート
	private enum LockState {
		SCANNING,      // スキャン中（ターゲット検索）
		LOCKED,        // ロックオン完了
		LOADING_TRAJECTORY, // 弾道チャンクロード中
		READY_TO_FIRE  // 射撃準備完了
	}
	private LockState lockState = LockState.SCANNING;
	private Entity lockedTarget = null; // ロックオンしたターゲット
	private int lockDuration = 0; // ロック継続時間（tick）
	private static final int MIN_LOCK_DURATION = 10; // 最小ロック時間（0.5秒）

	// 距離適応型更新間隔（パフォーマンス最適化）
	private static final int CHUNK_LOAD_UPDATE_INTERVAL_CLOSE = 10;  // 近距離（0-1000m）: 0.5秒
	private static final int CHUNK_LOAD_UPDATE_INTERVAL_MID = 20;    // 中距離（1000-2000m）: 1秒
	private static final int CHUNK_LOAD_UPDATE_INTERVAL_FAR = 40;    // 遠距離（2000-3000m）: 2秒

	// 3000m = 187.5チャンク → 安全のため190チャンクまでロード
	private static final int MAX_CHUNK_LOAD_DISTANCE_3000M = 190; // 3040m先まで確実にカバー

	@Override
	protected List<Integer> getAmmoList(){
		return configs;
	}

	@Override
	public void init(net.minecraftforge.common.ForgeChunkManager.Ticket ticket) {
		super.init(ticket);
		// タレットのチケットを保存（弾丸の射線上チャンクロードに使用）
		// 弾丸の個別チケットが失敗するため、タレットのチケットで代替
		this.turretTicket = ticket;
		if (turretTicket != null) {
			System.out.println("[Turret-Howard] ========================================");
			System.out.println("[Turret-Howard] 3000m CHUNK LOADING SYSTEM INITIALIZED");
			System.out.println("[Turret-Howard] Max range: 3040m (190 chunks)");
			System.out.println("[Turret-Howard] Adaptive update intervals: 0.5s-2s");
			System.out.println("[Turret-Howard] Turret position: " + this.pos);
			System.out.println("[Turret-Howard] ========================================");
		} else {
			System.out.println("[Turret-Howard] ERROR: Failed to receive chunk ticket!");
			System.out.println("[Turret-Howard] 3000m shooting will NOT work without ticket!");
		}
	}

	@Override
	public String getName(){
		return "container.turretHoward";
	}

	@Override
	public double getHeightOffset(){
		return 2.25D;
	}

	@Override
	public double getDecetorGrace(){
		return 3D;
	}

	@Override
	public double getTurretYawSpeed(){
		return 25D; // 超高速旋回（Goalkeeper CIWSの性能を再現）
	}

	@Override
	public double getTurretPitchSpeed(){
		return 20D; // 超高速仰俯角調整（Goalkeeper CIWSの性能を再現）
	}

	@Override
	public double getTurretElevation(){
		return 90D;
	}

	@Override
	public double getTurretDepression(){
		return 50D;
	}

	@Override
	public double getDecetorRange(){
		return 3000D; // GAU-8の最大有効射程（すべてのターゲットタイプに適用）
	}

	@Override
	public double getAcceptableInaccuracy() {
		// Goalkeeper CIWS超高精度照準許容誤差（距離適応型）
		// 3000m射撃を実現するため、距離に応じて許容誤差を動的に調整
		//
		// 近距離では早期射撃開始、遠距離では完璧な照準を待つ
		// 注：連射モード時も射撃精度は維持（照準完了判定は別途緩和）
		if (this.target == null || this.tPos == null) {
			return 0.5D; // デフォルト
		}

		Vec3d turretPos = this.getTurretPos();
		double distance = turretPos.distanceTo(this.tPos);

		// 距離に応じた許容誤差（度）
		// 0-500m: 0.2度（十分高精度、早期射撃で弾幕形成）
		// 500-1500m: 0.1度（高精度）
		// 1500-3000m: 0.05度（超高精度、3000mで2.6m誤差）
		// 3000m+: 0.03度（極限精度、3000mで1.6m誤差）
		double acceptableAngleDegrees;

		if (distance < 500) {
			acceptableAngleDegrees = 0.2;
		} else if (distance < 1500) {
			// 500-1500m: 0.2度から0.1度へ線形減少
			double t = (distance - 500) / 1000.0;
			acceptableAngleDegrees = 0.2 - t * 0.1;
		} else if (distance < 3000) {
			// 1500-3000m: 0.1度から0.05度へ線形減少
			double t = (distance - 1500) / 1500.0;
			acceptableAngleDegrees = 0.1 - t * 0.05;
		} else {
			// 3000m以上: 0.03度（極限精度）
			acceptableAngleDegrees = 0.03;
		}

		// FCS射撃解の品質が高い場合、さらに厳格化
		if (lastFireSolutionError < 1.0 && distance > 1500) {
			acceptableAngleDegrees *= 0.8; // 20%厳格化
		}

		return acceptableAngleDegrees;
	}

	@Override
	public int getDecetorInterval() {
		return 5; // 高速ターゲットスキャン（10 ticks -> 5 ticks）
	}

	@Override
	public double getBarrelLength(){
		return 3.25D;
	}

	@Override
	public long getMaxPower(){
		return 50000;
	}

	@Override
	public long getConsumption(){
		return 500;
	}

	int loaded;
	int timer;
	public float spin;
	public float lastSpin;

	// デバッグ用カウンター
	private int updateCounter = 0;
	private static final int DEBUG_INTERVAL = 20; // 20 tickごと（1秒）- デバッグ用に短縮

	// 予測位置を保存
	private Vec3d cachedPredictedPosition = null;
	// FCS射撃解をキャッシュ（毎tick 2回計算を防ぐ）
	private FireControlSystemGAU8.FireSolution cachedFireSolution = null;
	// ターゲットを一時保存（基底クラスがtPosを上書きしないようにするため）
	private Entity cachedTarget = null;
	// 前回のターゲット（ターゲット変更検出用）
	private Entity previousTarget = null;

	@Override
	public void update(){
		// クライアント側・サーバー側共通：前フレームの回転を保存
		this.lastRotationPitch = this.rotationPitch;
		this.lastRotationYaw = this.rotationYaw;

		// クライアント側の処理
		if(world.isRemote) {
			// バレルのスピンアニメーション
			this.lastSpin = this.spin;

			if(this.tPos != null) {
				this.spin += 45;
			}

			if(this.spin >= 360F) {
				this.spin -= 360F;
				this.lastSpin -= 360F;
			}

			// 回転補間の修正（360度を跨ぐ場合）
			if(Math.abs(this.lastRotationYaw - this.rotationYaw) > Math.PI) {
				if(this.lastRotationYaw < this.rotationYaw)
					this.lastRotationYaw += Math.PI * 2;
				else
					this.lastRotationYaw -= Math.PI * 2;
			}

			return; // クライアント側はここで終了
		}

		// サーバー側の処理
		{
			// CONFIGからアグロシステムの有効/無効を読み込む
			// configがリロードされた場合に反映されるよう、毎tick読み込む
			boolean configEnabled = com.hbm.config.WeaponConfig.turretDecoyEntityEnabled;

			// configで無効化された場合、既存のデコイを削除
			if (!configEnabled && this.aggroSystemEnabled && this.decoyEntity != null) {
				System.out.println("[Turret-Howard] Config disabled aggro system - removing existing decoy");
				this.decoyEntity.allowDeath();
				this.decoyEntity.setDead();
				this.decoyEntity = null;
			}

			this.aggroSystemEnabled = configEnabled;

			// チャンクローダーを初期化（初回のみ）- 親クラスのupdate()をオーバーライドしているため、ここで初期化が必要
			if (!chunkLoaderInitialized && turretTicket == null) {
				net.minecraftforge.common.ForgeChunkManager.Ticket ticket =
					net.minecraftforge.common.ForgeChunkManager.requestTicket(
						com.hbm.main.MainRegistry.instance,
						world,
						net.minecraftforge.common.ForgeChunkManager.Type.NORMAL
					);
				if (ticket != null) {
					System.out.println("[Turret-Howard] CRITICAL: Requesting chunk ticket for 3000m system...");
					init(ticket);
					chunkLoaderInitialized = true;
				} else {
					System.out.println("[Turret-Howard] ERROR: Failed to request chunk ticket from ForgeChunkManager!");
				}
			}

			// alignedフラグをリセット（毎tick最初にリセット）
			this.aligned = false;

			// Tick数をインクリメント（チャンクタイムアウト管理用）
			tickCount++;

			// 古い弾道チャンクをアンロード（5秒経過後）
			if (tickCount % 20 == 0) { // 1秒ごとにチェック
				updateTrajectoryChunkCleanup();
			}

			// デバッグログ：タレットが動作しているか確認
			updateCounter++;

			// サーバー: 弾薬リロード
			if(loaded <= 0) {
				BulletConfiguration conf = this.getFirstConfigLoaded();

				if(conf != null) {
					this.conusmeAmmo(conf.ammo);
					this.world.playSound(null, pos.getX(), pos.getY(), pos.getZ(), HBMSoundHandler.howard_reload, SoundCategory.BLOCKS, 4.0F, 1F);
					loaded = 200;
				}
			}

			// ターゲットが死亡しているかチェック
			if(this.target != null && !target.isEntityAlive()) {
				this.target = null;
				this.stattrak++;
			}

			// ターゲットが視界にあるかチェック
			if(this.target != null) {
				if(!this.entityInLOS(this.target)) {
					this.target = null;
				}
			}

			// Goalkeeper CIWSスタイルの超高精度射撃管制システム統合
			cachedPredictedPosition = null;
			cachedFireSolution = null; // CRITICAL: FCS結果もリセット
			if(this.target != null && isOn() && hasPower()) {
				Vec3d turretPos = this.getTurretPos();
				Vec3d targetCurrentPos = new Vec3d(this.target.posX, this.target.posY + this.target.height * 0.5, this.target.posZ);
				double distance = turretPos.distanceTo(targetCurrentPos);

				// FireControlSystemGAU8の高度な計算を使用（Runge-Kutta 4次、カルマンフィルター等）
				FireControlSystemGAU8.FireSolution solution = FireControlSystemGAU8.calculateOptimalFireSolution(
					turretPos,
					this.target
				);

				if (solution != null) {
					// 射撃解が見つかった場合
					cachedPredictedPosition = solution.aimPoint;
					cachedFireSolution = solution; // CRITICAL: FCS結果をキャッシュ（2回計算を防ぐ）
					this.tPos = cachedPredictedPosition;

					// 射撃解の品質を記録（クローズドループ補正用）
					lastFireSolutionAimPoint = solution.aimPoint;
					lastFireSolutionError = solution.error;

				} else {
					// 射撃解が見つからない場合 = FCSの収束条件が厳しすぎる
					// この場合、ターゲットに直接狙うことで最低限の射撃を可能にする
					System.out.println("[Turret-Howard] WARNING: FCS failed to converge at " +
						String.format("%.0f", distance) + "m - aiming directly at target (reduced accuracy)");

					// ターゲットの現在位置を直接狙う（精度低下を受け入れる）
					// 重力補正なしのため、遠距離では弾道が低くなるが、overshooting問題は回避
					cachedPredictedPosition = targetCurrentPos;
					this.tPos = cachedPredictedPosition;
					lastFireSolutionError = distance * 0.1; // 10%の誤差を想定
				}
			}

			// 予測位置に向けて旋回
			if(cachedPredictedPosition != null && isOn() && hasPower()) {
				// 予測位置を設定
				this.tPos = cachedPredictedPosition;

				// CRITICAL FIX: 親クラスのturnTowards()を使わず、FCSの射撃角度を直接使用
				// これにより弾道落下を正しく補正した角度で射撃可能
				// PERFORMANCE FIX: キャッシュされたFCS結果を再利用（2回計算を防ぐ）
				if (cachedFireSolution != null) {
					// FCSが計算した射撃角度を直接使用（0も有効な角度）
					this.turnTowardsWithFireAngles(cachedFireSolution.firePitch, cachedFireSolution.fireYaw);
				} else {
					// Fallback: 親クラスのメソッドを使用
					this.turnTowards(this.tPos);
				}

				// 電力消費
				this.setPower(this.getPower() - this.getConsumption());
			}

			// 電力チャージ
			this.power = Library.chargeTEFromItems(inventory, 10, this.power, this.getMaxPower());

			// ターゲット検索タイマー
			if(isOn() && hasPower()) {
				searchTimer--;
				if(searchTimer <= 0) {
					searchTimer = this.getDecetorInterval();
					// CRITICAL FIX: 常に再スキャン（ターゲットの有無に関わらず）
					// 600m離れた場所にテレポートしたプレイヤーも検出可能にする
					this.seekNewTarget();
				}
			} else {
				searchTimer = 0;
			}

			// 整列していて、ターゲットがあり、STTロックオン完了後のみ射撃
			if(lockState == LockState.READY_TO_FIRE &&
			   this.aligned &&
			   this.target != null &&
			   cachedPredictedPosition != null) {
				this.updateFiringTick();
			}

			// ========================================
			// 簡略化されたロック管理（AABB方式では複雑な状態遷移不要）
			// ========================================
			if (this.target != null && isOn() && hasPower()) {
				// ターゲット有効 - ロック継続カウント
				lockDuration++;

				// 最小ロック時間に到達したら射撃準備完了
				if (lockDuration >= MIN_LOCK_DURATION && lockState != LockState.READY_TO_FIRE) {
					lockState = LockState.READY_TO_FIRE;
				}
			} else {
				// ターゲット無効 - ロックリセット
				if (lockDuration > 0 || lockState != LockState.SCANNING) {
					lockState = LockState.SCANNING;
					lockedTarget = null;
					lockDuration = 0;
				}
			}

			// ネットワーク同期
			this.updateConnectionsAndNetwork();

			// アグロシステム更新
			this.updateAggroSystem();
		} // サーバー側の処理終了
	}

	/**
	 * 距離に応じたチャンクロード更新間隔を取得
	 */
	private int getChunkLoadUpdateInterval() {
		if (this.target == null || this.tPos == null) {
			return CHUNK_LOAD_UPDATE_INTERVAL_MID;
		}

		double distance = this.getTurretPos().distanceTo(this.tPos);

		if (distance < 1000) {
			return CHUNK_LOAD_UPDATE_INTERVAL_CLOSE; // 0.5秒（近距離は頻繁に更新）
		} else if (distance < 2000) {
			return CHUNK_LOAD_UPDATE_INTERVAL_MID; // 1秒
		} else {
			return CHUNK_LOAD_UPDATE_INTERVAL_FAR; // 2秒（遠距離は低頻度でOK）
		}
	}

	/**
	 * 基底クラスのネットワーク処理と接続更新を実行
	 */
	private void updateConnectionsAndNetwork() {
		// 接続を更新
		this.updateConnections();

		// ネットワークパケット送信（回転情報を含む）
		NBTTagCompound data = new NBTTagCompound();
		if(this.tPos != null) {
			data.setDouble("tX", this.tPos.x);
			data.setDouble("tY", this.tPos.y);
			data.setDouble("tZ", this.tPos.z);
		}
		data.setLong("power", this.power);
		data.setBoolean("isOn", this.isOn);
		data.setBoolean("targetPlayers", this.targetPlayers);
		data.setBoolean("targetAnimals", this.targetAnimals);
		data.setBoolean("targetMobs", this.targetMobs);
		data.setBoolean("targetMachines", this.targetMachines);
		data.setInteger("stattrak", this.stattrak);

		// 回転情報を追加（クライアント側でレンダリングに使用）
		data.setDouble("rotationYaw", this.rotationYaw);
		data.setDouble("rotationPitch", this.rotationPitch);

		this.networkPack(data, 250);
	}

	@Override
	public void networkUnpack(NBTTagCompound nbt) {
		super.networkUnpack(nbt);

		// 回転情報を受信
		if(nbt.hasKey("rotationYaw")) {
			this.rotationYaw = nbt.getDouble("rotationYaw");
		}
		if(nbt.hasKey("rotationPitch")) {
			this.rotationPitch = nbt.getDouble("rotationPitch");
		}
	}

	@Override
	public void updateFiringTick(){
		timer++;

		if(loaded > 0 && this.target != null) {

			this.world.playSound(null, pos.getX(), pos.getY(), pos.getZ(), HBMSoundHandler.howard_fire, SoundCategory.BLOCKS, 4.0F, 0.9F + world.rand.nextFloat() * 0.3F);
			this.world.playSound(null, pos.getX(), pos.getY(), pos.getZ(), HBMSoundHandler.howard_fire, SoundCategory.BLOCKS, 4.0F, 1F + world.rand.nextFloat() * 0.3F);

			// Goalkeeper CIWSの連射力: 4,200発/分 = 70発/秒 = 3.5発/tick
			// 交互に3発と4発で平均3.5発/tickを実現
			int bulletsToFire = (timer % 2 == 0) ? 4 : 3;

			for(int i = 0; i < bulletsToFire && loaded > 0; i++) {
				loaded--;

				// タレットの向き通りに弾丸を発射
				fireGAU8Bullet();
			}
		} else {
			// デバッグ: 射撃停止の原因をログ出力（200ティックごと）
			if (timer % 200 == 0) {
				if (loaded <= 0) {
					System.out.println("[Turret-Howard] ✗ NOT FIRING: Out of ammunition (loaded=" + loaded + ")");
				} else if (this.target == null) {
					System.out.println("[Turret-Howard] ✗ NOT FIRING: Target is null (lost target)");
					if (this.tPos != null) {
						double dist = this.getTurretPos().distanceTo(this.tPos);
						System.out.println("[Turret-Howard]   Last known target position: " + (int)dist + "m away");
						System.out.println("[Turret-Howard]   Detector range: " + (int)this.getDecetorRange() + "m");
					}
				}
			}
		}
	}

	/**
	 * GAU-8弾丸を発射（CIWS弾幕パターン実装）
	 *
	 * CIWSの特徴：
	 * - ターゲット周辺に面状の弾幕を形成
	 * - 距離に応じて適切なスプレッドパターン
	 * - ガウス分布で中心に弾丸を集中させつつ、周辺もカバー
	 */
	private void fireGAU8Bullet() {
		// タレット位置を取得
		Vec3d turretPos = this.getTurretPos();

		// 砲身先端位置を計算
		Vec3 vec = Vec3.createVectorHelper(this.getBarrelLength(), 0, 0);
		vec.rotateAroundZ((float) -this.rotationPitch);
		vec.rotateAroundY((float) -(this.rotationYaw + Math.PI * 0.5));

		Vec3d muzzlePos = turretPos.add(vec.xCoord, vec.yCoord, vec.zCoord);

		// タレットの向き通りに射撃方向を計算（基準方向）
		Vec3 fireVec = Vec3.createVectorHelper(1, 0, 0);
		fireVec.rotateAroundZ((float) -this.rotationPitch);
		fireVec.rotateAroundY((float) -(this.rotationYaw + Math.PI * 0.5));

		// ============================================================
		// CIWS弾幕スプレッドパターン
		// ============================================================

		// ターゲットまでの距離を計算
		double targetDistance = (this.target != null && this.tPos != null) ?
			turretPos.distanceTo(this.tPos) : 100.0;

		// Goalkeeper CIWS超高精度スプレッド（3000m射撃対応）
		// 実際のGoalkeeper CIWSは1500-2000mで高精度、これを3000mまで拡張
		// FCS計算済みの照準点に向けて発射するため、スプレッドは極小に抑える
		//
		// 目標: 3000mでの着弾分散を5m以内に（0.095度 = 0.00167 rad以下）
		// 近距離（0-1000m）: 0.03度 = 0.000524 rad（ほぼ完璧）
		// 中距離（1000-2000m）: 0.05度 = 0.000873 rad（非常に高精度）
		// 遠距離（2000-3000m）: 0.08度 = 0.001396 rad（3000mで4.2m分散）
		double spreadAngle;

		// FireControlSystemが高精度計算を行った場合、さらにスプレッドを削減
		double fcsQualityFactor = 1.0;
		if (lastFireSolutionError < 1.0) {
			// 誤差1m以下の超高精度射撃解の場合、スプレッドを50%削減
			fcsQualityFactor = 0.5;
		} else if (lastFireSolutionError < 5.0) {
			// 誤差5m以下の高精度射撃解の場合、スプレッドを75%に削減
			fcsQualityFactor = 0.75;
		}

		if (targetDistance < 1000) {
			spreadAngle = 0.000524; // 0.03度（1000mで0.52m分散）
		} else if (targetDistance < 2000) {
			// 1000-2000m: 0.03度から0.05度へ線形補間
			double t = (targetDistance - 1000) / 1000.0;
			spreadAngle = 0.000524 + t * (0.000873 - 0.000524);
		} else if (targetDistance < 3000) {
			// 2000-3000m: 0.05度から0.08度へ線形補間
			double t = (targetDistance - 2000) / 1000.0;
			spreadAngle = 0.000873 + t * (0.001396 - 0.000873);
		} else {
			// 3000m以上: 0.08度固定（3000mで4.2m、4000mで5.6m分散）
			spreadAngle = 0.001396;
		}

		// FCS品質係数を適用
		spreadAngle *= fcsQualityFactor;

		// ガウス分布による角度オフセット生成
		// 標準偏差 = spreadAngle / 2（95%が±spreadAngle内に収まる）
		double sigma = spreadAngle / 2.0;
		double offsetPitch = world.rand.nextGaussian() * sigma;
		double offsetYaw = world.rand.nextGaussian() * sigma;

		// スプレッド適用後の射撃方向を計算
		Vec3d baseDirection = new Vec3d(fireVec.xCoord, fireVec.yCoord, fireVec.zCoord);

		// 現在の方向から直交ベクトルを計算
		Vec3d up = new Vec3d(0, 1, 0);
		Vec3d right = baseDirection.crossProduct(up).normalize();
		Vec3d actualUp = right.crossProduct(baseDirection).normalize();

		// オフセットを適用
		Vec3d spreadDirection = baseDirection
			.add(right.scale(Math.tan(offsetYaw)))
			.add(actualUp.scale(Math.tan(offsetPitch)))
			.normalize();

		// GAU-8弾丸エンティティを生成
		EntityBulletGAU8 bullet = new EntityBulletGAU8(
			this.world,
			null, // shooter (タレットなのでnull)
			muzzlePos.x,
			muzzlePos.y,
			muzzlePos.z,
			spreadDirection.x,
			spreadDirection.y,
			spreadDirection.z
		);

		// タレット位置を設定（タレットのチャンクをロードし続けるため）
		bullet.setTurretPosition(this.pos);

		// ターゲットエンティティを設定（長距離エンティティ検出用）
		if (this.target != null) {
			bullet.setTargetEntity(this.target);
		}

		// TIMING LOG: 実際の射撃時刻を記録し、遅延tickを計算
		if (cachedFireSolution != null && cachedFireSolution.calculationTime > 0) {
			long fireTime = System.currentTimeMillis();
			long delayMs = fireTime - cachedFireSolution.calculationTime;
			double delayTicks = delayMs / 50.0; // 1 tick = 50ms

			System.out.println("[Turret-Howard] TIMING: Fire solution→Bullet spawn delay = " +
				String.format("%.2f", delayTicks) + " ticks (" + delayMs + "ms)" +
				" | Target distance: " + String.format("%.0f",
					getTurretPos().distanceTo(new Vec3d(target.posX, target.posY, target.posZ))) + "m");
		}

		// 弾丸をスポーン
		this.world.spawnEntity(bullet);

		// 射線上のチャンクをロード（3000m射撃対応）
		if (this.tPos != null) {
			loadTrajectoryChunks(muzzlePos, this.tPos);
		}

		// マズルフラッシュエフェクト
		spawnMuzzleFlash(muzzlePos);

	}

	/**
	 * 遠距離用に最適化された視線チェック
	 * 3000ブロックの射程に対応
	 */
	@Override
	public boolean entityInLOS(Entity e) {
		if(e.isDead || !e.isEntityAlive())
			return false;

		// 透明化ポーション無効（サーマルビジョン）
		if(!hasThermalVision() && e instanceof EntityLivingBase) {
			EntityLivingBase living = (EntityLivingBase) e;
			if(living.isPotionActive(net.minecraft.init.MobEffects.INVISIBILITY))
				return false;
		}

		Vec3d pos = this.getTurretPos();
		Vec3d ent = this.getEntityPos(e);
		Vec3d delta = new Vec3d(ent.x - pos.x, ent.y - pos.y, ent.z - pos.z);
		double length = delta.length();

		// 最小距離と最大距離のチェック
		if(length < this.getDecetorGrace())
			return false;

		// 遠距離用：検出範囲を厳密にチェック（110%ではなく100%）
		// これにより、3000ブロックまで確実にターゲットできる
		if(length > this.getDecetorRange())
			return false;

		// 仰俯角の範囲チェック
		delta = delta.normalize();
		double pitch = Math.asin(delta.y / delta.length());
		double pitchDeg = Math.toDegrees(pitch);

		if(pitchDeg < -this.getTurretDepression() || pitchDeg > this.getTurretElevation())
			return false;

		// 遠距離（1000ブロック以上）の場合、視線チェックを緩和
		// チャンクロードの問題を回避し、3000ブロックでも確実に射撃可能にする
		if(length > 1000.0) {
			// 遠距離では視線チェックをスキップ
			// 弾丸自体が物理シミュレーションとチャンクロードを行うため問題ない
			return true;
		}

		// 近距離〜中距離では通常の視線チェック
		return !com.hbm.lib.Library.isObstructed(world, ent.x, ent.y, ent.z, pos.x, pos.y, pos.z);
	}

	/**
	 * チャンクロード付きターゲットスキャン（3000m完全対応版）
	 *
	 * 重要な変更:
	 * - world.loadedEntityListは既にロードされたチャンク内のエンティティのみ含む
	 * - 600m離れた場所のプレイヤーはworld.loadedEntityListに含まれない
	 * - 解決策: スキャン前に検索範囲のチャンクを一時的にロード
	 *
	 * 新STT方式: チャンクロード → スキャン → ロックオン → 弾道チャンクロード → 射撃
	 */
	@Override
	protected void seekNewTarget() {
		if (world.isRemote) return;

		Vec3d turretPos = this.getTurretPos();
		double maxRange = this.getDecetorRange(); // 3000m

		// ========================================
		// AABB-based entity scanning (like TileEntityMachineRadar)
		// ========================================
		// Get all entities within a 3000-block radius using AABB
		List<Entity> list = world.getEntitiesWithinAABBExcludingEntity(null, new AxisAlignedBB(
			pos.getX() + 0.5 - maxRange,
			0D,
			pos.getZ() + 0.5 - maxRange,
			pos.getX() + 0.5 + maxRange,
			10000,
			pos.getZ() + 0.5 + maxRange));

		// Find the nearest valid target (距離優先順位システム)
		// 複数のターゲットがある場合、CIWSに最も近いターゲットを優先的に迎撃
		Entity nearestTarget = null;
		double nearestDistance = maxRange;
		int scannedCount = 0;
		int validTargetCount = 0;
		int closeRangeEnemyCount = 0; // 近接敵カウント（500m以内）

		for (Entity entity : list) {
			scannedCount++;

			// Check if entity is an acceptable target
			if (!entityAcceptableTarget(entity)) continue;

			validTargetCount++;

			// Calculate distance from CIWS to target
			Vec3d entityPos = this.getEntityPos(entity);
			double distance = turretPos.distanceTo(entityPos);

			// 密集敵カウント（100m以内 = 敵が密集している範囲）
			if (distance <= CLUSTER_DETECTION_RANGE) {
				closeRangeEnemyCount++;
			}

			// Check if within range and closer than current nearest
			// 距離順優先: より近いターゲットのみ選択
			if (distance > maxRange || distance >= nearestDistance) continue;

			// Update nearest target (最近接ターゲットを更新)
			nearestDistance = distance;
			nearestTarget = entity;
		}

		// 密集範囲内の敵数を更新
		this.clusterEnemyCount = closeRangeEnemyCount;

		// 連射モード判定：密集範囲内に3体以上の敵がいる場合は連射モードに切り替え
		// （敵が密集している = 射撃しながら照準変更が必要）
		boolean previousBurstMode = this.burstFireMode;
		this.burstFireMode = (closeRangeEnemyCount >= CLUSTER_ENEMY_THRESHOLD);

		// 連射モード切り替えログ
		if (this.burstFireMode != previousBurstMode) {
			if (this.burstFireMode) {
				System.out.println("[Turret-Howard] ========================================");
				System.out.println("[Turret-Howard] CLUSTER BURST FIRE MODE ACTIVATED");
				System.out.println("[Turret-Howard] Clustered enemies detected: " + closeRangeEnemyCount + " within " + CLUSTER_DETECTION_RANGE + "m");
				System.out.println("[Turret-Howard] Firing while aiming - rapid target switching enabled");
				System.out.println("[Turret-Howard] Maintaining high accuracy, relaxing alignment requirement");
				System.out.println("[Turret-Howard] ========================================");
			} else {
				System.out.println("[Turret-Howard] Cluster burst fire mode DEACTIVATED - returning to standard precision mode");
			}
		}

		// ターゲット発見
		if (nearestTarget != null) {
			// CRITICAL FIX: 既に同じターゲットを追跡中の場合、ロックステートをリセットしない
			// これによりlockDurationがMIN_LOCK_DURATIONに到達可能になる
			boolean isSameTarget = (this.target == nearestTarget);

			this.target = nearestTarget;
			this.tPos = this.getEntityPos(this.target);

			if (!isSameTarget) {
				// 新しいターゲット発見 - ロックステートをリセット
				// STTロックオンステートに移行
				lockState = LockState.LOCKED;
				lockedTarget = nearestTarget;
				lockDuration = 0;

				// 追跡システムリセット
				trackingSystem.resetTracking(this.target);

				// デバッグログ: 複数ターゲット時の優先順位決定
				if (validTargetCount > 1) {
					System.out.println("[Turret-Howard] Target prioritization: " +
						"Selected nearest target at " + String.format("%.1f", nearestDistance) + "m" +
						" (Total valid targets: " + validTargetCount + ")");
				}
			}

			return;
		}

		// ターゲット未発見 - 追跡リセット
		lockState = LockState.SCANNING;
		lockedTarget = null;

		if (this.target != null) {
			trackingSystem.resetTracking(this.target);
		}
		this.target = null;
	}

	/**
	 * FCSの射撃角度を使用してタレットを回転（親クラスのメソッドを使わない）
	 *
	 * 重要: このメソッドは親クラスのturnTowards()を使わず、
	 * FireControlSystemGAU8が計算した弾道補正済み角度を直接使用します
	 *
	 * @param targetPitch 目標仰角（ラジアン）
	 * @param targetYaw 目標方位角（ラジアン）
	 */
	private void turnTowardsWithFireAngles(double targetPitch, double targetYaw) {
		double turnYaw = Math.toRadians(this.getTurretYawSpeed());
		double turnPitch = Math.toRadians(this.getTurretPitchSpeed());
		double pi2 = Math.PI * 2;

		// Pitch（仰角）調整 - CRITICAL FIX: 即座にスナップせず、常に段階的に回転
		double deltaPitchAbs = Math.abs(this.rotationPitch - targetPitch);
		double snapThreshold = Math.toRadians(0.05); // 0.05°以内でのみスナップ

		if(deltaPitchAbs < snapThreshold || deltaPitchAbs > pi2 - snapThreshold) {
			this.rotationPitch = targetPitch; // 極めて近い場合のみスナップ
		} else {
			// 常に段階的に回転（スムーズな動き）
			double actualTurnPitch = Math.min(turnPitch, deltaPitchAbs);
			if(targetPitch > this.rotationPitch)
				this.rotationPitch += actualTurnPitch;
			else
				this.rotationPitch -= actualTurnPitch;
		}

		// Yaw（方位角）調整
		double deltaYaw = (targetYaw - this.rotationYaw) % pi2;

		int dir = 0;
		if(deltaYaw < -Math.PI)
			dir = 1;
		else if(deltaYaw < 0)
			dir = -1;
		else if(deltaYaw > Math.PI)
			dir = -1;
		else if(deltaYaw > 0)
			dir = 1;

		// CRITICAL FIX: Yawも即座にスナップせず、段階的に回転
		double deltaYawAbs = Math.abs(this.rotationYaw - targetYaw);
		if(deltaYawAbs < snapThreshold || deltaYawAbs > pi2 - snapThreshold) {
			this.rotationYaw = targetYaw; // 極めて近い場合のみスナップ
		} else {
			// 常に段階的に回転
			double actualTurnYaw = Math.min(turnYaw, deltaYawAbs);
			this.rotationYaw += actualTurnYaw * dir;
		}

		// 角度を正規化
		this.rotationYaw = this.rotationYaw % pi2;
		this.rotationPitch = this.rotationPitch % pi2;

		// 整列判定
		double deltaPitch = targetPitch - this.rotationPitch;
		deltaYaw = targetYaw - this.rotationYaw;
		double deltaAngle = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
		double acceptableInaccuracy = this.getAcceptableInaccuracy();

		// 連射モード時：射撃精度は維持したまま、照準完了判定を緩和
		// 通常モード：高精度照準完了を待つ（0.03～0.2度）
		// 連射モード：射撃しながら照準変更（2度以内で射撃許可、精度は維持）
		double alignmentThreshold = this.burstFireMode ? BURST_FIRE_ALIGNMENT_TOLERANCE : acceptableInaccuracy;

		if(deltaAngle <= Math.toRadians(alignmentThreshold)) {
			this.aligned = true;
		}

		// ALIGNMENT DEBUG: 整列状態をログ出力（100tickごと）
		if (timer % 100 == 0 && this.target != null) {
			double deltaAngleDeg = Math.toDegrees(deltaAngle);
			System.out.println("[Turret-Howard] ALIGNMENT: " +
				String.format("ΔAngle=%.4f° (req=%.4f°) | Aligned=%s | " +
					"Distance=%.0fm | Target V[%.1f, %.1f, %.1f]",
					deltaAngleDeg,
					acceptableInaccuracy,
					this.aligned ? "✓" : "✗",
					(this.tPos != null ? getTurretPos().distanceTo(this.tPos) : 0),
					(this.target != null ? this.target.motionX * 20 : 0),
					(this.target != null ? this.target.motionY * 20 : 0),
					(this.target != null ? this.target.motionZ * 20 : 0)
				));
		}
	}

	/**
	 * マズルフラッシュエフェクトをスポーン
	 */
	private void spawnMuzzleFlash(Vec3d muzzlePos) {
		// 2つの砲身からのマズルフラッシュ
		Vec3 hOff = Vec3.createVectorHelper(0, 0.25, 0);
		hOff.rotateAroundZ((float) -this.rotationPitch);
		hOff.rotateAroundY((float) -(this.rotationYaw + Math.PI * 0.5));

		for(int i = 0; i < 2; i++) {
			if(i == 1) {
				hOff.xCoord *= -1;
				hOff.yCoord *= -1;
				hOff.zCoord *= -1;
			}

			NBTTagCompound data = new NBTTagCompound();
			data.setString("type", "vanillaExt");
			data.setString("mode", "largeexplode");
			data.setFloat("size", 1.5F);
			data.setByte("count", (byte)1);
			PacketDispatcher.wrapper.sendToAllAround(
				new AuxParticlePacketNT(data, muzzlePos.x + hOff.xCoord, muzzlePos.y + hOff.yCoord, muzzlePos.z + hOff.zCoord),
				new TargetPoint(world.provider.getDimension(), this.pos.getX(), this.pos.getY(), this.pos.getZ(), 300)
			);
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt){
		this.loaded = nbt.getInteger("loaded");
		this.aggroSystemEnabled = nbt.getBoolean("aggroSystemEnabled");
		super.readFromNBT(nbt);
	}

	@Override
	public @NotNull NBTTagCompound writeToNBT(NBTTagCompound nbt){
		nbt.setInteger("loaded", loaded);
		nbt.setBoolean("aggroSystemEnabled", aggroSystemEnabled);
		return super.writeToNBT(nbt);
	}

	@Override
	public void invalidate() {
		super.invalidate();
		// タレットがロードしたチャンクをクリーンアップ
		cleanupLoadedChunks();
		// アグロシステムをクリーンアップ
		disableAggroSystem();
	}

	@Override
	public void onChunkUnload() {
		super.onChunkUnload();
		// タレットがロードしたチャンクをクリーンアップ
		cleanupLoadedChunks();
		// アグロシステムをクリーンアップ
		disableAggroSystem();
	}

	/**
	 * ロードしたチャンクをすべてアンロード（STT方式）
	 */
	/**
	 * 弾道上のチャンクをロード（3000m射撃対応）
	 *
	 * @param from 発射地点
	 * @param to   着弾予測地点
	 */
	private void loadTrajectoryChunks(Vec3d from, Vec3d to) {
		if (turretTicket == null) {
			if (tickCount % 100 == 0) {
				System.out.println("[Turret-Howard] ✗ Cannot load trajectory chunks: turretTicket is null");
			}
			return; // チケット未取得の場合は何もしない
		}

		// 射線をサンプリング（25m間隔 = 約1.5チャンク毎、より密にカバー）
		double distance = from.distanceTo(to);
		int samples = (int) Math.ceil(distance / 25.0); // 25mごとにサンプル（50mから変更）
		samples = Math.max(20, Math.min(samples, 150)); // 最小20、最大150サンプル

		Vec3d direction = to.subtract(from).normalize();

		int newChunksLoaded = 0;
		java.util.Set<net.minecraft.util.math.ChunkPos> chunksToLoad = new java.util.HashSet<>();

		for (int i = 0; i <= samples; i++) {
			double t = (double) i / samples;
			Vec3d point = from.add(direction.scale(distance * t));

			int chunkX = (int) Math.floor(point.x) >> 4;
			int chunkZ = (int) Math.floor(point.z) >> 4;

			// メインチャンクと周囲1チャンクをバッファとしてロード（弾道のばらつき対応）
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					net.minecraft.util.math.ChunkPos chunkPos = new net.minecraft.util.math.ChunkPos(
						chunkX + dx,
						chunkZ + dz
					);
					chunksToLoad.add(chunkPos);
				}
			}
		}

		// チャンクを一括ロード
		for (net.minecraft.util.math.ChunkPos chunkPos : chunksToLoad) {
			// まだロードされていないチャンクのみロード
			if (!trajectoryLoadedChunks.contains(chunkPos)) {
				try {
					net.minecraftforge.common.ForgeChunkManager.forceChunk(turretTicket, chunkPos);
					trajectoryLoadedChunks.add(chunkPos);
					trajectoryChunkLoadTime.put(chunkPos, tickCount);
					newChunksLoaded++;
				} catch (Exception e) {
					System.out.println("[Turret-Howard] ✗ Failed to force chunk " + chunkPos + ": " + e.getMessage());
				}
			} else {
				// 既にロード済みの場合はタイムスタンプを更新（アンロードを遅延）
				trajectoryChunkLoadTime.put(chunkPos, tickCount);
			}
		}

		// デバッグ: チャンクロード状況を定期的にログ出力
		if (newChunksLoaded > 0 || tickCount % 100 == 0) {
			System.out.println("[Turret-Howard] Trajectory chunks: " + trajectoryLoadedChunks.size() +
							   " loaded | " + newChunksLoaded + " new | Distance: " + (int)distance + "m");
		}
	}

	/**
	 * 古い弾道チャンクをアンロード（100tick = 5秒後）
	 */
	private void updateTrajectoryChunkCleanup() {
		if (turretTicket == null) return;

		java.util.Iterator<java.util.Map.Entry<net.minecraft.util.math.ChunkPos, Integer>> it =
			trajectoryChunkLoadTime.entrySet().iterator();

		while (it.hasNext()) {
			java.util.Map.Entry<net.minecraft.util.math.ChunkPos, Integer> entry = it.next();
			net.minecraft.util.math.ChunkPos chunkPos = entry.getKey();
			int loadTime = entry.getValue();

			// タイムアウトしたチャンクをアンロード
			if (tickCount - loadTime > TRAJECTORY_CHUNK_TIMEOUT) {
				try {
					net.minecraftforge.common.ForgeChunkManager.unforceChunk(turretTicket, chunkPos);
					trajectoryLoadedChunks.remove(chunkPos);
				} catch (Exception e) {
					// アンロード失敗時はスキップ
				}
				it.remove();
			}
		}
	}

	private void cleanupLoadedChunks() {
		// すべての弾道チャンクをアンロード
		if (turretTicket != null) {
			for (net.minecraft.util.math.ChunkPos chunkPos : trajectoryLoadedChunks) {
				try {
					net.minecraftforge.common.ForgeChunkManager.unforceChunk(turretTicket, chunkPos);
				} catch (Exception e) {
					// アンロード失敗時はスキップ
				}
			}
		}
		trajectoryLoadedChunks.clear();
		trajectoryChunkLoadTime.clear();
		System.out.println("[CIWS-RADAR] Turret cleanup - unloaded " + trajectoryLoadedChunks.size() + " trajectory chunks | Turret: " + this.pos);
	}

	// ========================================
	// Aggro System (敵対誘引システム) - 完全不死身版
	// ========================================
	// システムレベルでの偽装:
	// EntityTurretDecoyはEntityLivingBaseを継承しているため、
	// すべての敵モブのAI（EntityAINearestAttackableTarget、EntityAIFindEntityNearestPlayer等）から
	// 自動的にターゲットとして検出されます。
	//
	// モブのAIシステムは以下の流れでターゲットを選択：
	// 1. EntityAITargetが範囲内のEntityLivingBaseをスキャン
	// 2. 各エンティティが攻撃可能か判定（isEntityAlive()等）
	// 3. 優先度を計算してターゲットを決定
	//
	// EntityTurretDecoyは完全不死身かつタレット位置に固定されるため、
	// タレット自身がエンティティであるかのように振る舞い、
	// 敵モブのヘイトを引き付けます。
	//
	// 完全不死身の仕組み：
	// - setDead()をオーバーライドして通常は削除不可
	// - すべてのダメージ・エフェクトを無効化
	// - デコイが攻撃されてもタレットは無傷
	// - タレット破壊時のみデコイも削除される（allowDeath()経由）
	// ========================================

	private EntityTurretDecoy decoyEntity = null;
	private boolean aggroSystemEnabled = false; // デフォルトで無効化（configで制御）
	private int aggroUpdateCounter = 0;
	private static final int AGGRO_UPDATE_INTERVAL = 20; // 1秒ごとに更新

	/**
	 * アグロシステムを有効化
	 *
	 * このタレットが敵モブから敵対されるようになります。
	 *
	 * システムレベルでの動作:
	 * - EntityTurretDecoyをタレット位置にスポーン
	 * - すべての敵モブAI（Zombie、Skeleton、Creeper、Spider等）が自動的に検出
	 * - EntityAINearestAttackableTargetは範囲内のEntityLivingBaseを検索するため、
	 *   デコイエンティティは自然にターゲットリストに含まれる
	 * - プレイヤーよりも近い位置にいる場合、優先的にターゲットされる
	 *
	 * 完全不死身の特性:
	 * - デコイは完全無敵（すべてのダメージ・エフェクト無効）
	 * - デコイが攻撃されてもタレットは無傷
	 * - デコイは通常削除不可（タレット破壊時のみ削除）
	 */
	public void enableAggroSystem() {
		if (!world.isRemote) {
			// CONFIG CHECK: デコイエンティティがconfigで無効化されている場合は有効化しない
			if (!com.hbm.config.WeaponConfig.turretDecoyEntityEnabled) {
				System.out.println("[Turret-Howard] Aggro system is DISABLED in config - not enabling");
				this.aggroSystemEnabled = false;
				return;
			}

			this.aggroSystemEnabled = true;
			System.out.println("[Turret-Howard] ========================================");
			System.out.println("[Turret-Howard] AGGRO SYSTEM ENABLED (IMMORTAL MODE)");
			System.out.println("[Turret-Howard] Turret position: " + this.pos);
			System.out.println("[Turret-Howard] All hostile mobs will target this turret");
			System.out.println("[Turret-Howard] System-level AI deception active");
			System.out.println("[Turret-Howard] Decoy is IMMORTAL - cannot be killed");
			System.out.println("[Turret-Howard] ========================================");
		}
	}

	/**
	 * アグロシステムを無効化
	 */
	public void disableAggroSystem() {
		if (!world.isRemote) {
			this.aggroSystemEnabled = false;

			// デコイエンティティの削除（複数の方法で確実に削除）
			System.out.println("[Turret-Howard] DISABLING aggro system at " + this.pos);

			// 方法1: 保持している decoyEntity 参照から削除
			if (decoyEntity != null) {
				System.out.println("[Turret-Howard]   -> Removing decoy entity reference (isDead=" + decoyEntity.isDead + ")");
				decoyEntity.allowDeath();
				decoyEntity.setDead();
				decoyEntity = null;
			}

			// 方法2: ワールド内のすべての EntityTurretDecoy を検索して、このタレットの位置と一致するものを削除
			// （参照が失われている場合や、複数のデコイが生成されている場合に対応）
			java.util.List<EntityTurretDecoy> allDecoys = world.getEntitiesWithinAABB(
				EntityTurretDecoy.class,
				new net.minecraft.util.math.AxisAlignedBB(
					this.pos.getX() - 2, this.pos.getY() - 2, this.pos.getZ() - 2,
					this.pos.getX() + 2, this.pos.getY() + 2, this.pos.getZ() + 2
				)
			);

			if (!allDecoys.isEmpty()) {
				System.out.println("[Turret-Howard]   -> Found " + allDecoys.size() + " decoy entities near turret position");
				for (EntityTurretDecoy decoy : allDecoys) {
					System.out.println("[Turret-Howard]   -> Removing decoy at [" +
						String.format("%.1f, %.1f, %.1f", decoy.posX, decoy.posY, decoy.posZ) + "]");
					decoy.allowDeath();
					decoy.setDead();
				}
			}

			System.out.println("[Turret-Howard] Aggro system DISABLED - all decoys removed");
		}
	}

	/**
	 * アグロシステムの状態を取得
	 */
	public boolean isAggroSystemEnabled() {
		return this.aggroSystemEnabled;
	}

	/**
	 * アグロシステムの更新（毎tick呼び出し）
	 *
	 * デコイエンティティのライフサイクル管理：
	 * - 1秒ごとにデコイエンティティの存在をチェック
	 * - デコイが存在しない場合は新規生成
	 * - デコイが存在する場合はライフタイムをリフレッシュ
	 * - デコイは常にタレット位置に固定される（EntityTurretDecoy.onUpdate()で処理）
	 */
	private void updateAggroSystem() {
		if (!aggroSystemEnabled || world.isRemote) {
			return;
		}

		// CONFIG CHECK: デコイエンティティがconfigで無効化されている場合は何もしない
		if (!com.hbm.config.WeaponConfig.turretDecoyEntityEnabled) {
			return;
		}

		aggroUpdateCounter++;

		// 1秒ごとにデコイエンティティをチェック・リフレッシュ
		if (aggroUpdateCounter >= AGGRO_UPDATE_INTERVAL) {
			aggroUpdateCounter = 0;

			// デコイが存在しない、または死んでいる場合は生成
			if (decoyEntity == null || decoyEntity.isDead) {
				decoyEntity = new EntityTurretDecoy(world, this.pos);
				boolean spawned = world.spawnEntity(decoyEntity);

				System.out.println("[Turret-Howard] ========================================");
				System.out.println("[Turret-Howard] DECOY ENTITY SPAWN ATTEMPT");
				System.out.println("[Turret-Howard] Turret position: " + this.pos);
				System.out.println("[Turret-Howard] Spawn successful: " + spawned);
				System.out.println("[Turret-Howard] Decoy entity ID: " + decoyEntity.getEntityId());
				System.out.println("[Turret-Howard] Decoy position: X=" + String.format("%.2f", decoyEntity.posX) +
				                   " Y=" + String.format("%.2f", decoyEntity.posY) +
				                   " Z=" + String.format("%.2f", decoyEntity.posZ));
				System.out.println("[Turret-Howard] Decoy size: " + decoyEntity.width + " x " + decoyEntity.height);
				System.out.println("[Turret-Howard] Decoy is alive: " + decoyEntity.isEntityAlive());
				System.out.println("[Turret-Howard] Decoy world: " + (decoyEntity.world != null));
				System.out.println("[Turret-Howard] FORCED AGGRO SYSTEM ACTIVE (50 block range)");
				System.out.println("[Turret-Howard] ========================================");
			} else {
				// デコイが存在する場合はライフタイムをリフレッシュ
				decoyEntity.refreshLifetime();

				// 10秒ごとにデコイの状態をログ出力
				if (aggroUpdateCounter % 200 == 0) {
					System.out.println("[Turret-Howard] Decoy status: Alive=" + decoyEntity.isEntityAlive() +
					                   " Pos=[" + String.format("%.1f", decoyEntity.posX) + ", " +
					                   String.format("%.1f", decoyEntity.posY) + ", " +
					                   String.format("%.1f", decoyEntity.posZ) + "]" +
					                   " Turret=[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]");
				}
			}
		}
	}
}
