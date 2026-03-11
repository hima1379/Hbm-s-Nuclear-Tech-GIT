package com.hbm.main.tileentity.machine.fusion;

import com.hbm.main.tileentity.TileEntityLoadedBase;
import com.hbm.uninos.DirPos;
import com.hbm.uninos.GenNode;
import com.hbm.uninos.UniNodespace;
import com.hbm.uninos.networkproviders.PlasmaNetworkProvider;

import api.hbm.energy.IEnergyGenerator;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Fusion MHDT - Magnetohydrodynamic Thermal Generator
 *
 * Direct energy conversion from fusion plasma to electricity using MHD principles.
 * NO MOVING PARTS - Uses magnetic fields to extract energy from high-velocity plasma.
 *
 * Real MHD Physics:
 * - Direct conversion: Thermal/Kinetic → Electrical (no intermediate steam cycle)
 * - High temperature operation: 2000-3000 K (vs turbine limit ~900 K)
 * - Theoretical efficiency: 60-65% (vs conventional turbine 35-40%)
 * - For fusion: Direct plasma energy extraction at 55-60% efficiency
 *
 * NO COOLANT REQUIRED - MHD advantage is operation at extreme temperatures
 *
 * @author Adapted for 1.12.2 with realistic MHD physics
 */
public class TileEntityFusionMHDT extends TileEntityLoadedBase implements IFusionPowerReceiver, IEnergyGenerator, ITickable {

	protected GenNode plasmaNode;
	public long power;  // Stored electrical power (HE)
	public static final long MAX_POWER = Long.MAX_VALUE;  // No practical limit (9.22 quintillion HE)

	// Real-time power generation tracking
	public double fusionPowerReceived;    // Fusion power from reactor (W)
	public long electricalPowerGenerated; // Electrical power generated this tick (HE/tick)

	// ===== 現実のMHD物理定数 =====
	// Based on real MHD generator physics with adaptations for game

	// プラズマ流体力学パラメータ
	public static final double PLASMA_VELOCITY = 1500.0;        // m/s (超音速流)
	public static final double MAGNETIC_FIELD_STRENGTH = 3.0;   // T (テスラ)
	public static final double CHANNEL_LENGTH = 10.0;           // m
	public static final double CHANNEL_CROSS_SECTION = 1.0;     // m²
	public static final double LOAD_FACTOR = 0.85;              // K (最適負荷係数)

	// 温度スケーリング（現実的なMHD動作）
	// 融合炉のコアプラズマ温度(~2000 MK)を排気プラズマ温度(~4 kK)にスケーリング
	// Realistic MHD generators operate on EXHAUST plasma, not fusion core
	public static final double MHD_EXHAUST_TEMP_SCALING = 1.0 / 500_000.0;  // 2000 MK → 4000 K

	// 温度範囲（現実のMHD動作範囲: 2000-10000 K）
	public static final double MHD_MIN_TEMP = 2000.0;           // 2 kK (最低動作温度)
	public static final double MHD_OPTIMAL_TEMP = 3000.0;       // 3 kK (現実のMHD最適温度)
	public static final double MHD_MAX_TEMP = 10_000.0;         // 10 kK (上限)

	// 入力パワー制限なし（fuel rateによって自動的にスケール）
	// No power cap - scales with Torus fuel rate

	// プラズマ伝導率パラメータ（現実的なMHD排気プラズマ用）
	// Realistic MHD: seeded plasma (cesium/potassium) at 2-10 kK
	public static final double CONDUCTIVITY_BASE = 10.0;        // S/m (2000 K, seeded plasma)
	public static final double CONDUCTIVITY_MAX = 200.0;        // S/m (10000 K, optimal seeding)

	// 効率パラメータ
	public static final double ISENTROPIC_EFFICIENCY = 0.55;    // 55% (理論最高値)
	public static final double ENTHALPY_EXTRACTION = 0.30;      // 30% (実験値)

	// 物理定数
	public static final double BOLTZMANN = 1.380649e-23;        // J/K
	public static final double ELECTRON_CHARGE = 1.602176634e-19; // C
	public static final double ELECTRON_MASS = 9.10938e-31;     // kg

	// Power conversion constant
	// From TileEntityFusionTorus: 1 MW = 50,000 HE/tick (at 20 tps)
	public static final double WATTS_TO_HE_PER_TICK = 50_000.0 / 1.0e6;  // 0.05 HE per Watt per tick

