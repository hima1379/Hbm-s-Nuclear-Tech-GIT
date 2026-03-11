package com.hbm.main.tileentity.machine.fusion;

import java.util.Map.Entry;

import com.hbm.forgefluid.ModForgeFluids;
import com.hbm.interfaces.IControlReceiver;
import com.hbm.inventory.recipes.FusionRecipe;
import com.hbm.inventory.recipes.FusionRecipes;
import com.hbm.lib.Library;
import com.hbm.main.MainRegistry;
import com.hbm.main.tileentity.TileEntityLoadedBase;
import com.hbm.main.tileentity.machine.TileEntityCooledBase;
import com.hbm.module.machine.ModuleMachineFusion;
import com.hbm.physics.fusion.BetaLimit;
import com.hbm.physics.fusion.DensityLimit;
import com.hbm.physics.fusion.FusionCrossSections;
import com.hbm.physics.fusion.FusionReactionRate;
import com.hbm.physics.fusion.LawsonCriterion;
import com.hbm.physics.fusion.MagneticConfinement;
import com.hbm.physics.fusion.PlasmaDisruption;
import com.hbm.physics.fusion.PlasmaState;
import com.hbm.physics.fusion.PowerBalance;
import com.hbm.sound.AudioWrapper;
import com.hbm.uninos.DirPos;
import com.hbm.uninos.GenNode;
import com.hbm.uninos.INetworkProvider;
import com.hbm.uninos.UniNodespace;
import com.hbm.uninos.networkproviders.KlystronNetwork;
import com.hbm.uninos.networkproviders.KlystronNetworkProvider;
import com.hbm.uninos.networkproviders.PlasmaNetwork;
import com.hbm.uninos.networkproviders.PlasmaNetworkProvider;

import api.hbm.energy.IEnergyUser;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Fusion Torus - The heart of the fusion reactor (simplified for 1.12.2).
 *
 * Features:
 * - Receives klystron energy from Klystrons
 * - Processes fusion recipes
 * - Distributes plasma power to receivers
 * - Simplified cooling system (no TileEntityCooledBase dependency)
 *
 * @author Adapted for 1.12.2
 */
public class TileEntityFusionTorus extends TileEntityCooledBase implements IControlReceiver {

	public boolean didProcess = false;

	public FluidTank[] tanks;
	public ModuleMachineFusion fusionModule;

	protected GenNode<KlystronNetwork>[] klystronNodes;
	protected GenNode<PlasmaNetwork>[] plasmaNodes;
	public boolean[] connections;

	// klystronEnergy REMOVED - using heatingPower (W) for physics calculations
	public long plasmaEnergy;
	public double fuelConsumption;
	public double outputFlux; // Neutron flux being sent to receivers

	// Fuel rate control (1-9998)
	// 燃料レート：1 = デフォルト消費、2 = 2倍消費、9998 = 9998倍消費
	public int fuelRate = 1;              // 燃料消費レート（1～9998）
	private int previousFuelRate = 1;     // 前回のfuelRate（変更検出用）
	public static final int MIN_FUEL_RATE = 1;
	public static final int MAX_FUEL_RATE = 9998;

	// Physics-based fields
	public PlasmaState plasmaState;       // プラズマ状態（温度、密度等）
	public double qValue;                  // Q値（核融合利得）
	public double fusionPower;             // 核融合パワー (W)
	public double heatingPower;            // 外部加熱パワー (W)
	public double confinementTime;         // エネルギー閉じ込め時間 (s)
	public double plasmaVolume;            // プラズマ体積 (m³)

	// GUI temperature display fields (K)
	public double inputTemperatureK = 0.0;    // Klystronからの入力加熱による温度
	public double outputTemperatureK = 0.0;   // 核融合反応による出力温度
	public double currentTemperatureK = 0.0;  // 現在のプラズマ温度

	// Heating power from Klystron (W/tick)
	public long nbiPowerReceived = 0;    // NBI加熱パワー (W)
	public long icrfPowerReceived = 0;   // ICRH加熱パワー (W)
	public long ecrfPowerReceived = 0;   // ECRH加熱パワー (W)

	// Physics constants
	public static final double PLASMA_RADIUS = 2.0;        // プラズマ小半径 (m)
	public static final double PLASMA_MAJOR_RADIUS = 6.0;  // プラズマ大半径 (m)
	public static final double Z_EFF = 1.5;                // 実効原子番号（不純物レベル）
	public static final double ALPHA_HEATING_FRACTION = 0.25; // α加熱効率

	// ===== RADIATION CONSTANTS (Based on real fusion reactor data) =====
	// Source: ITER tokamak specifications and fusion physics research

	// Neutron energy per reaction type (MeV)
	public static final double NEUTRON_ENERGY_D_T = 14.1e6;    // D-T: 14.1 MeV neutrons
	public static final double NEUTRON_ENERGY_D_D = 2.45e6;    // D-D: 2.45 MeV neutrons (2nd branch)
	public static final double NEUTRON_ENERGY_D_HE3 = 0.0;     // D-He3: aneutronic (no neutrons)
	public static final double NEUTRON_ENERGY_HE3_HE3 = 0.0;   // He3-He3: aneutronic
	public static final double NEUTRON_ENERGY_EXOTIC = 18.0e6; // EXOTIC: higher energy (game balance)

	// Fraction of fusion energy carried by neutrons
	// D-T: ~80% of 17.6 MeV goes to neutron (14.1 MeV)
	// D-D: ~50% (one branch produces neutron, other produces He-3)
	// D-He3/He3-He3: <1% (aneutronic reactions)
	public static final double NEUTRON_FRACTION_D_T = 0.80;    // 80% of D-T energy in neutrons
	public static final double NEUTRON_FRACTION_D_D = 0.50;    // 50% of D-D energy in neutrons
	public static final double NEUTRON_FRACTION_D_HE3 = 0.01;  // ~1% (trace neutrons from side reactions)
	public static final double NEUTRON_FRACTION_HE3_HE3 = 0.01;
	public static final double NEUTRON_FRACTION_EXOTIC = 0.60; // 60% (game balance)

	// Neutron wall loading constants (MW/m²)
	// ITER target: 0.2-0.5 MW/m² stationary, up to 1 MW/m² in VNS concepts
	public static final double NEUTRON_WALL_LOADING_ITER = 0.5e6;  // 0.5 MW/m² = 0.5e6 W/m²

	// Convert fusion power to neutron flux (n/s)
	// For D-T: P_neutron = 0.8 × P_fusion
	// Neutron flux (n/s) = P_neutron / E_neutron
	// Where E_neutron = 14.1 MeV = 14.1e6 eV × 1.602e-19 J/eV = 2.26e-12 J

	// D-T fusion ignition temperature (ITER specifications)
	// D-T反応の点火温度: 4.0×10^7 K (40 million K, ~3.5 keV)
	// 実際の運用では1.5×10^8 K (150 million K, ~13 keV)程度で安定
	public static final double IGNITION_TEMPERATURE_MIN = 4.0e7;  // 最低点火温度 (K)
	public static final double IGNITION_TEMPERATURE_STABLE = 1.5e8; // 安定点火温度 (K)

	// ITER級電力容量: 50 GHE (50,000,000,000 HE)
	// 磁気封じ込め: 20 MW = 1,000,000 HE/tick
	// 運転時間: 50,000 tick (約42分間) の連続運転が可能
	public static final long MAX_POWER = 50_000_000_000L;

	// Magnetic confinement and disruption fields
	public boolean magneticFieldActive = true;      // 磁場が有効かどうか
	public double actualMagneticField = 5.3;        // 実際の磁場強度 (T)
	public boolean disruptionActive = false;        // 破壊が進行中か
	public double disruptionTime = 0.0;             // 破壊経過時間 (s)
	public double plasmaCurrent = 15.0;             // プラズマ電流 (MA) - ITER design value
	private int disruptionCooldown = 0;             // Disruption完了後の猶予期間 (ticks)

	public float magnet;
	public float prevMagnet;
	public float magnetSpeed;
	// Realistic high-speed rotation for ITER-scale tokamak
	// プラズマ/磁場回転の加速度と最大速度を大幅に増加
	public static final float MAGNET_ACCELERATION = 5.0F;     // 加速度を20倍に増加 (0.25F → 5.0F)
	public static final float MAX_MAGNET_SPEED = 720.0F;      // 最大速度: 720度/tick = 36回転/秒

	private AudioWrapper audio;
	public int timeOffset = -1;

	@SuppressWarnings("unchecked")
	public TileEntityFusionTorus() {
		super(3);

		// ITER級冷却システム: Integer.MAX_VALUE (2.147B) mB Perfluoromethyl
		// ITERの熱負荷: ~500 MW (核融合パワー + NBI/RF加熱損失)
		// Perfluoromethyl比熱: 1,100 J/(kg·K)、密度: 1,680 kg/m³
		// 必要冷却能力を満たすために超大容量タンクを使用
		// FluidTank.setCapacity()はint型のため、最大値はInteger.MAX_VALUE
		coolantTanks[0].setCapacity(Integer.MAX_VALUE);
		coolantTanks[1].setCapacity(Integer.MAX_VALUE);

		klystronNodes = new GenNode[4];
		plasmaNodes = new GenNode[4];
		connections = new boolean[4];

		this.tanks = new FluidTank[4];
		// Initialize all 4 input/output tanks (256,000 mB for high fuel rate support)
		// 256,000 mB = 256 buckets, supports fuelRate up to 9998
		this.tanks[0] = new FluidTank(256_000);
		this.tanks[1] = new FluidTank(256_000);
		this.tanks[2] = new FluidTank(256_000);
		this.tanks[3] = new FluidTank(256_000000);

		this.fusionModule = new ModuleMachineFusion(0, this, new net.minecraft.item.ItemStack[3])
				.fluidInput(tanks[0], tanks[1], tanks[2])
				.fluidOutput(tanks[3])
				.itemOutput(2);

		// Initialize plasma state (cold plasma)
		this.plasmaState = new PlasmaState(
			1.0e4,   // Ti = 10,000 K (cold)
			1.0e4,   // Te = 10,000 K (cold)
			1.0e19,  // n = 10^19 /m³ (typical tokamak density)
			5.0      // B = 5 Tesla (magnetic field)
		);

		// Calculate plasma volume for torus: V = 2π²Rr² where R=major radius, r=minor radius
		this.plasmaVolume = 2.0 * Math.PI * Math.PI * PLASMA_MAJOR_RADIUS * PLASMA_RADIUS * PLASMA_RADIUS;

		// Initial confinement time (will be updated)
		this.confinementTime = 0.1; // 0.1 seconds (poor confinement initially)

		this.qValue = 0.0;
		this.fusionPower = 0.0;
		this.heatingPower = 0.0;
	}

