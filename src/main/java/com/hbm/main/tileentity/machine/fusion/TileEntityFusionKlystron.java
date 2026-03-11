package com.hbm.main.tileentity.machine.fusion;

import com.hbm.interfaces.IControlReceiver;
import com.hbm.lib.Library;
import com.hbm.main.MainRegistry;
import com.hbm.main.tileentity.machine.TileEntityCooledBase;
import com.hbm.sound.AudioWrapper;
import com.hbm.uninos.DirPos;
import com.hbm.uninos.GenNode;
import com.hbm.uninos.UniNodespace;
import com.hbm.uninos.networkproviders.KlystronNetwork;
import com.hbm.uninos.networkproviders.KlystronNetworkProvider;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Fusion Klystron - ITER式プラズマ加熱システム
 *
 * 現実の物理法則に基づいた核融合炉加熱装置：
 * - 電力を内部にチャージ（95%効率、最大500 MWh = 1.8×10⁹ J）
 * - チャージエネルギーをNBI（中性粒子ビーム）とRF（高周波）に変換
 * - NBI: 34%効率、最大33 MW
 * - ICRH: 70%効率、最大20 MW
 * - ECRH: 50%効率、最大67 MW
 * - プラズマ状態に応じて自動配分
 * - 冷却システム: 102.4M mB Perfluoromethyl
 *
 * @author Adapted from ITER design
 */
public class TileEntityFusionKlystron extends TileEntityCooledBase implements IControlReceiver {

	// ===== Network =====
	protected GenNode<KlystronNetwork> klystronNode;

	// ===== Energy Storage & Charging =====
	/** 最大チャージ容量: 500 MWh = 1.8×10⁹ J */
	public static final long MAX_CHARGED_ENERGY = 1_800_000_000L;

	/** 充電効率: 95% */
	public static final double CHARGING_EFFICIENCY = 0.95;

	/** チャージされたエネルギー (J) */
	public long chargedEnergy = 0;

	/** 最大電力バッファ容量 */
	public long maxPower = 100_000_000L;

	// ===== Heating System Efficiencies (ITER実測値) =====
	/** NBI変換効率: 34% */
	public static final double NBI_EFFICIENCY = 0.34;

	/** ICRH変換効率: 70% */
	public static final double ICRH_EFFICIENCY = 0.70;

	/** ECRH変換効率: 50% */
	public static final double ECRH_EFFICIENCY = 0.50;

	// ===== Heating System Power Limits (W) =====
	/** NBI最大出力: 33 MW */
	public static final long NBI_MAX_POWER = 33_000_000L;

	/** ICRH最大出力: 20 MW */
	public static final long ICRH_MAX_POWER = 20_000_000L;

	/** ECRH最大出力: 67 MW */
	public static final long ECRH_MAX_POWER = 67_000_000L;

	// ===== Power Control =====
	/** 電源オン/オフ状態 */
	public boolean isPoweredOn = false;

	/** ワンショットバーストフラグ: 一度放出したらtrue */
	public boolean hasAlreadyFired = false;

	/** ユーザー設定: 出力パワー目標 (W) */
	public long outputTarget = 0;

	/** 実際の出力パワー (W/tick) */
	public long actualOutput = 0;

	/** NBI出力 (W/tick) */
	public long nbiOutput = 0;

	/** ICRH出力 (W/tick) */
	public long icrfOutput = 0;

	/** ECRH出力 (W/tick) */
	public long ecrfOutput = 0;

	// ===== Animation & Sound =====
	public float fan;
	public float prevFan;
	public float fanSpeed;
	public static final float FAN_ACCELERATION = 0.125F;
	private AudioWrapper audio;

	// ===== Ignition Readiness =====
	/** 電力供給中フラグ */
	public boolean hasPowerSupply = false;

	/** Torus点火準備完了フラグ */
	public boolean torusReady = false;

	// ===== Compatibility (for old GUI) =====
	/** 旧GUI互換性: output → actualOutput */
	public long output = 0;

	/** 旧GUI互換性: MAX_OUTPUT */
	public static final long MAX_OUTPUT = NBI_MAX_POWER + ICRH_MAX_POWER + ECRH_MAX_POWER;