	// Rotor animation fields (visual only - MHD has no moving parts!)
	// The "rotor" represents plasma flow visualization, not mechanical rotation
	public float rotor;
	public float prevRotor;
	public float rotorSpeed;
	public static final float ROTOR_ACCELERATION = 0.25F;
	public static final float MAX_ROTOR_SPEED = 20F;  // Fast plasma flow

	// Plasma temperature tracking (for efficiency calculation)
	public double plasmaTemperature = 300.0;  // K (received from Torus)

	public TileEntityFusionMHDT() {
		super();
		System.out.println("[FusionMHDT] *** CONSTRUCTOR CALLED *** TileEntity created!");
	}

	@Override
	public void update() {
		if(!world.isRemote) {
			// ===== SERVER SIDE =====
			// DEBUG: Unconditional log to verify update() is being called
			System.out.println("[FusionMHDT] *** UPDATE CALLED *** tick=" + world.getTotalWorldTime() + ", pos=" + pos);

			// NOTE: リセット処理は削除（receiveFusionPower内で処理完了）

			// Create or get plasma network node
			// IMPORTANT: Must match TileEntityFusionTorus node position (×7, not ×4)
			// Torus creates nodes at distance 7 from core position (see TileEntityFusionTorus.createNode line 1029)
			if(plasmaNode == null || plasmaNode.expired) {
				EnumFacing dir = EnumFacing.byIndex(this.getBlockMetadata() - 10).getOpposite();
				int nodeX = pos.getX() + dir.getXOffset() * 7;  // Changed from 4 to 7 to match Torus
				int nodeY = pos.getY() + 2;
				int nodeZ = pos.getZ() + dir.getZOffset() * 7;  // Changed from 4 to 7 to match Torus

				plasmaNode = UniNodespace.getNode(world, nodeX, nodeY, nodeZ, PlasmaNetworkProvider.THE_PROVIDER);

				if(plasmaNode == null) {
					BlockPos nodePos = new BlockPos(nodeX, nodeY, nodeZ);
					BlockPos connectionPos = new BlockPos(
						nodeX + dir.getXOffset(),
						nodeY,
						nodeZ + dir.getZOffset()
					);

					plasmaNode = new GenNode(PlasmaNetworkProvider.THE_PROVIDER, nodePos)
						.setConnections(new DirPos(connectionPos, dir));

					UniNodespace.createNode(world, plasmaNode);
				}
			}

			// Register as plasma receiver
			if(plasmaNode != null && plasmaNode.hasValidNet()) {
				plasmaNode.net.addReceiver(this);
			}

			// Simple status logging (detailed physics logging happens in receiveFusionPower)
			if(world.getTotalWorldTime() % 20 == 0) {
				System.out.println("[FusionMHDT] Status: receiving=" + (fusionPowerReceived > 0) +
					", temp=" + String.format("%.0f MK", plasmaTemperature/1e6) +
					", stored=" + power + " HE, generating=" + electricalPowerGenerated + " HE/tick");
			}

			// Mark dirty if power changed significantly
			if(electricalPowerGenerated > 0) {
				this.markDirty();
			}

			// Synchronize to client for HUD display
			if(world.getTotalWorldTime() % 10 == 0) {
				world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
			}

			// Transmit stored power to connected machines (IEnergyGenerator)
			// Based on 1.7.10 getConPos() - power connection ports at specific positions
			if(power > 0) {
				long powerBefore = power;

				// Get facing direction and rotation (same logic as 1.7.10)
				EnumFacing dir = EnumFacing.byIndex(this.getBlockMetadata() - 10);
				com.hbm.lib.ForgeDirection forgeDir = com.hbm.lib.ForgeDirection.getOrientation(dir.getIndex());
				com.hbm.lib.ForgeDirection rot = forgeDir.getRotation(com.hbm.lib.ForgeDirection.UP);

				// Send power from the 3 connection port positions (matching makeExtra() positions)
				// Left side port: dir * 4 + rot * 4
				this.sendPower(world, pos.add(forgeDir.offsetX * 4 + rot.offsetX * 4, 0, forgeDir.offsetZ * 4 + rot.offsetZ * 4), rot);
				// Right side port: dir * 4 - rot * 4
				this.sendPower(world, pos.add(forgeDir.offsetX * 4 - rot.offsetX * 4, 0, forgeDir.offsetZ * 4 - rot.offsetZ * 4), rot.getOpposite());
				// Front port: dir * 8, y + 1
				this.sendPower(world, pos.add(forgeDir.offsetX * 8, 1, forgeDir.offsetZ * 8), forgeDir);

				long powerAfter = power;
				if(world.getTotalWorldTime() % 20 == 0) {
					// ALWAYS log result
					System.out.println("[FusionMHDT] *** sendPower RESULT: " + powerBefore + " -> " + powerAfter + " (sent: " + (powerBefore - powerAfter) + " HE)");
					if(powerBefore == powerAfter) {
						System.out.println("[FusionMHDT] *** WARNING: NO POWER WAS SENT! ***");
					}
				}
			} else {
				if(world.getTotalWorldTime() % 20 == 0) {
					System.out.println("[FusionMHDT] *** NO POWER (power=0) ***");
				}
			}

		} else {
			// ===== CLIENT SIDE =====
			// Rotor animation (represents plasma flow visualization, not mechanical parts)
			// Speed is based on MHD physics: plasma flow rate and electrical power output

			if(this.fusionPowerReceived > 0 && this.electricalPowerGenerated > 0) {
				// Calculate target speed based on plasma temperature (proxy for plasma velocity)
				// Optimal: 150 MK = max speed (20)
				// Minimum: 1 MK = slow speed (5)
				double tempRatio = Math.min(this.plasmaTemperature / MHD_OPTIMAL_TEMP, 1.0);
				float targetSpeed = (float)(5.0F + 15.0F * tempRatio);  // 5-20 range

				// Smoothly accelerate to target speed
				if(this.rotorSpeed < targetSpeed) {
					this.rotorSpeed += ROTOR_ACCELERATION;
				} else if(this.rotorSpeed > targetSpeed) {
					this.rotorSpeed -= ROTOR_ACCELERATION * 0.5F;
				}
			} else {
				// Decelerate when not generating power
				this.rotorSpeed -= ROTOR_ACCELERATION * 0.5F;
			}

			this.rotorSpeed = net.minecraft.util.math.MathHelper.clamp(this.rotorSpeed, 0F, MAX_ROTOR_SPEED);

			this.prevRotor = this.rotor;
			this.rotor += this.rotorSpeed;

			if(this.rotor >= 360F) {
				this.rotor -= 360F;
				this.prevRotor -= 360F;
			}
		}
	}