	@Override
	public String getName() {
		return "container.fusionTorus";
	}

	@Override
	public void update() {
		if(!world.isRemote) {
			// ===== PHASE 0: BATTERY CHARGING =====
			// Charge from battery in slot 0 (same as Klystron)
			this.power = com.hbm.lib.Library.chargeTEFromItems(inventory, 0, power, MAX_POWER);

			// Debug: Print power status every 20 ticks
			if(world.getTotalWorldTime() % 20 == 0) {
				System.out.println("[FusionTorus DEBUG] Tick=" + world.getTotalWorldTime() +
					", Power=" + power + "/" + MAX_POWER +
					", MagneticFieldActive=" + magneticFieldActive);
			}

			// ===== PHASE 1: MAGNETIC FIELD POWER CHECK =====
			// ITER requires 20 MW (1,000,000 HE/tick at 20 tps) for 5.3T magnetic field
			long requiredMagnetPower = MagneticConfinement.calculateMagnetPowerPerTick(com.hbm.physics.fusion.MagneticConfinement.TOROIDAL_FIELD_NOMINAL);
			long availablePower = this.power; // HE available this tick

			if(!MagneticConfinement.canMaintainField(availablePower, com.hbm.physics.fusion.MagneticConfinement.TOROIDAL_FIELD_NOMINAL)) {
				// Insufficient power → magnetic field lost
				magneticFieldActive = false;
				actualMagneticField = MagneticConfinement.calculateActualField(availablePower);

				if(actualMagneticField < MagneticConfinement.MIN_FIELD_FOR_CONFINEMENT) {
					// Field too weak → trigger disruption
					if(!disruptionActive) {
						System.out.println("[FusionTorus] ***** MAGNETIC FIELD LOST - TRIGGERING DISRUPTION *****");
						System.out.println("[FusionTorus] Available power: " + availablePower + " HE/tick, Required: " + requiredMagnetPower + " HE/tick");
						System.out.println("[FusionTorus] Magnetic field: " + String.format("%.2f", actualMagneticField) + " T (below min " + MagneticConfinement.MIN_FIELD_FOR_CONFINEMENT + " T)");
						triggerDisruption();
					}
				}
			} else {
				// Sufficient power → maintain field
				magneticFieldActive = true;
				actualMagneticField = com.hbm.physics.fusion.MagneticConfinement.TOROIDAL_FIELD_NOMINAL; // Restore to nominal 5.3 T

				// Consume power for magnetic field maintenance
				this.power -= requiredMagnetPower;

				if(world.getTotalWorldTime() % 100 == 0) {
					System.out.println("[FusionTorus] Magnetic field maintained: " + String.format("%.2f", actualMagneticField) + " T, consumed " + requiredMagnetPower + " HE/tick");
				}
			}

			// Update plasma state magnetic field
			plasmaState.magneticField = actualMagneticField;

			// ===== PHASE 2: HANDLE DISRUPTION =====
			if(disruptionActive) {
				updateDisruption(0.05); // Δt = 1 tick = 0.05 s

				// During disruption, skip fusion processing
				this.fusionPower = 0.0;
				this.didProcess = false;
				this.heatingPower = 0.0;

				// Skip rest of processing during disruption
				return;
			}

			// ===== PHASE 3: CHECK INSTABILITIES =====
			// Update disruption cooldown
			if(disruptionCooldown > 0) {
				disruptionCooldown--;
				if(world.getTotalWorldTime() % 20 == 0) {
					System.out.println("[FusionTorus] Disruption cooldown: " + disruptionCooldown + " ticks remaining");
				}
			}

			// Check if plasma should disrupt due to limits (skip during cooldown)
			if(disruptionCooldown == 0 && PlasmaDisruption.shouldDisrupt(plasmaState, actualMagneticField, plasmaCurrent, PLASMA_RADIUS)) {
				triggerDisruption();
				return;
			}

			// Temperature management (from TileEntityCooledBase)
			this.temperature += this.temp_passive_heating;
			if(this.temperature > KELVIN + 20) this.temperature = KELVIN + 20;

			if(world.getTotalWorldTime() % 20 == 0) {
				System.out.println("[FusionTorus] Temperature: " + temperature + "K, Target: " + temperature_target + "K, isCool: " + isCool());
				System.out.println("[FusionTorus] Coolant tank 0: " + coolantTanks[0].getFluidAmount() + " mB");
			}

			if(this.temperature > this.temperature_target) {
				int cyclesTemp = (int) Math.ceil((Math.min(this.temperature - temperature_target, temp_change_max)) / temp_change_per_mb);
				int cyclesCool = coolantTanks[0].getFluidAmount();
				int cyclesHot = coolantTanks[1].getCapacity() - coolantTanks[1].getFluidAmount();
				int cycles = Math.min(Math.min(cyclesTemp, cyclesCool), cyclesHot);

				if(cycles > 0) {
					coolantTanks[0].drain(cycles, true);

					// Fill hot tank with hot coolant
					if(ModForgeFluids.PERFLUOROMETHYL_HOT != null) {
						coolantTanks[1].fill(new FluidStack(ModForgeFluids.PERFLUOROMETHYL_HOT, cycles), true);
					}

					this.temperature -= this.temp_change_per_mb * cycles;

					if(world.getTotalWorldTime() % 20 == 0) {
						System.out.println("[FusionTorus] Cooling: cycles=" + cycles + ", new temperature=" + temperature + "K");
					}
				}
			}

			// Server-side logic

			// Create/update network nodes
			for(int i = 0; i < 4; i++) {
				if(klystronNodes[i] == null || klystronNodes[i].expired) {
					klystronNodes[i] = createNode(KlystronNetworkProvider.THE_PROVIDER, EnumFacing.byIndex(i + 2));
					if(world.getTotalWorldTime() % 20 == 0) {
						System.out.println("[FusionTorus] Created klystron node " + i + " at " + klystronNodes[i].positions[0]);
					}
				}
				if(plasmaNodes[i] == null || plasmaNodes[i].expired) {
					plasmaNodes[i] = createNode(PlasmaNetworkProvider.THE_PROVIDER, EnumFacing.byIndex(i + 2));
				}

				if(klystronNodes[i].net != null) {
					klystronNodes[i].net.addReceiver(this);
					if(world.getTotalWorldTime() % 20 == 0) {
						System.out.println("[FusionTorus] Registered as receiver in klystron network " + i);
						System.out.println("[FusionTorus] Network has " + klystronNodes[i].net.receiverEntries.size() + " receivers, " + klystronNodes[i].net.providerEntries.size() + " providers");
						System.out.println("[FusionTorus] Klystron node " + i + " position: " + klystronNodes[i].positions[0]);
					}
				} else {
					if(world.getTotalWorldTime() % 20 == 0) {
						System.out.println("[FusionTorus] WARNING: klystron node " + i + " has no network!");
					}
				}
				if(plasmaNodes[i].net != null) plasmaNodes[i].net.addProvider(this);
			}

			// Send output fluids to adjacent blocks via all connection points
			// Both hot coolant (coolantTanks[1]) and byproduct (tanks[3]) are sent
			// IMPORTANT: Check if the target can accept the fluid before sending (like sample's canConnect)
			for(DirPos conPos : getConPos()) {
				BlockPos targetPos = conPos.getPos().offset(conPos.getDir());
				TileEntity targetTE = world.getTileEntity(targetPos);

				if(targetTE != null && targetTE.hasCapability(net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, conPos.getDir().getOpposite())) {
					net.minecraftforge.fluids.capability.IFluidHandler targetHandler = targetTE.getCapability(
						net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY,
						conPos.getDir().getOpposite()
					);

					if(targetHandler != null) {
						// Send hot coolant if available and target accepts it
						if(coolantTanks[1].getFluidAmount() > 0) {
							FluidStack hotCoolant = coolantTanks[1].getFluid();
							if(hotCoolant != null && canTargetAcceptFluid(targetHandler, hotCoolant)) {
								com.hbm.forgefluid.FFUtils.fillFluid(this, coolantTanks[1], world, targetPos, 1000);
							}
						}

						// Send byproduct if available and target accepts it
						if(tanks[3].getFluidAmount() > 0) {
							FluidStack byproduct = tanks[3].getFluid();
							if(byproduct != null && canTargetAcceptFluid(targetHandler, byproduct)) {
								com.hbm.forgefluid.FFUtils.fillFluid(this, tanks[3], world, targetPos, 1000);
							}
						}
					}
				}
			}

			// Charge from battery
			this.power = Library.chargeTEFromItems(inventory, 0, power, MAX_POWER);

			// Count plasma receivers and collectors
			int receiverCount = 0;
			int collectors = 0;

			for(int i = 0; i < 4; i++) {
				connections[i] = false;
				if(klystronNodes[i] != null && klystronNodes[i].hasValidNet() && !klystronNodes[i].net.providerEntries.isEmpty()) {
					connections[i] = true;
				}
				if(!connections[i] && plasmaNodes[i] != null && plasmaNodes[i].hasValidNet() && !plasmaNodes[i].net.receiverEntries.isEmpty()) {
					connections[i] = true;
				}

				if(plasmaNodes[i] != null && plasmaNodes[i].hasValidNet() && !plasmaNodes[i].net.receiverEntries.isEmpty()) {
					for(Object o : plasmaNodes[i].net.receiverEntries.entrySet()) {
						Entry<Object, Long> entry = (Entry<Object, Long>) o;
						Object thing = entry.getKey();
						if(thing instanceof TileEntityLoadedBase && !((TileEntityLoadedBase) thing).isLoaded()) continue;
						if(thing instanceof IFusionPowerReceiver && ((IFusionPowerReceiver) thing).receivesFusionPower()) receiverCount++;
						if(thing instanceof TileEntityFusionCollector) collectors++;
						break;
					}
				}
			}

			FusionRecipe recipe = fusionModule.getRecipe();

			// Calculate processing factors
			double powerFactor = getSpeedScaled(MAX_POWER, power);
			double factor = powerFactor; // Simplified - no fuel checking yet

			// ===== 温度ベースの点火条件 (レシピタイプ依存物理モデル) =====
			// プラズマ温度が点火温度に到達しているかチェック
			// 反応タイプごとに異なる点火温度:
			// - D-T: 4.0e7 K (40 MK) - 最も容易
			// - D-D: 4.5e8 K (450 MK) - D-Tの約10倍
			// - D-He3: 1.0e9 K (1000 MK) - アニュートロニック、高温
			// - He3-He3: 1.6e9 K (1600 MK) - 最高温度要求
			// - EXOTIC: 2.0e8 K (200 MK) - 架空反応（ゲームバランス）
			FusionCrossSections.ReactionType reactionType = recipe != null ?
				FusionCrossSections.getTypeFromName(recipe.name) : FusionCrossSections.ReactionType.D_T;

			double recipeIgnitionTemp = FusionCrossSections.getIgnitionTemperature(reactionType);
			double avgPlasmaTemp = (plasmaState.temperatureIon + plasmaState.temperatureElectron) / 2.0;
			boolean temperatureIgnition = avgPlasmaTemp >= recipeIgnitionTemp;

			// 最終的な点火条件: 温度条件 AND 磁場が有効
			boolean ignition = temperatureIgnition && magneticFieldActive;

			// 常時デバッグ出力（点火条件確認用）
			if(recipe != null) {
				System.out.println("[FusionTorus] Ignition check: avgTemp=" + String.format("%.2e K", avgPlasmaTemp) +
					" / recipeIgnitionTemp=" + String.format("%.2e K", recipeIgnitionTemp) +
					" (recipe=" + recipe.name + ", type=" + reactionType + ")" +
					" => tempIgnition=" + temperatureIgnition + ", magneticField=" + magneticFieldActive +
					" => IGNITION=" + ignition);
			}

			if(world.getTotalWorldTime() % 20 == 0 && recipe != null) {
				System.out.println("[FusionTorus] ===== STARTUP CONDITIONS =====");
				System.out.println("[FusionTorus] Recipe: " + recipe.getName());
				System.out.println("[FusionTorus] Plasma Temperature: " + String.format("%.2e K", avgPlasmaTemp) +
					" (ignition at " + String.format("%.2e K", IGNITION_TEMPERATURE_MIN) + ")");
				System.out.println("[FusionTorus] Magnetic Field: " + String.format("%.2f T", actualMagneticField) +
					" (active=" + magneticFieldActive + ")");
				System.out.println("[FusionTorus] Temperature Ignition: " + temperatureIgnition + " (temp >= " + String.format("%.2e K", IGNITION_TEMPERATURE_MIN) + ")");
				System.out.println("[FusionTorus] Wall Temperature: " + temperature + " / " + temperature_target + " (isCool=" + isCool() + ")");
				System.out.println("[FusionTorus] Power: " + power + " / " + MAX_POWER);
				System.out.println("[FusionTorus] Input tanks: [0]=" + tanks[0].getFluidAmount() + ", [1]=" + tanks[1].getFluidAmount() + ", [2]=" + tanks[2].getFluidAmount());
				System.out.println("[FusionTorus] canStart: " + (isCool() && ignition) + " (isCool=" + isCool() + " && ignition=" + ignition + ")");
				System.out.println("[FusionTorus] =============================");
			}

			this.plasmaEnergy = 0;
			this.fuelConsumption = 0;

			// Set fuel rate before processing
			// fuelRate範囲チェック：1～9998
			this.fuelRate = Math.max(MIN_FUEL_RATE, Math.min(MAX_FUEL_RATE, this.fuelRate));
			this.fusionModule.fuelRate = this.fuelRate;

			// Detect fuel rate change and adjust plasma parameters dynamically
			if(this.fuelRate != this.previousFuelRate) {
				double rateRatio = (double)this.fuelRate / (double)this.previousFuelRate;

				// Higher fuel rate → higher density → higher temperature (from increased collisions/fusion)
				// Density scales with fuel rate (more fuel injection)
				plasmaState.density *= Math.sqrt(rateRatio);  // Scale density by sqrt(rate ratio)

				// Temperature responds to density change (adiabatic response)
				// For ideal plasma: T ∝ n^(γ-1) where γ = 5/3 for monatomic gas
				// T ∝ n^(2/3)
				double tempRatio = Math.pow(rateRatio, 0.33);  // Moderate temperature response
				plasmaState.temperatureIon *= tempRatio;
				plasmaState.temperatureElectron *= tempRatio;

				// Update pressure and beta
				plasmaState.pressure = plasmaState.calculatePressure();
				plasmaState.plasmaBeta = plasmaState.calculateBeta();

				System.out.println("[FusionTorus] ***** FUEL RATE CHANGED: " + this.previousFuelRate + " → " + this.fuelRate + " *****");
				System.out.println("[FusionTorus] Rate ratio: " + String.format("%.3f", rateRatio));
				System.out.println("[FusionTorus] Density adjusted: " + String.format("%.3e → %.3e /m³ (×%.3f)",
					plasmaState.density / Math.sqrt(rateRatio), plasmaState.density, Math.sqrt(rateRatio)));
				System.out.println("[FusionTorus] Temperature adjusted: " + String.format("%.6e → %.6e K (×%.3f)",
					(plasmaState.temperatureIon + plasmaState.temperatureElectron) / (2.0 * tempRatio),
					(plasmaState.temperatureIon + plasmaState.temperatureElectron) / 2.0,
					tempRatio));

				this.previousFuelRate = this.fuelRate;
			}

			this.fusionModule.preUpdate(factor, collectors * 0.5D);
			this.fusionModule.update(1D, 1D, this.isCool() && ignition, inventory.getStackInSlot(1));
			this.didProcess = this.fusionModule.didProcess;

			if(this.didProcess && world.getTotalWorldTime() % 20 == 0) {
				System.out.println("[FusionTorus] ***** PROCESSING! didProcess=true *****");
			}
			if(this.fusionModule.markDirty) this.markDirty();

			// ===== Physics-based calculations =====
			// 温度進化は加熱パワーがあれば常に実行（点火前でも）
			// これにより、Klystronバーストでプラズマを加熱できる
			double dt = 0.05; // seconds per tick
			// reactionTypeは393-395行目で既に定義済み

			// 磁場が有効な場合、温度を進化させる
			// これにより、Klystronバーストによる点火前加熱が可能になる
			if(magneticFieldActive) {
				System.out.println("[FusionTorus] ***** TEMPERATURE EVOLUTION ACTIVE *****");
				System.out.println("[FusionTorus] magneticFieldActive=" + magneticFieldActive + ", heatingPower=" + heatingPower + " W, didProcess=" + didProcess);

				// Update confinement time estimate
				this.confinementTime = LawsonCriterion.estimateConfinementTime(plasmaState, PLASMA_RADIUS);

				// Calculate fusion power (only if ignited)
				if(didProcess && recipe != null) {
					double baseFusionPower = FusionReactionRate.calculateFusionPower(
						plasmaState,
						reactionType,
						plasmaVolume
					);

					// Apply reaction type power scaling (EXOTIC = 10x)
					double powerScalingFactor = FusionCrossSections.getPowerScalingFactor(reactionType);

					// Scale by processing factor, fuel rate, and reaction type
					this.fusionPower = baseFusionPower * factor * this.fuelRate * powerScalingFactor;

					System.out.println("[FusionTorus] ***** FUSION POWER CALCULATION *****");
					System.out.println("[FusionTorus] baseFusionPower=" + String.format("%.3e W", baseFusionPower));
					System.out.println("[FusionTorus] factor=" + factor + ", fuelRate=" + this.fuelRate +
					                   ", powerScaling=" + String.format("%.1fx", powerScalingFactor) +
					                   " (type=" + reactionType + ")");
					System.out.println("[FusionTorus] finalFusionPower=" + String.format("%.3e W", this.fusionPower));
				} else {
					this.fusionPower = 0.0;
				}

				// Calculate Q-value (fusion gain)
				this.qValue = FusionReactionRate.calculateQValue(fusionPower, heatingPower);

				double tempBefore = (plasmaState.temperatureIon + plasmaState.temperatureElectron) / 2.0;
				System.out.println("[FusionTorus] BEFORE RK4: avgTemp=" + String.format("%.2e K", tempBefore));
		System.out.println("[FusionTorus] RK4 Parameters: volume=" + plasmaVolume + ", confinementTime=" + confinementTime + ", heatingPower=" + heatingPower + ", fusionPower=" + fusionPower);
		try {

				// Evolve plasma temperature using RK4 integration
				// 加熱パワー（Klystronバースト）とα加熱（融合）の両方を考慮
				PowerBalance.evolveTemperatureRK4(
					plasmaState,
					plasmaVolume,
					confinementTime,
					heatingPower,
					fusionPower,
					ALPHA_HEATING_FRACTION,
					Z_EFF,
					dt,
					reactionType
				);
		} catch (Throwable e) {
				System.out.println("[FusionTorus] ***** EXCEPTION/ERROR IN RK4! *****");
				e.printStackTrace();
				System.out.println("[FusionTorus] Error type: " + e.getClass().getName());
				System.out.println("[FusionTorus] Message: " + e.getMessage());
		}

				// Physics-based temperature evolution with RECIPE-DEPENDENT limits
				// Temperature naturally varies based on heating power, fusion power, and losses
				// Apply realistic upper limit based on REACTION TYPE and fuel rate
				//
				// 各反応タイプの特性：
				// - D-T (基準): 2.0e8 × 1.0 × √(fuelRate)
				// - D-D: 2.0e8 × 1.5 × √(fuelRate) - D-Tより高温で最適
				// - D-He3: 2.0e8 × 2.5 × √(fuelRate) - アニュートロニック、さらに高温
				// - He3-He3: 2.0e8 × 4.0 × √(fuelRate) - 超高温要求
				// - EXOTIC (反物質): 2.0e8 × 15.0 × √(fuelRate) - 桁違いの高温・高出力
				double baseTemp = 2.0e8;  // 200 MK base
				double tempScalingFactor = FusionCrossSections.getTemperatureScalingFactor(reactionType);
				double realisticMaxTemp = baseTemp * tempScalingFactor * Math.sqrt(this.fuelRate);

				// 絶対安全上限（数値オーバーフロー防止）
				// 通常の核融合: 10 GK、反物質反応: 50 GK
				double safetyMaxTemp = (reactionType == FusionCrossSections.ReactionType.EXOTIC) ?
					5.0e10 : 1.0e10;

				double tempAfterBeforeClamp = (plasmaState.temperatureIon + plasmaState.temperatureElectron) / 2.0;

				// Apply realistic limit first, then safety limit
				double effectiveMaxTemp = Math.min(realisticMaxTemp, safetyMaxTemp);
				plasmaState.temperatureIon = Math.min(effectiveMaxTemp, Math.max(1000.0, plasmaState.temperatureIon));
				plasmaState.temperatureElectron = Math.min(effectiveMaxTemp, Math.max(1000.0, plasmaState.temperatureElectron));

				// Log if temperature was clamped
				if(tempAfterBeforeClamp > effectiveMaxTemp && world.getTotalWorldTime() % 20 == 0) {
					System.out.println("[FusionTorus] ***** TEMPERATURE CLAMPED *****");
					System.out.println("[FusionTorus] Recipe: " + (recipe != null ? recipe.name : "none") +
					                   ", Type: " + reactionType + ", Scaling: " + String.format("%.1fx", tempScalingFactor));
					System.out.println("[FusionTorus] Before clamp: " + String.format("%.2e K (%.0f MK)", tempAfterBeforeClamp, tempAfterBeforeClamp/1e6) +
					                   ", Limit: " + String.format("%.2e K (%.0f MK)", effectiveMaxTemp, effectiveMaxTemp/1e6) +
					                   " (fuelRate=" + this.fuelRate + ")");
				}

				double tempAfter = (plasmaState.temperatureIon + plasmaState.temperatureElectron) / 2.0;
				double tempDelta = tempAfter - tempBefore;

				// Log temperature evolution with full precision
				System.out.println("[FusionTorus] AFTER RK4: avgTemp=" + String.format("%.6e K (%.2f MK)", tempAfter, tempAfter/1e6) +
					", ΔT=" + String.format("%+.6e K (%+.4f MK)", tempDelta, tempDelta/1e6));
				System.out.println("[FusionTorus] Ion/Electron temps: Ti=" + String.format("%.6e K", plasmaState.temperatureIon) +
					", Te=" + String.format("%.6e K", plasmaState.temperatureElectron) + " (fuelRate=" + this.fuelRate + ")");
			} else {
				if(world.getTotalWorldTime() % 20 == 0) {
					System.out.println("[FusionTorus] Temperature evolution SKIPPED: heatingPower=" + heatingPower + " W, didProcess=" + didProcess);
				}
			}

			if(didProcess && recipe != null) {

				// Calculate neutron flux from fusion power
				double neutronFlux = FusionReactionRate.calculateNeutronFlux(fusionPower, reactionType);

				// Convert to legacy values for compatibility
				this.plasmaEnergy = (long) Math.ceil(fusionPower / 1.0e6); // W to TU (approx)
				this.fuelConsumption = factor * this.fuelRate;  // Include fuel rate in display
				this.outputFlux = neutronFlux / 1.0e15; // Scale to manageable numbers

				// GUI温度表示の更新
				// inputTemperatureKはreceiveHeating()で計算済み
				// outputTemperatureKは核融合反応による温度上昇を推定
				// currentTemperatureKは現在のプラズマ温度（イオン・電子の平均）
				if(fusionPower > 0 && plasmaState.density > 0) {
					double particleCount = plasmaState.density * plasmaVolume;
					double deltaT_fusion = (fusionPower * ALPHA_HEATING_FRACTION * dt) / (particleCount * PlasmaState.BOLTZMANN);
					this.outputTemperatureK = Math.min(deltaT_fusion, 1.0e9); // 上限1GK
				} else {
					this.outputTemperatureK = 0.0;
				}
				this.currentTemperatureK = (plasmaState.temperatureIon + plasmaState.temperatureElectron) / 2.0;

				if(world.getTotalWorldTime() % 20 == 0) {
					System.out.println("[FusionTorus] ===== PHYSICS STATUS =====");
					System.out.println("[FusionTorus] Plasma State: " + plasmaState.toString());
					System.out.println("[FusionTorus] Fusion Power: " + String.format("%.2e W (%.1f MW)", fusionPower, fusionPower/1e6));
					System.out.println("[FusionTorus] Heating Power: " + String.format("%.2e W (%.1f MW)", heatingPower, heatingPower/1e6));
					System.out.println("[FusionTorus] Q-value: " + String.format("%.3f", qValue));
					System.out.println("[FusionTorus] Confinement time: " + String.format("%.3f s", confinementTime));
					System.out.println("[FusionTorus] Neutron flux: " + String.format("%.2e n/s", neutronFlux));
					System.out.println("[FusionTorus] ========================");
				}
			} else {
				// No fusion - temperature evolution is handled by RK4 (includes all radiation losses)
				// This block only updates GUI values
				this.fusionPower = 0.0;
				this.qValue = 0.0;
				this.outputFlux = 0.0;

				// Update GUI temperature display
				// inputTemperatureK is set by receiveHeating() if Klystron is active
				this.outputTemperatureK = 0.0;  // No fusion heating
				this.currentTemperatureK = (plasmaState.temperatureIon + plasmaState.temperatureElectron) / 2.0;

				// Debug log when not processing fusion
				if(world.getTotalWorldTime() % 20 == 0) {
					System.out.println("[FusionTorus] ***** NO FUSION - Temperature evolving via RK4 only *****");
					System.out.println("[FusionTorus] heatingPower=" + String.format("%.2e W", heatingPower) +
						", fusionPower=0 W");
					System.out.println("[FusionTorus] Current plasma temp: Ti=" + String.format("%.6e K", plasmaState.temperatureIon) +
						", Te=" + String.format("%.6e K", plasmaState.temperatureElectron));
				}
			}

			double outputIntensity = getOutputIntensity(receiverCount);
			// NOTE: outputFlux is now calculated from physics in the didProcess block above (line 520)
			// Old recipe-based calculation removed to use physics-based neutron flux

			// Debug logging for flux transmission
			if(world.getTotalWorldTime() % 20 == 0 && this.outputFlux > 0) {
				System.out.println("[FusionTorus] ***** TRANSMITTING FLUX: " + this.outputFlux + " ***** (recipe: " +
					(recipe != null ? recipe.output.getDisplayName() : "none") + ", factor: " + factor + ")");
			}

			// Distribute plasma power and neutron flux
			// DEBUG: Always log plasmaEnergy value
			System.out.println("[FusionTorus] *** CHECKING plasmaEnergy=" + this.plasmaEnergy + " TU (tick=" + world.getTotalWorldTime() + ")");
			if(this.plasmaEnergy > 0) {
				System.out.println("[FusionTorus] *** plasmaEnergy > 0, distributing power...");
				// Calculate realistic neutron power for breeding/transmutation
				// This uses physics-based constants from real fusion reactor data
				FusionRecipe currentRecipe = fusionModule.getRecipe();
				FusionCrossSections.ReactionType currentReactionType = currentRecipe != null ?
					FusionCrossSections.getTypeFromName(currentRecipe.name) : FusionCrossSections.ReactionType.D_T;

				double neutronPowerForBreeding = calculateNeutronPowerForBreeding(
					this.fusionPower,
					currentReactionType
				);

				if(world.getTotalWorldTime() % 20 == 0) {
					System.out.println("[FusionTorus] ===== PLASMA POWER DISTRIBUTION =====");
					System.out.println("[FusionTorus] plasmaEnergy: " + this.plasmaEnergy + " TU");
					System.out.println("[FusionTorus] Fusion power: " + String.format("%.2e W", this.fusionPower));
					System.out.println("[FusionTorus] Neutron power for breeding: " + String.format("%.2f", neutronPowerForBreeding));
					System.out.println("[FusionTorus] Checking 4 plasma nodes for receivers...");
				}

				int totalReceiversFound = 0;
				for(int i = 0; i < 4; i++) {
					if(plasmaNodes[i] != null) {
						boolean hasNet = plasmaNodes[i].hasValidNet();
						int nodeReceiverCount = hasNet ? plasmaNodes[i].net.receiverEntries.size() : 0;

						if(world.getTotalWorldTime() % 20 == 0) {
							System.out.println("[FusionTorus]   Node[" + i + "]: position=" + (plasmaNodes[i].positions != null && plasmaNodes[i].positions.length > 0 ? plasmaNodes[i].positions[0] : "null") +
								", hasValidNet=" + hasNet + ", receivers=" + nodeReceiverCount);
						}

						if(hasNet && !plasmaNodes[i].net.receiverEntries.isEmpty()) {
							for(Object o : plasmaNodes[i].net.receiverEntries.entrySet()) {
								Entry<Object, Long> entry = (Entry<Object, Long>) o;
								if(entry.getKey() instanceof IFusionPowerReceiver) {
									totalReceiversFound++;

									// NEW: Send physics-based values (W, K) instead of legacy TU units
									// Distribute fusion power among receivers based on outputIntensity
									double thermalPowerPerReceiver = this.fusionPower * outputIntensity;
									double plasmaTemp = this.currentTemperatureK;

									if(world.getTotalWorldTime() % 20 == 0) {
										String receiverType = entry.getKey().getClass().getSimpleName();
										System.out.println("[FusionTorus]     → Sending to " + receiverType +
											": thermalPower=" + String.format("%.2e W (%.1f MW)", thermalPowerPerReceiver, thermalPowerPerReceiver/1e6) +
											", plasmaTemp=" + String.format("%.2e K (%.0f MK)", plasmaTemp, plasmaTemp/1e6) +
											", neutronFlux=" + String.format("%.2e", neutronPowerForBreeding));
									}

									// Send physics-based plasma state: thermal power (W), temperature (K), neutron flux
									((IFusionPowerReceiver) entry.getKey()).receiveFusionPower(
										thermalPowerPerReceiver,
										plasmaTemp,
										neutronPowerForBreeding
									);
								}
							}
						}
					} else {
						if(world.getTotalWorldTime() % 20 == 0) {
							System.out.println("[FusionTorus]   Node[" + i + "]: NULL");
						}
					}
				}

				if(world.getTotalWorldTime() % 20 == 0) {
					System.out.println("[FusionTorus] Total receivers powered: " + totalReceiversFound);
					System.out.println("[FusionTorus] =======================================");
				}
			} else {
				if(world.getTotalWorldTime() % 20 == 0) {
					System.out.println("[FusionTorus] ===== NO PLASMA POWER TO DISTRIBUTE =====");
					System.out.println("[FusionTorus] plasmaEnergy: " + this.plasmaEnergy + " TU (must be > 0)");
					System.out.println("[FusionTorus] didProcess: " + didProcess + ", recipe: " + (fusionModule.getRecipe() != null ? fusionModule.getRecipe().name : "null"));
					System.out.println("[FusionTorus] =======================================");
				}
			}

			// Network sync
			NBTTagCompound data = new NBTTagCompound();
			data.setBoolean("didProcess", didProcess);
			// klystronEnergy removed - using heatingPower instead
			data.setLong("plasmaEnergy", plasmaEnergy);
			data.setDouble("fuelConsumption", fuelConsumption);
			data.setLong("power", power);
			data.setDouble("progress", fusionModule.progress);
			data.setDouble("bonus", fusionModule.bonus);
			data.setDouble("outputFlux", outputFlux); // Add neutron flux for client display
			data.setInteger("fuelRate", fuelRate); // Fuel rate for client display
			for(int i = 0; i < 4; i++) data.setBoolean("conn" + i, connections[i]);

			// Add plasma physics data for client display
			data.setDouble("plasmaTi", plasmaState.temperatureIon);
			data.setDouble("plasmaTe", plasmaState.temperatureElectron);
			data.setDouble("plasmaDensity", plasmaState.density);
			data.setDouble("qValue", qValue);

			// Add GUI temperature fields
			data.setDouble("inputTemperatureK", inputTemperatureK);
			data.setDouble("outputTemperatureK", outputTemperatureK);
			data.setDouble("currentTemperatureK", currentTemperatureK);

			// Add heating power data for GUI display
			data.setLong("nbiPowerReceived", nbiPowerReceived);
			data.setLong("icrfPowerReceived", icrfPowerReceived);
			data.setLong("ecrfPowerReceived", ecrfPowerReceived);
			data.setDouble("heatingPower", heatingPower);

			// Add temperature and coolant tank data for client sync
			data.setFloat("temperature", temperature);
			for(int i = 0; i < 2; i++) {
				if(coolantTanks[i].getFluid() != null) {
					data.setInteger("tank" + i, coolantTanks[i].getFluidAmount());
				}
			}

			// Add fluid tank data for client sync
			for(int i = 0; i < 4; i++) {
				FluidStack fluid = tanks[i].getFluid();
				if(fluid != null && fluid.amount > 0) {
					data.setString("tank" + i + "_fluid", fluid.getFluid().getName());
					data.setInteger("tank" + i + "_amount", fluid.amount);
				}
			}

			// Add magnetic field status for client rotation
			data.setBoolean("magneticFieldActive", magneticFieldActive);

			this.networkPack(data, 50);


		} else {
			// Client-side logic

			if(timeOffset == -1) this.timeOffset = world.rand.nextInt(30_000);

			double powerFactor = getSpeedScaled(MAX_POWER, power);
			// 磁気封じ込めが有効な場合は回転（電力がある限り回転）
			if(this.magneticFieldActive) {
				this.magnetSpeed += MAGNET_ACCELERATION;
			} else {
				this.magnetSpeed -= MAGNET_ACCELERATION * 0.5F; // ゆっくり減速
			}

			// リアリスティックな高速回転: 最大720度/tick (36回転/秒 at 20 tps)
			// 融合反応が活発なほど高速回転
			this.magnetSpeed = MathHelper.clamp(this.magnetSpeed, 0F, MAX_MAGNET_SPEED * (float) powerFactor);

			this.prevMagnet = this.magnet;
			this.magnet += this.magnetSpeed;

			if(this.magnet >= 360F) {
				this.magnet -= 360F;
				this.prevMagnet -= 360F;
			}

			// Sound management
			if(this.magnetSpeed > 0 && MainRegistry.proxy.me().getDistanceSq(pos.getX() + 0.5, pos.getY() + 2.5, pos.getZ() + 0.5) < 50 * 50) {
				float speed = this.magnetSpeed / 30F;

				if(audio == null) {
					audio = MainRegistry.proxy.getLoopedSound(com.hbm.lib.HBMSoundHandler.fusionReactorRunning, net.minecraft.util.SoundCategory.BLOCKS,
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


	public static double getOutputIntensity(int receiverCount) {
		if(receiverCount == 1) return 1D; // 100%
		if(receiverCount == 2) return 0.625D; // 125% total
		if(receiverCount == 3) return 0.5D; // 150% total
		return 0.4375D; // 175% total
	}

	/**
	 * Calculate neutron power for breeding/transmutation based on reaction type.
	 *
	 * Real fusion reactor physics:
	 * - D-T: 80% of fusion power goes to 14.1 MeV neutrons
	 * - D-D: 50% of fusion power goes to 2.45 MeV neutrons (averaged)
	 * - D-He3/He3-He3: ~1% (aneutronic, minimal neutrons)
	 *
	 * This method calculates the neutron flux (n/s) that hits the breeding blanket.
	 *
	 * @param fusionPower Total fusion power (W)
	 * @param reactionType Type of fusion reaction
	 * @return Neutron power for transmutation (n/s scaled for RBMKOutgasser recipes)
	 */
	public static double calculateNeutronPowerForBreeding(double fusionPower, FusionCrossSections.ReactionType reactionType) {
		if(fusionPower <= 0) return 0.0;

		// Get neutron fraction and energy for this reaction type
		double neutronFraction = 0.0;
		double neutronEnergy = 0.0;  // in eV

		switch(reactionType) {
			case D_T:
				neutronFraction = NEUTRON_FRACTION_D_T;
				neutronEnergy = NEUTRON_ENERGY_D_T;
				break;
			case D_D:
				neutronFraction = NEUTRON_FRACTION_D_D;
				neutronEnergy = NEUTRON_ENERGY_D_D;
				break;
			case D_HE3:
				neutronFraction = NEUTRON_FRACTION_D_HE3;
				neutronEnergy = NEUTRON_ENERGY_D_HE3;
				break;
			case HE3_HE3:
				neutronFraction = NEUTRON_FRACTION_HE3_HE3;
				neutronEnergy = NEUTRON_ENERGY_HE3_HE3;
				break;
			case EXOTIC:
				neutronFraction = NEUTRON_FRACTION_EXOTIC;
				neutronEnergy = NEUTRON_ENERGY_EXOTIC;
				break;
			default:
				neutronFraction = NEUTRON_FRACTION_D_T;
				neutronEnergy = NEUTRON_ENERGY_D_T;
		}

		// Calculate power carried by neutrons
		double neutronPower = fusionPower * neutronFraction;  // Watts

		// Convert to neutron flux (n/s)
		// E_neutron (J) = neutronEnergy (eV) × 1.602176634e-19 (J/eV)
		// flux (n/s) = P_neutron (W) / E_neutron (J)
		if(neutronEnergy > 0) {
			double neutronEnergyJoules = neutronEnergy * 1.602176634e-19;
			double neutronFlux = neutronPower / neutronEnergyJoules;  // n/s

			// Scale for breeding blanket efficiency
			// Real breeding blankets capture ~10-30% of neutrons for transmutation
			// ITER TBR (Tritium Breeding Ratio) target: 1.0-1.2 (tritium per D-T neutron)
			double breedingEfficiency = 0.20;  // 20% of neutrons cause transmutation
			double effectiveFlux = neutronFlux * breedingEfficiency;

			// Scale to RBMKOutgasser recipe values
			// Li-6 breeding requires 240 flux units total
			// At 100 MW fusion power, Li-6 should breed in ~30 seconds (600 ticks)
			// Required flux per tick: 240 / 600 = 0.4 flux/tick at 100 MW
			// Scaling: (flux/tick at 100 MW) / (100 MW) = 0.4 / 100e6 = 4e-9
			//
			// This makes breeding times reasonable:
			// - Li-6 dust tiny (240): ~30 sec at 100 MW
			// - Gold ingot (360,000): ~12.5 hours at 100 MW
			// - U-238 → Pu-239 (190,000): ~6.6 hours at 100 MW
			double scalingFactor = 4.0e-9;  // flux per watt per tick
			double recipeFlux = neutronPower * scalingFactor;

			return recipeFlux;
		}

		return 0.0;
	}

	/**
	 * Checks if a target IFluidHandler can accept a specific fluid.
	 * This is critical for respecting pipe filters and preventing wrong fluids from being sent.
	 * Similar to sample's canConnect(type, dir) check.
	 *
	 * @param targetHandler The target fluid handler (pipe/tank)
	 * @param fluid The fluid to check
	 * @return true if the target can accept this fluid
	 */
	private boolean canTargetAcceptFluid(net.minecraftforge.fluids.capability.IFluidHandler targetHandler, FluidStack fluid) {
		if(targetHandler == null || fluid == null) return false;

		try {
			// Try a dry run (doFill=false) to check if the target accepts this fluid
			// If it returns > 0, the target can accept the fluid
			int canFill = targetHandler.fill(new FluidStack(fluid, 1), false);
			return canFill > 0;
		} catch(Exception e) {
			return false;
		}
	}

	/**
	 * KlystronからNBI/ICRH/ECRH加熱パワーを受信
	 *
	 * ITER式加熱システム：
	 * - NBI (Neutral Beam Injection): 中性粒子ビーム加熱
	 * - ICRH (Ion Cyclotron Resonance Heating): イオンサイクロトロン共鳴加熱
	 * - ECRH (Electron Cyclotron Resonance Heating): 電子サイクロトロン共鳴加熱
	 *
	 * 受信したパワーはheatingPowerに加算され、プラズマ温度計算に使用される
	 *
	 * @param nbiPower NBI出力 (W/tick)
	 * @param icrfPower ICRH出力 (W/tick)
	 * @param ecrfPower ECRH出力 (W/tick)
	 */
	public void receiveHeating(long nbiPower, long icrfPower, long ecrfPower) {
		// 総加熱パワーを計算 (W/tick)
		long totalHeatingPower = nbiPower + icrfPower + ecrfPower;

		// heatingPowerフィールドを更新（物理計算で使用）
		this.heatingPower = totalHeatingPower;
		// klystronEnergy removed - heatingPower is used directly

		// 個別の加熱パワーを設定
		this.nbiPowerReceived = nbiPower;
		this.icrfPowerReceived = icrfPower;
		this.ecrfPowerReceived = ecrfPower;

		System.out.println("========================================");
		System.out.println("[FusionTorus] ***** receiveHeating() CALLED *****");
		System.out.println("[FusionTorus] Tick: " + world.getTotalWorldTime());
		System.out.println("[FusionTorus] NBI=" + nbiPower + " W, ICRH=" + icrfPower + " W, ECRH=" + ecrfPower + " W");
		System.out.println("[FusionTorus] Total=" + totalHeatingPower + " W");
		System.out.println("[FusionTorus] heatingPower set to: " + this.heatingPower + " W");
		System.out.println("[FusionTorus] heatingPower set to: " + this.heatingPower + " W");
		System.out.println("========================================");

		// 入力温度を計算（加熱パワーから推定）
		// 簡易計算: P = n × V × k_B × ΔT / Δt
		// ΔT = P × Δt / (n × V × k_B)
		// Δt = 1 tick = 0.05 s
		if(totalHeatingPower > 0 && plasmaState.density > 0) {
			double dt_heating = 0.05; // seconds per tick
			double particleCount = plasmaState.density * plasmaVolume;
			double deltaT = (totalHeatingPower * dt_heating) / (particleCount * PlasmaState.BOLTZMANN);
			this.inputTemperatureK = Math.min(deltaT, 1.0e9); // 上限1GK
		} else {
			this.inputTemperatureK = 0.0;
		}
	}

	@SuppressWarnings("unchecked")
	public GenNode createNode(INetworkProvider provider, EnumFacing dir) {
		int nodeX = pos.getX() + dir.getXOffset() * 7;
		int nodeY = pos.getY() + 2;
		int nodeZ = pos.getZ() + dir.getZOffset() * 7;

		GenNode node = UniNodespace.getNode(world, nodeX, nodeY, nodeZ, provider);
		if(node != null) return node;

		BlockPos nodePos = new BlockPos(nodeX, nodeY, nodeZ);
		BlockPos connectionPos = new BlockPos(
			nodeX + dir.getXOffset(),
			nodeY,
			nodeZ + dir.getZOffset()
		);

		node = new GenNode(provider, nodePos)
			.setConnections(new DirPos(connectionPos, dir));

		UniNodespace.createNode(world, node);

		return node;
	}

	/** Linearly scales up from 0% to 100% from 0 to 0.5, then stays at 100% */
	public static double getSpeedScaled(double max, double level) {
		if(max == 0) return 0D;
		if(level >= max * 0.5) return 1D;
		return level / max * 2D;
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
			for(GenNode node : klystronNodes) if(node != null) UniNodespace.destroyNode(world, node);
			for(GenNode node : plasmaNodes) if(node != null) UniNodespace.destroyNode(world, node);
		}
	}

	@Override
	public void networkUnpack(NBTTagCompound nbt) {
		super.networkUnpack(nbt);

		this.didProcess = nbt.getBoolean("didProcess");
		// klystronEnergy removed - using heatingPower
		this.plasmaEnergy = nbt.getLong("plasmaEnergy");
		this.fuelConsumption = nbt.getDouble("fuelConsumption");
		this.outputFlux = nbt.getDouble("outputFlux"); // Read neutron flux from network packet
		this.fusionModule.progress = nbt.getDouble("progress");
		this.fusionModule.bonus = nbt.getDouble("bonus");

		// Read fuel rate from network packet
		if(nbt.hasKey("fuelRate")) {
			this.fuelRate = nbt.getInteger("fuelRate");
		}

		for(int i = 0; i < 4; i++) connections[i] = nbt.getBoolean("conn" + i);

		// Read plasma physics data from network packet
		if(nbt.hasKey("plasmaTi")) {
			plasmaState.temperatureIon = nbt.getDouble("plasmaTi");
			plasmaState.temperatureElectron = nbt.getDouble("plasmaTe");
			plasmaState.density = nbt.getDouble("plasmaDensity");
			this.qValue = nbt.getDouble("qValue");
		}

		// Read GUI temperature fields
		if(nbt.hasKey("inputTemperatureK")) {
			this.inputTemperatureK = nbt.getDouble("inputTemperatureK");
			this.outputTemperatureK = nbt.getDouble("outputTemperatureK");
			this.currentTemperatureK = nbt.getDouble("currentTemperatureK");
		}

	// Read magnetic field status for client rotation
	if(nbt.hasKey("magneticFieldActive")) {
		this.magneticFieldActive = nbt.getBoolean("magneticFieldActive");
	}

		// Read heating power data for GUI
		if(nbt.hasKey("nbiPowerReceived")) {
			this.nbiPowerReceived = nbt.getLong("nbiPowerReceived");
			this.icrfPowerReceived = nbt.getLong("icrfPowerReceived");
			this.ecrfPowerReceived = nbt.getLong("ecrfPowerReceived");
			this.heatingPower = nbt.getDouble("heatingPower");
		}

		// Sync fluid tanks to client
		for(int i = 0; i < 4; i++) {
			if(nbt.hasKey("tank" + i + "_fluid")) {
				String fluidName = nbt.getString("tank" + i + "_fluid");
				int amount = nbt.getInteger("tank" + i + "_amount");
				net.minecraftforge.fluids.Fluid fluid = FluidRegistry.getFluid(fluidName);
				if(fluid != null && amount > 0) {
					tanks[i].setFluid(new FluidStack(fluid, amount));
				} else {
					tanks[i].setFluid(null);
				}
			} else {
				tanks[i].setFluid(null);
			}
		}
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);

		for(int i = 0; i < 4; i++) {
			if(tanks[i].getFluid() != null) {
				nbt.setTag("ft" + i, tanks[i].writeToNBT(new NBTTagCompound()));
			}
		}

		this.fusionModule.writeToNBT(nbt);

		// Save fuel rate
		nbt.setInteger("fuelRate", fuelRate);

		// Save plasma state
		nbt.setDouble("plasmaTi", plasmaState.temperatureIon);
		nbt.setDouble("plasmaTe", plasmaState.temperatureElectron);
		nbt.setDouble("plasmaDensity", plasmaState.density);
		nbt.setDouble("plasmaB", plasmaState.magneticField);
		nbt.setDouble("qValue", qValue);
		nbt.setDouble("fusionPower", fusionPower);
		nbt.setDouble("heatingPower", heatingPower);
		nbt.setDouble("confinementTime", confinementTime);

		// Save magnetic confinement and disruption state
		nbt.setBoolean("magneticFieldActive", magneticFieldActive);
		nbt.setDouble("actualMagneticField", actualMagneticField);
		nbt.setBoolean("disruptionActive", disruptionActive);
		nbt.setDouble("disruptionTime", disruptionTime);
		nbt.setDouble("plasmaCurrent", plasmaCurrent);

		return nbt;
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);

		for(int i = 0; i < 4; i++) {
			if(nbt.hasKey("ft" + i)) {
				tanks[i].readFromNBT(nbt.getCompoundTag("ft" + i));
			}
		}

		this.fusionModule.readFromNBT(nbt);

		// Load fuel rate (default to 1 if not present)
		if(nbt.hasKey("fuelRate")) {
			this.fuelRate = nbt.getInteger("fuelRate");
			// Validate range
			this.fuelRate = Math.max(MIN_FUEL_RATE, Math.min(MAX_FUEL_RATE, this.fuelRate));
		} else {
			this.fuelRate = 1;
		}

		// Load plasma state
		if(nbt.hasKey("plasmaTi")) {
			plasmaState.temperatureIon = nbt.getDouble("plasmaTi");
			plasmaState.temperatureElectron = nbt.getDouble("plasmaTe");
			plasmaState.density = nbt.getDouble("plasmaDensity");
			plasmaState.magneticField = nbt.getDouble("plasmaB");
			plasmaState.pressure = plasmaState.calculatePressure();
			plasmaState.plasmaBeta = plasmaState.calculateBeta();
		}

		if(nbt.hasKey("qValue")) {
			this.qValue = nbt.getDouble("qValue");
			this.fusionPower = nbt.getDouble("fusionPower");
			this.heatingPower = nbt.getDouble("heatingPower");
			this.confinementTime = nbt.getDouble("confinementTime");
		}

		// Load magnetic confinement and disruption state
		if(nbt.hasKey("magneticFieldActive")) {
			this.magneticFieldActive = nbt.getBoolean("magneticFieldActive");
			this.actualMagneticField = nbt.getDouble("actualMagneticField");
			this.disruptionActive = nbt.getBoolean("disruptionActive");
			this.disruptionTime = nbt.getDouble("disruptionTime");
			this.plasmaCurrent = nbt.getDouble("plasmaCurrent");
		}
	}

	// ===== Client Sync =====

	@Override
	public NBTTagCompound getUpdateTag() {
		NBTTagCompound nbt = super.getUpdateTag();
		this.fusionModule.writeToNBT(nbt);

		// Add fluid tank data for client sync
		for(int i = 0; i < 4; i++) {
			FluidStack fluid = tanks[i].getFluid();
			if(fluid != null && fluid.amount > 0) {
				nbt.setString("tank" + i + "_fluid", fluid.getFluid().getName());
				nbt.setInteger("tank" + i + "_amount", fluid.amount);
			}
		}

		return nbt;
	}

	@Override
	public net.minecraft.network.play.server.SPacketUpdateTileEntity getUpdatePacket() {
		NBTTagCompound nbt = new NBTTagCompound();
		this.fusionModule.writeToNBT(nbt);

		// Add fluid tank data for client sync
		for(int i = 0; i < 4; i++) {
			FluidStack fluid = tanks[i].getFluid();
			if(fluid != null && fluid.amount > 0) {
				nbt.setString("tank" + i + "_fluid", fluid.getFluid().getName());
				nbt.setInteger("tank" + i + "_amount", fluid.amount);
			}
		}

		return new net.minecraft.network.play.server.SPacketUpdateTileEntity(this.pos, 0, nbt);
	}

	@Override
	public void onDataPacket(net.minecraft.network.NetworkManager netManager, net.minecraft.network.play.server.SPacketUpdateTileEntity pkt) {
		NBTTagCompound nbt = pkt.getNbtCompound();
		this.fusionModule.readFromNBT(nbt);

		// Read fluid tank data from packet
		for(int i = 0; i < 4; i++) {
			if(nbt.hasKey("tank" + i + "_fluid")) {
				String fluidName = nbt.getString("tank" + i + "_fluid");
				int amount = nbt.getInteger("tank" + i + "_amount");
				net.minecraftforge.fluids.Fluid fluid = FluidRegistry.getFluid(fluidName);
				if(fluid != null && amount > 0) {
					tanks[i].setFluid(new FluidStack(fluid, amount));
				} else {
					tanks[i].setFluid(null);
				}
			} else {
				tanks[i].setFluid(null);
			}
		}
	}

	// ===== TileEntityCooledBase Implementation =====

	@Override
	public DirPos[] getConPos() {
		// 26 connection points around the torus structure for fluid/power transfer
		// Based on sample implementation with all connection points at bottom (y-1) and top (y+5)
		return new DirPos[] {
			// Center top and bottom
			new DirPos(pos.add(0, -1, 0), EnumFacing.DOWN),
			new DirPos(pos.add(0, 5, 0), EnumFacing.UP),

			// Right side (+X direction) - 6 connection points
			new DirPos(pos.add(6, -1, 0), EnumFacing.DOWN),
			new DirPos(pos.add(6, 5, 0), EnumFacing.UP),
			new DirPos(pos.add(6, -1, 2), EnumFacing.DOWN),
			new DirPos(pos.add(6, 5, 2), EnumFacing.UP),
			new DirPos(pos.add(6, -1, -2), EnumFacing.DOWN),
			new DirPos(pos.add(6, 5, -2), EnumFacing.UP),

			// Left side (-X direction) - 6 connection points
			new DirPos(pos.add(-6, -1, 0), EnumFacing.DOWN),
			new DirPos(pos.add(-6, 5, 0), EnumFacing.UP),
			new DirPos(pos.add(-6, -1, 2), EnumFacing.DOWN),
			new DirPos(pos.add(-6, 5, 2), EnumFacing.UP),
			new DirPos(pos.add(-6, -1, -2), EnumFacing.DOWN),
			new DirPos(pos.add(-6, 5, -2), EnumFacing.UP),

			// Front side (+Z direction) - 6 connection points
			new DirPos(pos.add(0, -1, 6), EnumFacing.DOWN),
			new DirPos(pos.add(0, 5, 6), EnumFacing.UP),
			new DirPos(pos.add(2, -1, 6), EnumFacing.DOWN),
			new DirPos(pos.add(2, 5, 6), EnumFacing.UP),
			new DirPos(pos.add(-2, -1, 6), EnumFacing.DOWN),
			new DirPos(pos.add(-2, 5, 6), EnumFacing.UP),

			// Back side (-Z direction) - 6 connection points
			new DirPos(pos.add(0, -1, -6), EnumFacing.DOWN),
			new DirPos(pos.add(0, 5, -6), EnumFacing.UP),
			new DirPos(pos.add(2, -1, -6), EnumFacing.DOWN),
			new DirPos(pos.add(2, 5, -6), EnumFacing.UP),
			new DirPos(pos.add(-2, -1, -6), EnumFacing.DOWN),
			new DirPos(pos.add(-2, 5, -6), EnumFacing.UP)
		};
	}

	@Override
	public long getMaxPower() {
		return MAX_POWER;
	}


	// ===== IControlReceiver Implementation =====

	@Override
	public boolean hasPermission(EntityPlayer player) {
		return this.isUseableByPlayer(player);
	}

	@Override
	public void receiveControl(NBTTagCompound data) {
		if(data.hasKey("index") && data.hasKey("selection")) {
			int index = data.getInteger("index");
			String selection = data.getString("selection");
			if(index == 0) {
				// Clear tanks when recipe changes to avoid fluid type conflicts
				String oldRecipe = this.fusionModule.recipe;
				if(!selection.equals(oldRecipe)) {
					System.out.println("[FusionTorus] Recipe changed from " + oldRecipe + " to " + selection + ", clearing tanks");
					for(int i = 0; i < 4; i++) {
						if(tanks[i].getFluidAmount() > 0) {
							System.out.println("[FusionTorus]   Clearing tank " + i + ": " + tanks[i].getFluid().getFluid().getName() + " x" + tanks[i].getFluidAmount());
							tanks[i].setFluid(null);
						}
					}
				}

				this.fusionModule.recipe = selection;
				this.markDirty();
				// Sync to client
				if(!world.isRemote) {
					world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
				}
			}
		}

		// Receive fuel rate from GUI input box
		if(data.hasKey("fuelRate")) {
			try {
				int newFuelRate = data.getInteger("fuelRate");
				// Validate range: 1-9998
				newFuelRate = Math.max(MIN_FUEL_RATE, Math.min(MAX_FUEL_RATE, newFuelRate));
				this.fuelRate = newFuelRate;
				System.out.println("[FusionTorus] Fuel rate set to: " + this.fuelRate);
				this.markDirty();
				// Sync to client
				if(!world.isRemote) {
					world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
				}
			} catch(NumberFormatException e) {
				System.out.println("[FusionTorus] Invalid fuel rate format: " + data.getString("fuelRate"));
			}
		}
	}

	// ===== IFluidHandler Override for Fuel Tanks =====

	@Override
	public net.minecraftforge.fluids.capability.IFluidTankProperties[] getTankProperties() {
		System.out.println("[FusionTorus] getTankProperties() called");

		// Get coolant tank properties from parent
		net.minecraftforge.fluids.capability.IFluidTankProperties[] coolantProps = super.getTankProperties();

		// Add fuel tank properties
		// Use FluidTank's default getTankProperties() implementation
		// This reports all tanks as fillable and drainable
		// Actual input/output control is done in fill() and drain() methods
		net.minecraftforge.fluids.capability.IFluidTankProperties[] allProps = new net.minecraftforge.fluids.capability.IFluidTankProperties[6]; // 2 coolant + 4 fuel

		// Copy coolant properties
		allProps[0] = coolantProps[0];
		allProps[1] = coolantProps[1];

		// Add fuel tank properties using default implementation
		for(int i = 0; i < 4; i++) {
			allProps[i + 2] = tanks[i].getTankProperties()[0];
		}

		return allProps;
	}

	@Override
	public int fill(FluidStack resource, boolean doFill) {
		if(resource == null) {
			System.out.println("[FusionTorus] fill() called with null resource");
			return 0;
		}

		System.out.println("[FusionTorus] fill() called with: " + resource.getFluid().getName() + " x" + resource.amount + ", doFill=" + doFill);
		System.out.println("[FusionTorus] Current recipe: " + this.fusionModule.recipe);

		// Check if it's coolant
		if(resource.getFluid() == ModForgeFluids.PERFLUOROMETHYL_COLD) {
			System.out.println("[FusionTorus] Filling coolant via super.fill()");
			return super.fill(resource, doFill);
		}

		// Check if it's a recipe fluid
		FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipe(this.fusionModule.recipe);
		System.out.println("[FusionTorus] Recipe lookup result: " + (recipe != null ? recipe.name : "null"));

		if(recipe == null) {
			System.out.println("[FusionTorus] Recipe is null, cannot accept fluid");
			return 0;
		}

		if(recipe.inputFluids == null) {
			System.out.println("[FusionTorus] Recipe inputFluids is null, cannot accept fluid");
			return 0;
		}

		System.out.println("[FusionTorus] Recipe has " + recipe.inputFluids.length + " input fluids");
		for(int j = 0; j < recipe.inputFluids.length; j++) {
			if(recipe.inputFluids[j] != null) {
				System.out.println("[FusionTorus]   inputFluid[" + j + "] = " + recipe.inputFluids[j].getName());
			}
		}

		// Try to fill appropriate tank based on recipe
		for(int i = 0; i < 3; i++) { // Only input tanks (0-2)
			if(recipe.inputFluids.length > i && recipe.inputFluids[i] != null && recipe.inputFluids[i] == resource.getFluid()) {
				System.out.println("[FusionTorus] Matched tank " + i + ", attempting fill");
				System.out.println("[FusionTorus]   Tank capacity: " + tanks[i].getCapacity());
				System.out.println("[FusionTorus]   Tank current fluid: " + (tanks[i].getFluid() == null ? "null" : tanks[i].getFluid().getFluid().getName()));
				System.out.println("[FusionTorus]   Tank current amount: " + tanks[i].getFluidAmount());
				System.out.println("[FusionTorus]   Resource: " + resource.getFluid().getName() + " x" + resource.amount);

				// Clear tank if it contains wrong fluid for current recipe
				FluidStack currentFluid = tanks[i].getFluid();
				if(currentFluid != null && currentFluid.getFluid() != resource.getFluid()) {
					System.out.println("[FusionTorus]   Tank contains wrong fluid, clearing: " + currentFluid.getFluid().getName());
					tanks[i].setFluid(null);
				}

				int filled = tanks[i].fill(resource, doFill);
				System.out.println("[FusionTorus] Filled " + filled + " mB into tank " + i);
				if(filled > 0 && doFill) {
					this.markDirty();
				}
				return filled;
			}
		}

		System.out.println("[FusionTorus] No matching tank found for fluid: " + resource.getFluid().getName());
		return 0;
	}

	@Override
	public FluidStack drain(FluidStack resource, boolean doDrain) {
		if(resource == null) return null;

		System.out.println("[FusionTorus] drain(FluidStack) called: fluid=" + resource.getFluid().getName() + ", amount=" + resource.amount + ", doDrain=" + doDrain);

		// Check if it's hot coolant
		if(resource.getFluid() == ModForgeFluids.PERFLUOROMETHYL_HOT) {
			FluidStack drained = super.drain(resource, doDrain);
			System.out.println("[FusionTorus] Drained hot coolant: " + (drained != null ? drained.amount + " mB" : "null"));
			return drained;
		}

		// Check if it's output fluid
		FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipe(this.fusionModule.recipe);
		if(recipe != null && recipe.outputFluid != null && recipe.outputFluid == resource.getFluid()) {
			FluidStack drained = tanks[3].drain(resource.amount, doDrain);
			System.out.println("[FusionTorus] Drained output fluid: " + (drained != null ? drained.amount + " mB" : "null"));
			return drained;
		}

		System.out.println("[FusionTorus] No matching tank for drain");
		return null;
	}

	@Override
	public FluidStack drain(int maxDrain, boolean doDrain) {
		System.out.println("[FusionTorus] drain(int) called: maxDrain=" + maxDrain + ", doDrain=" + doDrain);
		System.out.println("[FusionTorus] Coolant tank 1 amount: " + coolantTanks[1].getFluidAmount() + " mB");
		System.out.println("[FusionTorus] Output tank 3 amount: " + tanks[3].getFluidAmount() + " mB");

		// Try draining hot coolant first
		FluidStack coolantDrain = super.drain(maxDrain, doDrain);
		if(coolantDrain != null) {
			System.out.println("[FusionTorus] Drained hot coolant: " + coolantDrain.amount + " mB");
			return coolantDrain;
		}

		// Try draining output tank
		FluidStack outputDrain = tanks[3].drain(maxDrain, doDrain);
		if(outputDrain != null) {
			System.out.println("[FusionTorus] Drained output: " + outputDrain.amount + " mB");
		} else {
			System.out.println("[FusionTorus] No fluid to drain");
		}
		return outputDrain;
	}

	// ===== Capability Override for Direction-Based Fluid Handling =====

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, EnumFacing facing) {
		if(capability == net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
			// Return a wrapper that handles both input and output based on the fluid type
			return (T) new net.minecraftforge.fluids.capability.IFluidHandler() {
				@Override
				public net.minecraftforge.fluids.capability.IFluidTankProperties[] getTankProperties() {
					return TileEntityFusionTorus.this.getTankProperties();
				}
				@Override
				public int fill(FluidStack resource, boolean doFill) {
					return TileEntityFusionTorus.this.fill(resource, doFill);
				}
				@Override
				public FluidStack drain(FluidStack resource, boolean doDrain) {
					return TileEntityFusionTorus.this.drain(resource, doDrain);
				}
				@Override
				public FluidStack drain(int maxDrain, boolean doDrain) {
					return TileEntityFusionTorus.this.drain(maxDrain, doDrain);
				}
			};
		}
		return super.getCapability(capability, facing);
	}

	// ===== Rendering =====

	private AxisAlignedBB bb = null;

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		if(bb == null) {
			bb = new AxisAlignedBB(
				pos.getX() - 8,
				pos.getY(),
				pos.getZ() - 8,
				pos.getX() + 9,
				pos.getY() + 5,
				pos.getZ() + 9
			);
		}
		return bb;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public double getMaxRenderDistanceSquared() {
		return 65536.0D;
	}

	// ===== DISRUPTION HANDLING METHODS =====

	/**
	 * Trigger plasma disruption event.
	 * Called when magnetic field is lost or instability limits are exceeded.
	 */
	private void triggerDisruption() {
		if(!disruptionActive) {
			System.out.println("[FusionTorus] ===== PLASMA DISRUPTION TRIGGERED =====");
			disruptionActive = true;
			disruptionTime = 0.0;

			// Log disruption cause
			double avgTemp = (plasmaState.temperatureIon + plasmaState.temperatureElectron) / 2.0;
			System.out.println("[FusionTorus] Plasma temperature: " + String.format("%.2e K", avgTemp));
			System.out.println("[FusionTorus] Magnetic field: " + String.format("%.2f T", actualMagneticField));
			System.out.println("[FusionTorus] Plasma beta: " + String.format("%.3f%%", plasmaState.plasmaBeta * 100));
			System.out.println("[FusionTorus] Plasma density: " + String.format("%.2e m^-3", plasmaState.density));

			// Calculate disruption severity
			double severity = PlasmaDisruption.calculateDisruptionSeverity(plasmaState, plasmaVolume);
			System.out.println("[FusionTorus] Disruption severity: " + String.format("%.1f%%", severity * 100));

			// Calculate wall heat load
			double wallArea = 2.0 * Math.PI * PLASMA_MAJOR_RADIUS * 2.0 * Math.PI * PLASMA_RADIUS; // Torus surface area (approx)
			double heatLoad = PlasmaDisruption.calculateWallHeatLoad(plasmaState, plasmaVolume, wallArea);
			System.out.println("[FusionTorus] Wall heat load: " + String.format("%.1f MJ/m²", heatLoad));

			// Turn off fusion immediately
			this.fusionPower = 0.0;
			this.didProcess = false;
		}
	}

	/**
	 * Update disruption state - execute thermal and current quench.
	 * @param deltaTime Time step (seconds, typically 0.05 for 1 tick)
	 */
	private void updateDisruption(double deltaTime) {
		if(!disruptionActive) return;

		// Execute thermal quench (temperature collapse)
		PlasmaDisruption.executeThermalQuench(plasmaState, deltaTime);

		// Execute current quench (plasma current decay)
		plasmaCurrent = PlasmaDisruption.executeCurrentQuench(plasmaCurrent, deltaTime);

		// Update disruption timer
		disruptionTime += deltaTime;

		// Log progress every 100ms
		if(Math.abs(disruptionTime % 0.1) < deltaTime) {
			double avgTemp = (plasmaState.temperatureIon + plasmaState.temperatureElectron) / 2.0;
			System.out.println("[FusionTorus] Disruption progress: t=" +
			                   String.format("%.3f s", disruptionTime) +
			                   ", T=" + String.format("%.2e K", avgTemp) +
			                   ", Ip=" + String.format("%.2f MA", plasmaCurrent));
		}

		// Check if disruption is complete
		if(PlasmaDisruption.isDisruptionComplete(plasmaState, plasmaCurrent)) {
			disruptionActive = false;
			disruptionTime = 0.0;  // Reset disruption timer
			System.out.println("[FusionTorus] ===== DISRUPTION COMPLETE =====");
			System.out.println("[FusionTorus] Plasma extinguished. Duration: " + String.format("%.3f s", disruptionTime));
			System.out.println("[FusionTorus] Final temperature: " +
			                   String.format("%.1f K", (plasmaState.temperatureIon + plasmaState.temperatureElectron) / 2.0));

			// Reset plasma state to COLD PLASMA (same as constructor)
			// All parameters must be reset to prevent immediate re-disruption
			plasmaState.temperatureIon = 1.0e4;   // 10,000 K (cold plasma, reheatable)
			plasmaState.temperatureElectron = 1.0e4;
			plasmaState.density = 1.0e19;         // Reset to initial density (10^19 /m³)
			plasmaState.magneticField = actualMagneticField;  // Update to current field
			plasmaCurrent = 15.0;  // Reset plasma current to ITER design value (MA)
			plasmaState.pressure = plasmaState.calculatePressure();
			plasmaState.plasmaBeta = plasmaState.calculateBeta();

			// Reset fuel rate to default to prevent immediate Greenwald limit violation
			this.fuelRate = MIN_FUEL_RATE;
			this.previousFuelRate = MIN_FUEL_RATE;

			// Set cooldown period to prevent immediate re-disruption
			// 5 seconds (100 ticks) grace period for user to adjust parameters
			disruptionCooldown = 100;

			System.out.println("[FusionTorus] Plasma reset to cold state (10,000 K, n=10^19 /m³). Ready for re-ignition.");
			System.out.println("[FusionTorus] Fuel rate reset to " + this.fuelRate + " to prevent immediate disruption.");
			System.out.println("[FusionTorus] Disruption cooldown set to " + disruptionCooldown + " ticks (5 seconds).");
		}
	}
}