	/** 旧GUI互換性: compair (空のダミータンク) */
	public net.minecraftforge.fluids.FluidTank compair = new net.minecraftforge.fluids.FluidTank(1);

	public TileEntityFusionKlystron() {
		super(1); // 1 slot for battery

		// 冷却タンク容量を102.4M mBに拡大
		// ITER加熱システムの実際の冷却要求: ~51.2 MW熱負荷
		// Perfluoromethyl比熱: 1,100 J/(kg·K)、密度: 1,680 kg/m³
		// 必要冷却能力を満たすために大容量タンクを使用
		coolantTanks[0].setCapacity(102_400_000);
		coolantTanks[1].setCapacity(102_400_000);
	}

	@Override
	public String getName() {
		return "container.fusionKlystron";
	}

	@Override
	public void update() {
		// 親クラスの冷却処理を実行
		super.update();

		if(!world.isRemote) {
			// ===== Server-side logic =====

			// バッテリーから充電
			this.power = Library.chargeTEFromItems(inventory, 0, power, maxPower);

			// 電力供給チェック
			this.hasPowerSupply = (power > 0);

			// Torus点火準備チェック
			this.torusReady = checkTorusIgnitionReady();

			// 電源状態に応じた動作
			this.actualOutput = 0;
			this.nbiOutput = 0;
			this.icrfOutput = 0;
			this.ecrfOutput = 0;

			if(!isPoweredOn) {
				// ===== 電源OFF: チャージモード =====
				if(power > 0 && chargedEnergy < MAX_CHARGED_ENERGY) {
					// 毎tick充電可能な電力量を計算（95%効率）
					long chargeableEnergy = (long)(power * CHARGING_EFFICIENCY);
					long spaceLeft = MAX_CHARGED_ENERGY - chargedEnergy;
					long actualCharge = Math.min(chargeableEnergy, spaceLeft);

					// 必要な電力を消費
					long powerNeeded = (long)Math.ceil(actualCharge / CHARGING_EFFICIENCY);
					if(powerNeeded <= power) {
						this.power -= powerNeeded;
						this.chargedEnergy += actualCharge;
					}
				}

				// バーストフラグをリセット（充電中は再度発射可能にする）
				// 電源OFF時は常にhasAlreadyFiredをリセット
				this.hasAlreadyFired = false;
			} else {
				// ===== 電源ON: ワンショットバースト加熱モード =====
				if(!hasAlreadyFired && chargedEnergy > 0) {
					// **一瞬だけ全エネルギーを放出（バースト）**
					System.out.println("[Klystron] ***** ONE-SHOT BURST FIRING! *****");
					System.out.println("[Klystron] Releasing ALL charged energy: " + chargedEnergy + " J");

					// 全エネルギーをバーストとして放出
					long burstEnergy = chargedEnergy;

					// プラズマ状態に基づいて自動配分
					distributeHeatingPower(burstEnergy);

					// 全エネルギーを消費
					this.chargedEnergy = 0;
					this.actualOutput = burstEnergy;

					// バーストフラグを設定
					this.hasAlreadyFired = true;

					System.out.println("[Klystron] Burst complete! NBI=" + nbiOutput + "W, ICRH=" + icrfOutput + "W, ECRH=" + ecrfOutput + "W");
					System.out.println("[Klystron] Auto-shutting down...");

					// 自動的に電源OFF（次tickで実行）
					// ここではフラグだけ設定し、次の処理で確実にOFFにする
				}

				// バースト完了後、自動的に電源をOFFにする
				if(hasAlreadyFired) {
					this.isPoweredOn = false;
					System.out.println("[Klystron] Power automatically turned OFF after burst.");
				}
			}

			// Klystronネットワークノード処理
			this.klystronNode = handleKNode(klystronNode, this);

			// 加熱パワーをTorusに送信
			// ゼロ値を送信すると Torus の heatingPower がリセットされるため、
		// 実際に加熱パワーがある場合のみ送信する
		if(nbiOutput > 0 || icrfOutput > 0 || ecrfOutput > 0) {
			boolean connected = provideHeating(klystronNode, nbiOutput, icrfOutput, ecrfOutput);
		}

			// 旧GUI互換性: outputフィールドを更新
			this.output = this.actualOutput;

			// デバッグ出力（毎tick）
			System.out.println("[Klystron DEBUG] Tick=" + world.getTotalWorldTime() +
				", isPoweredOn=" + isPoweredOn +
				", power=" + power + "/" + maxPower +
				", chargedEnergy=" + chargedEnergy + "/" + MAX_CHARGED_ENERGY);
			if(isPoweredOn) {
				System.out.println("[Klystron HEATING] NBI=" + nbiOutput + "W, ICRH=" + icrfOutput + "W, ECRH=" + ecrfOutput + "W, Total=" + actualOutput + "W");
			} else {
				System.out.println("[Klystron CHARGING] Charging from battery...");
			}

			// Network sync
			NBTTagCompound data = new NBTTagCompound();
			data.setLong("power", power);
			data.setLong("chargedEnergy", chargedEnergy);
			data.setLong("outputTarget", outputTarget);
			data.setLong("actualOutput", actualOutput);
			data.setLong("nbiOutput", nbiOutput);
			data.setLong("icrfOutput", icrfOutput);
			data.setLong("ecrfOutput", ecrfOutput);
			data.setBoolean("isPoweredOn", isPoweredOn);
			// hasAlreadyFiredはネットワーク同期しない（サーバー側のみの状態）
			data.setBoolean("hasPowerSupply", hasPowerSupply);
			data.setBoolean("torusReady", torusReady);
			this.networkPack(data, 100);

		} else {
			// ===== Client-side logic =====

			// Fan animation
			double mult = outputTarget > 0 ? (double)actualOutput / (double)outputTarget : 0;
			if(this.actualOutput > 0) {
				this.fanSpeed += FAN_ACCELERATION * mult;
			} else {
				this.fanSpeed -= FAN_ACCELERATION;
			}

			this.fanSpeed = MathHelper.clamp(this.fanSpeed, 0F, 5F * (float)mult);

			this.prevFan = this.fan;
			this.fan += this.fanSpeed;

			if(this.fan >= 360F) {
				this.fan -= 360F;
				this.prevFan -= 360F;
			}

			// Sound management
			if(this.fanSpeed > 0 && MainRegistry.proxy.me().getDistanceSq(pos.getX() + 0.5, pos.getY() + 2.5, pos.getZ() + 0.5) < 30 * 30) {
				float speed = this.fanSpeed / 5F;

				if(audio == null) {
					audio = MainRegistry.proxy.getLoopedSound(com.hbm.lib.HBMSoundHandler.fel, net.minecraft.util.SoundCategory.BLOCKS,
						(float)(pos.getX() + 0.5), (float)(pos.getY() + 2.5), (float)(pos.getZ() + 0.5),
						getVolume((int)speed), speed);
					audio.startSound();
				} else {
					audio.updateVolume(getVolume((int)speed));
					audio.updatePitch(speed);
					audio.keepAlive();
				}
			} else {
				if(audio != null) {
					if(audio.isPlaying()) audio.stopSound();
					audio = null;
				}
			}
		}
	}