	/**
	 * プラズマ電気伝導率を温度から計算（現実的なMHD排気プラズマ用）
	 *
	 * 現実的なMHD動作：排気プラズマ（2-10 kK）+ シード材（セシウム/カリウム）
	 * - シード材添加により低温でも高伝導率を実現
	 * - 温度が高いほど伝導率が向上
	 *
	 * 物理的根拠：
	 * - Spitzer伝導率：σ ∝ T^(3/2)
	 * - 2000 K (seeded): σ ~ 10 S/m
	 * - 3000 K (optimal): σ ~ 50 S/m
	 * - 10000 K (max): σ ~ 200 S/m
	 *
	 * @param temperature 排気プラズマ温度 (K)
	 * @return 電気伝導率 (S/m)
	 */
	private double calculatePlasmaConductivity(double temperature) {
		if(temperature < MHD_MIN_TEMP) {
			// 2000 K以下：シード不完全、動作不可
			double ratio = temperature / MHD_MIN_TEMP;
			return CONDUCTIVITY_BASE * ratio * ratio;  // 二次関数的に低下
		}

		if(temperature >= MHD_MAX_TEMP) {
			// 10 kK以上：飽和（シード限界）
			return CONDUCTIVITY_MAX;
		}

		// Spitzer伝導率：σ ∝ T^(3/2)
		// 2 kK → 10 S/m, 10 kK → 200 S/m にスケーリング
		double tempRatio = temperature / MHD_MIN_TEMP;
		double conductivity = CONDUCTIVITY_BASE * Math.pow(tempRatio, 1.5);

		return Math.min(conductivity, CONDUCTIVITY_MAX);
	}