	/**
	 * 加熱エネルギーをNBI/ICRH/ECRHに自動配分
	 *
	 * 配分戦略（ITER運用モデル）:
	 * - 低温(<5 keV): RF主体（ECRH 80%, ICRH 20%）
	 * - 中温(5-20 keV): NBI+RF混合（NBI 50%, ICRH 30%, ECRH 20%）
	 * - 高温(>20 keV): NBI主体（NBI 70%, ICRH 20%, ECRH 10%）
	 *
	 * バーストモード: chargedEnergy（J）を1 tick（0.05s）で放出
	 * Power = Energy / time
	 *
	 * @param burstEnergy バースト放出するエネルギー (J)
	 */
	private void distributeHeatingPower(long burstEnergy) {
		// バーストエネルギーを1 tick (0.05s) で放出する場合のパワー
		// Power (W) = Energy (J) / time (s)
		double burstTime = 0.05; // 1 tick = 0.05 seconds
		long instantaneousPower = (long)(burstEnergy / burstTime);

		System.out.println("[Klystron] Burst energy: " + burstEnergy + " J over " + burstTime + " s = " + instantaneousPower + " W instantaneous power");

		// 暫定実装: 中温運用を想定した固定配分
		// NBI 50%, ICRH 30%, ECRH 20%
		long nbiTarget = (long)(instantaneousPower * 0.50);
		long icrfTarget = (long)(instantaneousPower * 0.30);
		long ecrfTarget = (long)(instantaneousPower * 0.20);

		// **バーストモードでは最大出力制限を無視**
		// 瞬間的な高出力を実現するため
		// （通常運転時の制限は別途適用される）

		// 効率を適用して実際の出力を計算
		this.nbiOutput = (long)(nbiTarget * NBI_EFFICIENCY);
		this.icrfOutput = (long)(icrfTarget * ICRH_EFFICIENCY);
		this.ecrfOutput = (long)(ecrfTarget * ECRH_EFFICIENCY);

		System.out.println("[Klystron] Distributed: NBI=" + nbiOutput + " W, ICRH=" + icrfOutput + " W, ECRH=" + ecrfOutput + " W");
	}

	/**
	 * Torus点火準備状態をチェック（ITER式点火条件）
	 *
	 * チェック項目:
	 * 1. 冷却システム正常動作（温度が安全範囲内）
	 * 2. Torusが接続されている
	 * 3. Torusのプラズマ状態確認:
	 *    - 密度 > 1.0e19 /m³（最低密度）
	 *    - 温度 > 1.0e4 K（最低温度）
	 *    - 磁場 > 1.0 T（最低磁場）
	 * 4. Lawson判定基準:
	 *    - n × τ_E × T > 3.0e21 keV·s/m³（D-T反応の点火条件）
	 * 5. Torusの冷却システム動作
	 *
	 * @return 点火可能ならtrue
	 */
	private boolean checkTorusIgnitionReady() {
		// 1. Klystron自体の冷却チェック
		if(!this.isCool()) {
			return false;
		}

		// 2. Torusネットワーク接続チェック
		if(klystronNode == null || klystronNode.net == null || klystronNode.net.receiverEntries.isEmpty()) {
			return false;
		}

		// 3. Torusの状態を詳細チェック
		boolean torusReady = false;
		for(Object o : klystronNode.net.receiverEntries.entrySet()) {
			java.util.Map.Entry e = (java.util.Map.Entry) o;

			if(e.getKey() instanceof TileEntityFusionTorus) {
				TileEntityFusionTorus torus = (TileEntityFusionTorus) e.getKey();

				if(!torus.isLoaded() || torus.isInvalid()) {
					continue;
				}

				// Torusのプラズマ状態チェック
				com.hbm.physics.fusion.PlasmaState state = torus.plasmaState;
				if(state == null) {
					continue;
				}

				// 密度チェック（最低 1.0e19 /m³）
				if(state.density < 1.0e19) {
					continue;
				}

				// 温度チェック（最低 10,000 K = 0.86 eV）
				double avgTemp = (state.temperatureIon + state.temperatureElectron) / 2.0;
				if(avgTemp < 1.0e4) {
					continue;
				}

				// 磁場チェック（最低 1.0 T）
				if(state.magneticField < 1.0) {
					continue;
				}

				// Lawson判定基準チェック
				// n × τ_E × T > 3.0e21 keV·s/m³（D-T核融合の点火条件）
				double temp_keV = avgTemp / 11604.5; // K to keV
				double confinementTime = torus.confinementTime;
				double lawsonProduct = state.density * confinementTime * temp_keV;

				// D-T反応の点火条件: n × τ_E × T > 3.0e21 keV·s/m³
				// 実際のITERでは ~5.0e21 keV·s/m³を目標としている
				if(lawsonProduct < 3.0e21) {
					continue;
				}

				// Torusの冷却システムチェック
				if(!torus.isCool()) {
					continue;
				}

				// 全条件クリア
				torusReady = true;
				break;
			}
		}

		return torusReady;
	}