	/**
	 * ホールパラメータ（β = ωτ）を計算（融合プラズマ対応）
	 *
	 * 物理的意味：
	 * - β < 1: 衝突支配、理想的なMHD動作
	 * - β > 1: 磁場支配、効率低下開始
	 * - β > 5: Velikhov不安定性のリスク
	 *
	 * 融合プラズマMHDの課題：
	 * - 超高温（GK規模）では理論的βが極端に大きくなる
	 * - 工学的解決策：電極分割、斜め磁場、短チャンネル設計
	 * - これにより実効的なβを安定範囲（β < 2）に制限
	 *
	 * @param magneticField 磁場強度 (T)
	 * @param conductivity プラズマ伝導率 (S/m)
	 * @param temperature プラズマ温度 (K)
	 * @return 実効ホールパラメータ (無次元、工学的制限適用後)
	 */
	private double calculateHallParameter(double magneticField, double conductivity, double temperature) {
		// サイクロトロン周波数：ω = eB/m_e
		double cyclotronFreq = (ELECTRON_CHARGE * magneticField) / ELECTRON_MASS;

		// Coulomb衝突時間（融合プラズマ用）
		// τ_ei ≈ 3.44×10⁵ × T_e^(3/2) / (n_e × ln(Λ)) [秒]
		// MHDチャンネル内プラズマ密度: n_e ≈ 5×10²³ m⁻³ (融合炉より高密度)
		// Coulomb対数: ln(Λ) ≈ 15 (高温プラズマ)

		double tempEV = temperature * BOLTZMANN / ELECTRON_CHARGE;  // K → eV変換
		double plasmaDensity = 5.0e23;  // m⁻³ (MHDチャンネル、融合炉の約5000倍密度)
		double coulombLog = 15.0;       // 無次元

		// 電子-イオン衝突時間 [秒]
		double collisionTime = (3.44e5 * Math.pow(tempEV, 1.5)) / (plasmaDensity * coulombLog);

		// 理論的ホールパラメータ
		double beta_theoretical = cyclotronFreq * collisionTime;

		// 工学的制限：電極設計、チャンネル形状最適化によりβを制限
		// 現実のMHD発電機：セグメント化電極、斜め磁場配置で β_effective < 2 を実現
		final double BETA_MAX_ENGINEERING = 2.0;  // 工学的上限

		double beta_effective = Math.min(beta_theoretical, BETA_MAX_ENGINEERING);

		return beta_effective;
	}

	/**
	 * Calculate MHD efficiency based on EXHAUST plasma temperature.
	 *
	 * 現実的なMHD動作：
	 * - 排気プラズマ温度（2000-3000 K）で動作
	 * - 温度が高いほど効率が向上
	 * - 最適温度（3000 K）で理論最高効率55%
	 *
	 * Temperature curve:
	 * - Below 2000 K: Quadratic drop-off (ionization incomplete)
	 * - 2000-3000 K: Linear increase 40% → 55%
	 * - Above 3000 K: Maintain 55% (isentropic limit)
	 *
	 * @param exhaustTemp MHD排気プラズマ温度 (K)
	 * @return MHD変換効率 (0.0-1.0)
	 */
	private double calculateMHDEfficiency(double exhaustTemp) {
		if(exhaustTemp < MHD_MIN_TEMP) {
			// 2000 K以下：イオン化不完全で急激に効率低下
			double ratio = exhaustTemp / MHD_MIN_TEMP;
			return ISENTROPIC_EFFICIENCY * ratio * ratio;  // 二次関数的
		}

		if(exhaustTemp >= MHD_OPTIMAL_TEMP) {
			// 3000 K以上：理論最高効率55%（等エントロピー限界）
			return ISENTROPIC_EFFICIENCY;
		}

		// 2000 K〜3000 Kの範囲：線形補間
		// 下限効率40% → 最適効率55%
		double minEfficiency = 0.40;
		double tempRange = MHD_OPTIMAL_TEMP - MHD_MIN_TEMP;
		double tempDelta = exhaustTemp - MHD_MIN_TEMP;
		double efficiencyRange = ISENTROPIC_EFFICIENCY - minEfficiency;

		return minEfficiency + (efficiencyRange * tempDelta / tempRange);
	}

	@Override
	public boolean receivesFusionPower() {
		return true;  // Always ready to receive fusion power
	}

	@Override
	public void receiveFusionPower(double thermalPowerWatts, double plasmaTemperatureK, double neutronFlux) {
		// DEBUG: Unconditional log to verify method is being called
		System.out.println("[FusionMHDT] *** receiveFusionPower CALLED *** power=" + thermalPowerWatts + " W, temp=" + plasmaTemperatureK + " K");
		System.out.println("[FusionMHDT] World null check: world=" + (world == null ? "NULL!!!" : "OK") + ", isRemote=" + (world != null ? world.isRemote : "N/A"));

		// ===== 現実的なMHDアプローチ: 排気プラズマへの変換 =====
		// MHD発電機は融合炉の「排気プラズマ」で動作し、コアプラズマではない
		// Fusion core: ~2000 MK → MHD exhaust: ~4 kK (500,000x cooler)
		double mhdExhaustTemp = plasmaTemperatureK * MHD_EXHAUST_TEMP_SCALING;
		mhdExhaustTemp = Math.max(MHD_MIN_TEMP, Math.min(mhdExhaustTemp, MHD_MAX_TEMP));
		this.plasmaTemperature = mhdExhaustTemp;

		// No power cap - scales directly with Torus fuel rate
		// MHDTの処理能力は燃料レートに比例してスケール

		// ===== ステップ1: プラズマ伝導率計算 =====
		// 高温プラズマ：σ ∝ T^(3/2) (Spitzer伝導率)
		double conductivity = calculatePlasmaConductivity(mhdExhaustTemp);

		// ===== ステップ2: ホールパラメータ計算 =====
		// β = ωτ (磁場と衝突の競合)
		double hallParameter = calculateHallParameter(MAGNETIC_FIELD_STRENGTH, conductivity, mhdExhaustTemp);

		// ===== ステップ3: MHD出力密度計算 =====
		// P_density = σ × v × B² × K(1-K) [W/m³]
		double powerDensity = conductivity * PLASMA_VELOCITY *
		                     Math.pow(MAGNETIC_FIELD_STRENGTH, 2) *
		                     LOAD_FACTOR * (1.0 - LOAD_FACTOR);

		// チャンネル体積での総出力
		double channelVolume = CHANNEL_LENGTH * CHANNEL_CROSS_SECTION;
		double rawElectricalPower = powerDensity * channelVolume;

		// ===== ステップ4: ホールパラメータによる効率補正 =====
		double hallEfficiency = 1.0;
		if(hallParameter > 5.0) {
			// Velikhov不安定性による大幅な効率低下
			hallEfficiency = 0.3;
		} else if(hallParameter > 1.0) {
			// 磁場支配による効率低下
			hallEfficiency = 1.0 - 0.15 * (hallParameter - 1.0);
		}

		// ===== ステップ5: 温度依存効率計算 =====
		double temperatureEfficiency = calculateMHDEfficiency(mhdExhaustTemp);

		// ===== ステップ6: エンタルピー抽出率 =====
		// 総合変換効率 = 温度効率 × エンタルピー抽出率 × Hall補正
		double overallEfficiency = temperatureEfficiency * ENTHALPY_EXTRACTION * hallEfficiency;

		// ===== ステップ7: 電力出力計算 =====
		// 2つの計算方法のうち、小さい方を採用（物理的制約）
		double thermalConversion = thermalPowerWatts * overallEfficiency;
		double mhdPhysics = rawElectricalPower * 1.0e9;  // スケーリング調整

		double electricalPowerWatts = Math.min(thermalConversion, mhdPhysics);

		// ===== ステップ8: HE/tickに変換して蓄電 =====
		long hePerTick = (long)(electricalPowerWatts * WATTS_TO_HE_PER_TICK);

		this.fusionPowerReceived = thermalPowerWatts;  // Direct from Torus (scales with fuel rate)
		this.electricalPowerGenerated = hePerTick;

		// Add generated power to storage (with overflow protection)
		// Long.MAX_VALUE = 9,223,372,036,854,775,807 (922京)
		if(this.power < MAX_POWER - hePerTick) {
			this.power += hePerTick;
		} else {
			this.power = MAX_POWER;  // Cap at Long.MAX_VALUE to prevent overflow
		}

		// ===== デバッグログ（ALWAYS表示 - 診断用） =====
		// NOTE: world nullチェックとログ頻度制限を削除（完全なデバッグ用）
		System.out.println("[FusionMHDT] ===== MHD PHYSICS (Realistic Exhaust) =====");
		System.out.println("[FusionMHDT] Fusion core temp: " + String.format("%.2e K (%.0f MK)",
			plasmaTemperatureK, plasmaTemperatureK/1e6));
		System.out.println("[FusionMHDT] MHD exhaust temp: " + String.format("%.2e K (%.0f K)",
			mhdExhaustTemp, mhdExhaustTemp));
		System.out.println("[FusionMHDT] Input thermal power: " + String.format("%.2e W (%.1f GW)",
			thermalPowerWatts, thermalPowerWatts/1e9) + " (scales with fuel rate)");
		System.out.println("[FusionMHDT]   Conductivity (Spitzer): " + String.format("%.2e S/m", conductivity));
		System.out.println("[FusionMHDT]   Velocity: " + PLASMA_VELOCITY + " m/s, B-field: " + MAGNETIC_FIELD_STRENGTH + " T");

		// Hall parameter status
		String betaStatus;
		if(Math.abs(hallParameter - 2.0) < 0.01) {
			betaStatus = " (engineering-limited for stability)";
		} else if(hallParameter > 1.0) {
			betaStatus = String.format(" (%.0f%% efficiency penalty)", (1.0 - hallEfficiency) * 100);
		} else {
			betaStatus = " (optimal)";
		}
		System.out.println("[FusionMHDT]   Hall parameter β: " + String.format("%.3f", hallParameter) + betaStatus);

		System.out.println("[FusionMHDT] Efficiency breakdown:");
		System.out.println("[FusionMHDT]   Temperature: " + String.format("%.1f%%", temperatureEfficiency * 100));
		System.out.println("[FusionMHDT]   Enthalpy extraction: " + String.format("%.1f%%", ENTHALPY_EXTRACTION * 100));
		System.out.println("[FusionMHDT]   Hall correction: " + String.format("%.1f%%", hallEfficiency * 100));
		System.out.println("[FusionMHDT]   → Overall: " + String.format("%.1f%%", overallEfficiency * 100));

		System.out.println("[FusionMHDT] Output: " + String.format("%,d", hePerTick) + " HE/tick (" +
			String.format("%.1f GW", electricalPowerWatts/1e9) + ")");
		System.out.println("[FusionMHDT] Stored: " + String.format("%,d", power) + " / " + String.format("%,d", MAX_POWER) + " HE");
		System.out.println("[FusionMHDT] =========================================");
	}