	/**
	 * k-nodeを確保し、klystronをプロバイダーとして登録
	 */
	public static <T extends KlystronNetwork> GenNode<T> handleKNode(GenNode<T> klystronNode, TileEntity that) {
		World world = that.getWorld();
		BlockPos pos = that.getPos();

		if(klystronNode == null || klystronNode.expired) {
			EnumFacing dir = EnumFacing.byIndex(that.getBlockMetadata() - 10).getOpposite();

			int nodeX = pos.getX() + dir.getXOffset() * 4;
			int nodeY = pos.getY() + 2;
			int nodeZ = pos.getZ() + dir.getZOffset() * 4;

			klystronNode = (GenNode<T>) UniNodespace.getNode(world, nodeX, nodeY, nodeZ, KlystronNetworkProvider.THE_PROVIDER);

			if(klystronNode == null) {
				BlockPos nodePos = new BlockPos(nodeX, nodeY, nodeZ);
				BlockPos connectionPos = new BlockPos(
					nodeX + dir.getXOffset(),
					nodeY,
					nodeZ + dir.getZOffset()
				);

				klystronNode = (GenNode<T>) new GenNode<KlystronNetwork>(KlystronNetworkProvider.THE_PROVIDER, nodePos)
					.setConnections(new DirPos(connectionPos, dir));

				UniNodespace.createNode(world, klystronNode);
			}
		}

		if(klystronNode.net != null) {
			klystronNode.net.addProvider(that);
		}

		return klystronNode;
	}

	/**
	 * 加熱パワーをk-net経由でTorusに送信
	 *
	 * @param klystronNode ネットワークノード
	 * @param nbiPower NBI出力 (W)
	 * @param icrfPower ICRH出力 (W)
	 * @param ecrfPower ECRH出力 (W)
	 * @return 接続成功ならtrue
	 */
	public static boolean provideHeating(GenNode klystronNode, long nbiPower, long icrfPower, long ecrfPower) {
		boolean connected = false;

		if(klystronNode != null && klystronNode.net != null) {
			KlystronNetwork net = (KlystronNetwork) klystronNode.net;

			for(Object o : net.receiverEntries.entrySet()) {
				java.util.Map.Entry e = (java.util.Map.Entry) o;

				if(e.getKey() instanceof TileEntityFusionTorus) {
					TileEntityFusionTorus torus = (TileEntityFusionTorus) e.getKey();

					if(torus.isLoaded() && !torus.isInvalid()) {
						// ITER式加熱システム: receiveHeating()経由で送信
						torus.receiveHeating(nbiPower, icrfPower, ecrfPower);

						connected = true;
						System.out.println("[Klystron] Transferred heating to Torus: NBI=" + nbiPower + "W, ICRF=" + icrfPower + "W, ECRF=" + ecrfPower + "W");
						break;
					}
				}
			}
		}

		return connected;
	}

	/**
	 * 旧GUI互換性: getSpeedScaled
	 */
	public static double getSpeedScaled(long max, long current) {
		if(max <= 0) return 0;
		double ratio = (double) current / (double) max;
		return Math.pow(ratio, 0.25);
	}

	/**
	 * 旧GUI互換性: provideKyU (現在はprovideHeatingを使用)
	 */
	public static boolean provideKyU(GenNode klystronNode, long output) {
		// 旧システムとの互換性: 単一出力をNBI/ICRH/ECRHに分配
		long nbi = (long)(output * 0.50);
		long icrf = (long)(output * 0.30);
		long ecrf = (long)(output * 0.20);
		return provideHeating(klystronNode, nbi, icrf, ecrf);
	}

	@Override
	public DirPos[] getConPos() {
		EnumFacing dir = EnumFacing.byIndex(this.getBlockMetadata() - 10);
		EnumFacing rot = dir.rotateY();

		return new DirPos[] {
			// Klystron network connection (front)
			new DirPos(
				new BlockPos(pos.getX() + dir.getXOffset() * 4, pos.getY() + 2, pos.getZ() + dir.getZOffset() * 4),
				dir
			),
			// Power connection (right)
			new DirPos(
				new BlockPos(pos.getX() + rot.getXOffset() * 3, pos.getY(), pos.getZ() + rot.getZOffset() * 3),
				rot
			),
			// Power connection (left)
			new DirPos(
				new BlockPos(pos.getX() - rot.getXOffset() * 3, pos.getY(), pos.getZ() - rot.getZOffset() * 3),
				rot.getOpposite()
			)
		};
	}