	@Override
	public void invalidate() {
		super.invalidate();
		if(!world.isRemote) {
			if(this.plasmaNode != null) {
				UniNodespace.destroyNode(world, plasmaNode);
			}
		}
	}

	// ===== IEnergyGenerator Implementation =====

	@Override
	public long getPower() {
		return power;
	}

	@Override
	public void setPower(long power) {
		this.power = power;
	}

	@Override
	public long getMaxPower() {
		return MAX_POWER;
	}

	// ===== NBT Serialization =====

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);
		nbt.setLong("power", power);
		nbt.setDouble("fusionPowerReceived", fusionPowerReceived);
		nbt.setLong("electricalPowerGenerated", electricalPowerGenerated);
		nbt.setDouble("plasmaTemperature", plasmaTemperature);
		return nbt;
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);
		this.power = nbt.getLong("power");
		if(nbt.hasKey("fusionPowerReceived")) {
			this.fusionPowerReceived = nbt.getDouble("fusionPowerReceived");
		}
		if(nbt.hasKey("electricalPowerGenerated")) {
			this.electricalPowerGenerated = nbt.getLong("electricalPowerGenerated");
		}
		if(nbt.hasKey("plasmaTemperature")) {
			this.plasmaTemperature = nbt.getDouble("plasmaTemperature");
		}
	}

	// ===== Client Synchronization =====

	@Override
	public NBTTagCompound getUpdateTag() {
		return this.writeToNBT(new NBTTagCompound());
	}

	@Override
	public net.minecraft.network.play.server.SPacketUpdateTileEntity getUpdatePacket() {
		NBTTagCompound nbt = new NBTTagCompound();
		nbt.setLong("power", power);
		nbt.setDouble("fusionPowerReceived", fusionPowerReceived);
		nbt.setLong("electricalPowerGenerated", electricalPowerGenerated);
		nbt.setDouble("plasmaTemperature", plasmaTemperature);
		return new net.minecraft.network.play.server.SPacketUpdateTileEntity(pos, 0, nbt);
	}

	@Override
	public void onDataPacket(net.minecraft.network.NetworkManager net, net.minecraft.network.play.server.SPacketUpdateTileEntity pkt) {
		NBTTagCompound nbt = pkt.getNbtCompound();
		this.power = nbt.getLong("power");
		this.fusionPowerReceived = nbt.getDouble("fusionPowerReceived");
		this.electricalPowerGenerated = nbt.getLong("electricalPowerGenerated");
		this.plasmaTemperature = nbt.getDouble("plasmaTemperature");
	}

	// ===== Rendering =====

	private AxisAlignedBB bb = null;

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		if(bb == null) {
			bb = new AxisAlignedBB(
				pos.getX() - 3,
				pos.getY(),
				pos.getZ() - 3,
				pos.getX() + 4,
				pos.getY() + 4,
				pos.getZ() + 4
			);
		}
		return bb;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public double getMaxRenderDistanceSquared() {
		return 65536.0D;
	}
}