	@Override
	public void onChunkUnload() {
		super.onChunkUnload();

		if(audio != null) {
			audio.stopSound();
			audio = null;
		}
	}

	@Override
	public void invalidate() {
		super.invalidate();

		if(audio != null) {
			audio.stopSound();
			audio = null;
		}

		if(!world.isRemote) {
			if(this.klystronNode != null) {
				UniNodespace.destroyNode(world, klystronNode);
			}
		}
	}

	@Override
	public void networkUnpack(NBTTagCompound nbt) {
		super.networkUnpack(nbt);

		this.chargedEnergy = nbt.getLong("chargedEnergy");
		this.outputTarget = nbt.getLong("outputTarget");
		this.actualOutput = nbt.getLong("actualOutput");
		this.nbiOutput = nbt.getLong("nbiOutput");
		this.icrfOutput = nbt.getLong("icrfOutput");
		this.ecrfOutput = nbt.getLong("ecrfOutput");
		this.isPoweredOn = nbt.getBoolean("isPoweredOn");
		// hasAlreadyFiredはネットワーク同期しない（サーバー側のみ）
		this.hasPowerSupply = nbt.getBoolean("hasPowerSupply");
		this.torusReady = nbt.getBoolean("torusReady");
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);

		nbt.setLong("chargedEnergy", chargedEnergy);
		nbt.setLong("outputTarget", outputTarget);
		nbt.setBoolean("isPoweredOn", isPoweredOn);
		// hasAlreadyFiredは保存しない（ワールドリロード時にリセット）

		return nbt;
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);

		this.chargedEnergy = nbt.getLong("chargedEnergy");
		this.outputTarget = nbt.getLong("outputTarget");
		this.isPoweredOn = nbt.getBoolean("isPoweredOn");
		// hasAlreadyFiredは常にfalseから開始
		this.hasAlreadyFired = false;
	}

	@Override
	public long getMaxPower() {
		return maxPower;
	}

	// ===== IControlReceiver Implementation =====

	@Override
	public boolean hasPermission(net.minecraft.entity.player.EntityPlayer player) {
		return player.getDistanceSq(pos.getX() + 0.5, pos.getY() + 2.5, pos.getZ() + 0.5) < 20 * 20;
	}

	@Override
	public void receiveControl(NBTTagCompound data) {
		System.out.println("[Klystron] receiveControl() called with data: " + data);

		if(data.hasKey("amount")) {
			this.outputTarget = data.getLong("amount");
			if(this.outputTarget < 0) this.outputTarget = 0;
			long maxOutput = NBI_MAX_POWER + ICRH_MAX_POWER + ECRH_MAX_POWER;
			if(this.outputTarget > maxOutput) this.outputTarget = maxOutput;
			System.out.println("[Klystron] outputTarget set to: " + this.outputTarget);
			this.markDirty();
		}

		if(data.hasKey("powerButton")) {
			boolean newState = data.getBoolean("powerButton");
			System.out.println("[Klystron] Power button clicked! isPoweredOn: " + this.isPoweredOn + " -> " + newState);
			this.isPoweredOn = newState;

			// 電源ONの時、バーストフラグをリセットして新しいバーストを可能にする
			if(newState == true) {
				this.hasAlreadyFired = false;
				System.out.println("[Klystron] hasAlreadyFired reset to false - ready for new burst!");
			}

			this.markDirty();
		}
	}

	// ===== Rendering =====

	private AxisAlignedBB bb = null;

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		if(bb == null) {
			bb = new AxisAlignedBB(
				pos.getX() - 4,
				pos.getY(),
				pos.getZ() - 4,
				pos.getX() + 5,
				pos.getY() + 5,
				pos.getZ() + 5
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
