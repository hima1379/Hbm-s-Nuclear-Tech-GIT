package com.hbm.main.tileentity.machine.fusion;

import com.hbm.forgefluid.ModForgeFluids;
import com.hbm.main.tileentity.TileEntityLoadedBase;
import com.hbm.physics.fusion.FusionCrossSections;
import com.hbm.uninos.DirPos;
import com.hbm.uninos.GenNode;
import com.hbm.uninos.UniNodespace;
import com.hbm.uninos.networkproviders.PlasmaNetworkProvider;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Fusion Boiler (Breeding Blanket) - Realistic ITER-class fusion reactor heat extraction system.
 *
 * ## Physics Model:
 * Based on real fusion reactor breeding blanket designs (ITER, DEMO, SPARC).
 *
 * ### Neutron Energy Deposition:
 * - D-T fusion produces 14.1 MeV neutrons (80% of fusion energy)
 * - Neutrons penetrate ~1m thick lithium blanket
 * - Li-6 + n → T + He + 4.8 MeV (tritium breeding + additional heat)
 * - Total deposited energy: 18.9 MeV per neutron
 *
 * ### Heat Transfer Chain:
 * 1. Neutron kinetic energy → Lithium blanket (thermal energy)
 * 2. Blanket → Coolant (water/helium) via heat exchanger (85% efficiency)
 * 3. Coolant → Steam generation
 *
 * ### Steam Production:
 * - Operating pressure: 15.5 MPa (water-cooled) or 8 MPa (helium-cooled)
 * - Temperature range: 300°C - 600°C
 * - UltraHotSteam (600°C+): Supercritical steam for maximum efficiency (~45%)
 *
 * ### References:
 * - ITER Breeding Blanket Test Program
 * - EUROfusion DEMO design studies
 * - World Nuclear Association fusion reactor specifications
 *
 * @author Adapted for 1.12.2 with realistic fusion physics
 */
public class TileEntityFusionBoiler extends TileEntityLoadedBase implements IFusionPowerReceiver, ITickable, IFluidHandler {

	protected GenNode plasmaNode;
	public long plasmaEnergy;
	public long plasmaEnergySync; // For client synchronization
	public FluidTank[] tanks;

	// Thermodynamic fields
	public double boilerTemperature;    // Boiler coolant temperature (K)
	public double thermalPower;         // Current thermal power (W)
	public double waterMass;            // Mass of water in boiler (kg)

	// Thermodynamic constants (ITER-realistic values)
	public static final double WATER_HEAT_CAPACITY = 4186.0;     // J/(kg·K) - liquid water
	public static final double WATER_LATENT_HEAT = 2.257e6;      // J/kg - latent heat of vaporization
	public static final double BOILING_POINT = 373.15;           // K (100°C at 1 atm)
	public static final double BOILER_MASS = 10000.0;            // kg (10 tons baseline - overridden by water content)
	public static final double HEAT_TRANSFER_EFFICIENCY = 0.85;  // 85% efficiency (realistic for blanket → coolant)

	// Nuclear constants for D-T fusion blanket
	public static final double NEUTRON_ENERGY_MEV = 14.1;        // MeV per D-T neutron
	public static final double LITHIUM_BREEDING_ENERGY_MEV = 4.8; // MeV from Li-6 + n → T + He
	public static final double TOTAL_NEUTRON_ENERGY_MEV = NEUTRON_ENERGY_MEV + LITHIUM_BREEDING_ENERGY_MEV; // 18.9 MeV total
	public static final double MEV_TO_JOULES = 1.602176634e-13;  // Conversion factor

	public TileEntityFusionBoiler() {
		this.tanks = new FluidTank[2];
		this.tanks[0] = new FluidTank(256_000_000); // Water input (256 buckets for high-rate operation)
		this.tanks[1] = new FluidTank(256_000_000); // Steam output

		// Initialize boiler temperature to room temperature
		this.boilerTemperature = 293.15; // 20°C
		this.thermalPower = 0.0;
		this.waterMass = BOILER_MASS;
	}

	@Override
	public void update() {
		if(!world.isRemote) {
			// Debug logging every second
			if(world.getTotalWorldTime() % 20 == 0) {
				System.out.println("[FusionBoiler] ===== UPDATE =====");
				System.out.println("[FusionBoiler] Position: " + pos);
				System.out.println("[FusionBoiler] PlasmaEnergy: " + this.plasmaEnergy + " TU");
				System.out.println("[FusionBoiler] Water tank: " + tanks[0].getFluidAmount() + "/" + tanks[0].getCapacity() + " mB");
				System.out.println("[FusionBoiler] Steam tank: " + tanks[1].getFluidAmount() + "/" + tanks[1].getCapacity() + " mB");
				System.out.println("[FusionBoiler] PlasmaNode valid: " + (plasmaNode != null && plasmaNode.hasValidNet()));
			}

			// Save energy for sync and reset
			this.plasmaEnergySync = this.plasmaEnergy;
			this.plasmaEnergy = 0;

			if(plasmaNode == null || plasmaNode.expired) {
				EnumFacing dir = EnumFacing.byIndex(this.getBlockMetadata() - 10).getOpposite();
				int nodeX = pos.getX() + dir.getXOffset() * 4;
				int nodeY = pos.getY() + 2;
				int nodeZ = pos.getZ() + dir.getZOffset() * 4;

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
					System.out.println("[FusionBoiler] Created plasma node at " + nodePos);
				}
			}

			if(plasmaNode != null && plasmaNode.hasValidNet()) {
				plasmaNode.net.addReceiver(this);
			}

			// Send output steam to adjacent blocks via connection points
			// For fusion-scale steam production, send maximum amount per tick
			for(DirPos conPos : getConPos()) {
				if(tanks[1].getFluidAmount() > 0) {
					BlockPos targetPos = conPos.getPos().offset(conPos.getDir());
					com.hbm.forgefluid.FFUtils.fillFluid(this, tanks[1], world, targetPos, Integer.MAX_VALUE);
				}
			}

			// Sync to client every 50 ticks
			if(world.getTotalWorldTime() % 50 == 0) {
				this.markDirty();
				world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
			}
		}
	}

	@Override
	public boolean receivesFusionPower() {
		return true;
	}

	@Override
	public void receiveFusionPower(double thermalPowerWatts, double plasmaTemperatureK, double neutronFlux) {
		// NEW: Receive physics-based values from Torus
		// - thermalPowerWatts: Fusion thermal power (W)
		// - plasmaTemperatureK: Plasma temperature (K) - could be used for advanced heat transfer calculations
		// - neutronFlux: Neutron flux for breeding blanket calculations

		// Store for legacy compatibility (some code might still use plasmaEnergy for display)
		this.plasmaEnergy = (long)(thermalPowerWatts / 1.0e6); // W to approximate TU for display

		System.out.println("[FusionBoiler] ***** receiveFusionPower CALLED *****");
		System.out.println("[FusionBoiler] Received thermalPower: " + String.format("%.2e W (%.1f MW)", thermalPowerWatts, thermalPowerWatts/1e6));
		System.out.println("[FusionBoiler] Plasma temperature: " + String.format("%.2e K (%.0f MK)", plasmaTemperatureK, plasmaTemperatureK/1e6));
		System.out.println("[FusionBoiler] Received neutronFlux: " + neutronFlux);

		// Get water from tank
		FluidStack waterFluid = tanks[0].getFluid();
		if(waterFluid == null || waterFluid.amount <= 0) {
			System.out.println("[FusionBoiler] No water in tank, cooling down");
			// Natural cooling if no water
			coolDown(0.05); // 1 tick = 0.05 seconds
			return;
		}

		System.out.println("[FusionBoiler] Water in tank: " + waterFluid.amount + " mB");

		// Only accept water in input tank
		if(waterFluid.getFluid() != FluidRegistry.WATER) {
			System.out.println("[FusionBoiler] ERROR: Input is not water! fluid=" + waterFluid.getFluid().getName());
			return;
		}

		// ===== PHYSICS-BASED THERMODYNAMICS (ITER-REALISTIC) =====
		// Use the same physics as TileEntityFusionTorus - NO TU conversion!

		// Convert neutron flux from scaled value back to n/s
		// (Torus scales it down by 1e15 for transmission: line 507)
		double actualNeutronFlux = neutronFlux * 1.0e15; // neutrons/s

		// Each D-T neutron carries energy that gets deposited in lithium blanket:
		// 1. Neutron kinetic energy: 14.1 MeV
		// 2. Li-6 + n → T + He + 4.8 MeV (tritium breeding reaction)
		// Total energy per neutron: 18.9 MeV
		double neutronEnergyMeV = TOTAL_NEUTRON_ENERGY_MEV; // 18.9 MeV
		double neutronPowerWatts = actualNeutronFlux * neutronEnergyMeV * MEV_TO_JOULES;

		// Apply heat transfer efficiency (neutrons → blanket → coolant → water)
		// Blanket captures: ~95%, Heat exchanger efficiency: ~90%
		// Combined: 0.95 × 0.90 = 0.855 ≈ 85%
		this.thermalPower = neutronPowerWatts * HEAT_TRANSFER_EFFICIENCY;

		System.out.println("[FusionBoiler] ===== BLANKET THERMODYNAMICS (Physics-based, NO TU) =====");
		System.out.println("[FusionBoiler] Neutron flux: " + String.format("%.2e n/s", actualNeutronFlux));
		System.out.println("[FusionBoiler] Neutron energy: " + neutronEnergyMeV + " MeV/neutron");
		System.out.println("[FusionBoiler] Neutron power (raw): " + String.format("%.2e W (%.1f GW)", neutronPowerWatts, neutronPowerWatts/1e9));
		System.out.println("[FusionBoiler] Thermal power (after " + String.format("%.0f%%", HEAT_TRANSFER_EFFICIENCY*100) + " efficiency): " + String.format("%.2e W (%.1f GW)", thermalPower, thermalPower/1e9));

		// Update boiler mass based on water content (dynamic mass)
		// 1 mB water = 1 mL water = 1 g water = 0.001 kg water
		// Boiler structure mass: 15 tons (15,000 kg) - realistic for large fusion blanket module
		double waterMassKg = waterFluid.amount * 0.001; // mB → kg
		double structureMass = 15000.0; // kg (15 tons)
		this.waterMass = structureMass + waterMassKg;

		System.out.println("[FusionBoiler] Water in tank: " + waterFluid.amount + " mB = " + String.format("%.1f kg", waterMassKg));
		System.out.println("[FusionBoiler] Total boiler mass: " + String.format("%.1f kg (structure: %.1f kg + water: %.1f kg)", waterMass, structureMass, waterMassKg));

		// Update boiler temperature (1 tick = 0.05 seconds)
		double deltaTime = 0.05;
		System.out.println("[FusionBoiler] ***** CALLING updateTemperature() *****");
		System.out.println("[FusionBoiler] Before: boilerTemperature = " + String.format("%.1f K (%.1f°C)", boilerTemperature, boilerTemperature - 273.15));

		updateTemperature(thermalPower, deltaTime);

		System.out.println("[FusionBoiler] After: boilerTemperature = " + String.format("%.1f K (%.1f°C)", boilerTemperature, boilerTemperature - 273.15));

		// Convert water to steam based on temperature
		System.out.println("[FusionBoiler] Checking steam conversion: boilerTemp=" + String.format("%.1f K", boilerTemperature) + ", boilingPoint=" + String.format("%.1f K", BOILING_POINT) + ", canConvert=" + (boilerTemperature >= BOILING_POINT));

		if(boilerTemperature >= BOILING_POINT) {
			System.out.println("[FusionBoiler] ***** CALLING convertWaterToSteam() *****");
			convertWaterToSteam(deltaTime);
		} else {
			System.out.println("[FusionBoiler] Temperature too low for steam production (need " + String.format("%.0f°C", BOILING_POINT - 273.15) + ")");
		}

		// Play boiler sound if operating
		if(thermalPower > 1e6 && world.rand.nextInt(200) == 0) {
			world.playSound(null, pos,
				new SoundEvent(new net.minecraft.util.ResourceLocation("block.lava.pop")),
				SoundCategory.BLOCKS, 2.5F, 1.0F);
		}
	}

	/**
	 * Update boiler temperature based on thermal power input
	 *
	 * @param thermalPower Thermal power (W)
	 * @param deltaTime Time step (s)
	 */
	private void updateTemperature(double thermalPower, double deltaTime) {
		if(waterMass <= 0) return;

		// Heat energy added: Q = P × Δt
		double heatAdded = thermalPower * deltaTime;

		// Temperature change: ΔT = Q / (m × c_p)
		double deltaT = heatAdded / (waterMass * WATER_HEAT_CAPACITY);

		// Update temperature
		boilerTemperature += deltaT;

		// Passive cooling loss (radiation + conduction)
		// Loss rate proportional to temperature difference
		double ambientTemp = 293.15; // 20°C
		double coolingRate = 5000.0; // W/K (heat loss coefficient) - realistic for large blanket
		double coolingPower = coolingRate * (boilerTemperature - ambientTemp);
		double coolingDeltaT = coolingPower * deltaTime / (waterMass * WATER_HEAT_CAPACITY);

		boilerTemperature -= coolingDeltaT;

		// Clamp temperature to reasonable range
		boilerTemperature = Math.max(ambientTemp, Math.min(1273.15, boilerTemperature)); // 20°C to 1000°C

		System.out.println("[FusionBoiler] updateTemperature(): heatAdded=" + String.format("%.1f J", heatAdded) +
			", deltaT_heating=" + String.format("%.2f K", deltaT) +
			", coolingPower=" + String.format("%.1f W", coolingPower) +
			", deltaT_cooling=" + String.format("%.2f K", coolingDeltaT) +
			", netChange=" + String.format("%.2f K", deltaT - coolingDeltaT));
	}

	/**
	 * Natural cooling when no heat input
	 *
	 * @param deltaTime Time step (s)
	 */
	private void coolDown(double deltaTime) {
		double ambientTemp = 293.15; // 20°C
		double coolingRate = 5000.0; // W/K

		double coolingPower = coolingRate * (boilerTemperature - ambientTemp);
		double coolingDeltaT = coolingPower * deltaTime / (waterMass * WATER_HEAT_CAPACITY);

		boilerTemperature -= coolingDeltaT;
		boilerTemperature = Math.max(ambientTemp, boilerTemperature);

		this.thermalPower = 0.0;
	}

	/**
	 * Convert water to steam based on boiler temperature
	 *
	 * @param deltaTime Time step (s)
	 */
	private void convertWaterToSteam(double deltaTime) {
		System.out.println("[FusionBoiler] ===== convertWaterToSteam() START =====");

		FluidStack waterFluid = tanks[0].getFluid();
		if(waterFluid == null || waterFluid.amount <= 0) {
			System.out.println("[FusionBoiler] No water available for conversion");
			return;
		}

		System.out.println("[FusionBoiler] Water available: " + waterFluid.amount + " mB");

		// Determine steam type based on temperature (fusion blanket cooling system)
		// ITER-class fusion reactors operate at very high temperatures for efficiency
		Fluid steamType;
		double tempCelsius = boilerTemperature - 273.15;

		System.out.println("[FusionBoiler] Temperature: " + String.format("%.1f°C", tempCelsius));

		if(tempCelsius >= 600.0) {
			// ULTRAHOTSTEAM: 600+°C - Supercritical steam (>374°C, >22.1 MPa)
			// Used in advanced power cycles for maximum thermal efficiency (~45%)
			steamType = ModForgeFluids.ULTRAHOTSTEAM;
		} else if(tempCelsius >= 450.0) {
			// SUPERHOTSTEAM: 450-600°C - High-temperature superheated steam
			steamType = ModForgeFluids.SUPERHOTSTEAM;
		} else if(tempCelsius >= 300.0) {
			// HOTSTEAM: 300-450°C - Moderate superheat
			steamType = ModForgeFluids.HOTSTEAM;
		} else if(tempCelsius >= 100.0) {
			// STEAM: 100-300°C - Low-grade steam
			steamType = ModForgeFluids.STEAM;
		} else {
			return; // Below boiling point
		}

		System.out.println("[FusionBoiler] Selected steam type: " + steamType.getName() + " at " + String.format("%.1f°C", tempCelsius));

		// Calculate steam production rate based on available thermal power and temperature
		// Total energy needed per kg:
		// 1. Sensible heat (20°C→100°C): 4186 J/(kg·K) × 80 K = 334,880 J/kg
		// 2. Latent heat (liquid→vapor): 2,257,000 J/kg
		// 3. Superheat (100°C→target): 2000 J/(kg·K) × (T-100) K

		double totalEnergyPerKg;
		if(tempCelsius >= 600.0) {
			// UltraSteam: full energy to 600°C
			totalEnergyPerKg = 334880 + 2257000 + (2000 * 500); // = 3,591,880 J/kg
		} else if(tempCelsius >= 450.0) {
			// SuperHotSteam: to 450°C
			totalEnergyPerKg = 334880 + 2257000 + (2000 * 350); // = 3,291,880 J/kg
		} else if(tempCelsius >= 300.0) {
			// HotSteam: to 300°C
			totalEnergyPerKg = 334880 + 2257000 + (2000 * 200); // = 3,091,880 J/kg
		} else {
			// Normal steam: to 100°C
			totalEnergyPerKg = 334880 + 2257000; // = 2,591,880 J/kg
		}

		// Production rate based on available thermal power
		double productionRate = thermalPower / totalEnergyPerKg; // kg/s
		double waterToConvert = productionRate * deltaTime; // kg per tick

		// Convert kg to mB (1 kg water = 1000 g = 1000 mL = 1000 mB)
		int waterMB = (int) Math.ceil(waterToConvert * 1000.0);

		System.out.println("[FusionBoiler] Thermal power: " + String.format("%.2e W (%.1f GW)", thermalPower, thermalPower/1e9));
		System.out.println("[FusionBoiler] Energy per kg steam: " + String.format("%.2e J/kg", totalEnergyPerKg));
		System.out.println("[FusionBoiler] Production rate: " + String.format("%.2f kg/s (%.0f mB/tick)", productionRate, waterToConvert * 1000.0 / deltaTime));
		System.out.println("[FusionBoiler] Water to convert this tick: " + String.format("%.4f kg = %d mB", waterToConvert, waterMB));

		// Limit by available water and output tank capacity
		int maxInputOps = waterFluid.amount;
		int maxOutputOps = tanks[1].getCapacity() - tanks[1].getFluidAmount();
		int operations = Math.min(waterMB, Math.min(maxInputOps, maxOutputOps));

		System.out.println("[FusionBoiler] Limits: requested=" + waterMB + " mB, available=" + maxInputOps + " mB, outputSpace=" + maxOutputOps + " mB");
		System.out.println("[FusionBoiler] Actual conversion: " + operations + " mB");

		if(operations > 0) {
			// Consume water
			int drained = tanks[0].drain(operations, true).amount;

			// Produce steam
			int filled = tanks[1].fill(new FluidStack(steamType, operations), true);

			System.out.println("[FusionBoiler] ***** CONVERSION SUCCESS! *****");
			System.out.println("[FusionBoiler] Drained " + drained + " mB water");
			System.out.println("[FusionBoiler] Filled " + filled + " mB " + steamType.getName());
			System.out.println("[FusionBoiler] Output tank now: " + tanks[1].getFluidAmount() + "/" + tanks[1].getCapacity() + " mB");
		} else {
			System.out.println("[FusionBoiler] No conversion performed (operations=0)");
		}

		System.out.println("[FusionBoiler] ===== convertWaterToSteam() END =====");
	}

	/**
	 * Returns connection positions for fluid input/output.
	 * 4 connection points around the boiler structure - matches 1.7.10 exactly!
	 */
	public DirPos[] getConPos() {
		EnumFacing dir = EnumFacing.byIndex(this.getBlockMetadata() - 10);
		EnumFacing rot = dir.rotateY();

		return new DirPos[] {
			new DirPos(pos.add(-dir.getXOffset() * 1 + rot.getXOffset() * 2, 0, -dir.getZOffset() * 1 + rot.getZOffset() * 2), rot),
			new DirPos(pos.add(-dir.getXOffset() * 1 - rot.getXOffset() * 2, 0, -dir.getZOffset() * 1 - rot.getZOffset() * 2), rot.getOpposite()),
			new DirPos(pos.add(dir.getXOffset() * 2 + rot.getXOffset() * 2, 0, dir.getZOffset() * 2 + rot.getZOffset() * 2), rot),
			new DirPos(pos.add(dir.getXOffset() * 2 - rot.getXOffset() * 2, 0, dir.getZOffset() * 2 - rot.getZOffset() * 2), rot.getOpposite())
		};
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

	// ===== IFluidHandler Implementation =====

	@Override
	public IFluidTankProperties[] getTankProperties() {
		return new IFluidTankProperties[] {
			tanks[0].getTankProperties()[0],
			tanks[1].getTankProperties()[0]
		};
	}

	@Override
	public int fill(FluidStack resource, boolean doFill) {
		// Only accept water in tank 0
		if(resource == null || resource.getFluid() != FluidRegistry.WATER) {
			return 0;
		}
		int filled = tanks[0].fill(resource, doFill);
		if(filled > 0 && doFill) {
			this.markDirty();
		}
		return filled;
	}

	@Override
	public FluidStack drain(FluidStack resource, boolean doDrain) {
		// Only drain ULTRAHOTSTEAM from tank 1
		if(resource == null || resource.getFluid() != ModForgeFluids.ULTRAHOTSTEAM) {
			return null;
		}
		FluidStack drained = tanks[1].drain(resource.amount, doDrain);
		if(drained != null && drained.amount > 0 && doDrain) {
			this.markDirty();
		}
		return drained;
	}

	@Override
	public FluidStack drain(int maxDrain, boolean doDrain) {
		// Drain ULTRAHOTSTEAM from tank 1
		FluidStack drained = tanks[1].drain(maxDrain, doDrain);
		if(drained != null && drained.amount > 0 && doDrain) {
			this.markDirty();
		}
		return drained;
	}

	// ===== Capability Support =====

	@Override
	public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
		return capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
		if(capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
			return (T) this;
		}
		return super.getCapability(capability, facing);
	}

	// ===== Public accessors for GUI/overlay =====

	public FluidTank[] getAllTanks() {
		return tanks;
	}

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

	// ===== NBT Persistence =====

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);

		// Save tank data
		if(tanks[0].getFluid() != null) {
			NBTTagCompound tank0 = new NBTTagCompound();
			tanks[0].writeToNBT(tank0);
			nbt.setTag("tank0", tank0);
		}

		if(tanks[1].getFluid() != null) {
			NBTTagCompound tank1 = new NBTTagCompound();
			tanks[1].writeToNBT(tank1);
			nbt.setTag("tank1", tank1);
		}

		// Save thermodynamic state
		nbt.setDouble("boilerTemperature", boilerTemperature);
		nbt.setDouble("thermalPower", thermalPower);
		nbt.setDouble("waterMass", waterMass);

		return nbt;
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);

		// Load tank data
		if(nbt.hasKey("tank0")) {
			tanks[0].readFromNBT(nbt.getCompoundTag("tank0"));
		}

		if(nbt.hasKey("tank1")) {
			tanks[1].readFromNBT(nbt.getCompoundTag("tank1"));
		}

		// Load thermodynamic state
		if(nbt.hasKey("boilerTemperature")) {
			this.boilerTemperature = nbt.getDouble("boilerTemperature");
			this.thermalPower = nbt.getDouble("thermalPower");
			this.waterMass = nbt.getDouble("waterMass");
		}
	}

	// ===== Network Synchronization =====

	@Override
	public NBTTagCompound getUpdateTag() {
		NBTTagCompound nbt = super.getUpdateTag();

		// Add plasma energy
		nbt.setLong("plasmaEnergySync", plasmaEnergySync);

		// Add fluid tank data for client sync
		if(tanks[0].getFluid() != null) {
			NBTTagCompound tank0 = new NBTTagCompound();
			tanks[0].writeToNBT(tank0);
			nbt.setTag("tank0", tank0);
		}

		if(tanks[1].getFluid() != null) {
			NBTTagCompound tank1 = new NBTTagCompound();
			tanks[1].writeToNBT(tank1);
			nbt.setTag("tank1", tank1);
		}

		// Add thermodynamic data for client display
		nbt.setDouble("boilerTemperature", boilerTemperature);
		nbt.setDouble("thermalPower", thermalPower);

		return nbt;
	}

	@Override
	public net.minecraft.network.play.server.SPacketUpdateTileEntity getUpdatePacket() {
		NBTTagCompound nbt = new NBTTagCompound();

		// Add plasma energy
		nbt.setLong("plasmaEnergySync", plasmaEnergySync);

		// Add fluid tank data for client sync
		if(tanks[0].getFluid() != null) {
			NBTTagCompound tank0 = new NBTTagCompound();
			tanks[0].writeToNBT(tank0);
			nbt.setTag("tank0", tank0);
		}

		if(tanks[1].getFluid() != null) {
			NBTTagCompound tank1 = new NBTTagCompound();
			tanks[1].writeToNBT(tank1);
			nbt.setTag("tank1", tank1);
		}

		// Add thermodynamic data for client display
		nbt.setDouble("boilerTemperature", boilerTemperature);
		nbt.setDouble("thermalPower", thermalPower);

		return new net.minecraft.network.play.server.SPacketUpdateTileEntity(pos, 0, nbt);
	}

	@Override
	public void onDataPacket(net.minecraft.network.NetworkManager netManager, net.minecraft.network.play.server.SPacketUpdateTileEntity pkt) {
		NBTTagCompound nbt = pkt.getNbtCompound();

		// Read plasma energy
		this.plasmaEnergy = nbt.getLong("plasmaEnergySync");

		// Read fluid tank data from packet
		if(nbt.hasKey("tank0")) {
			tanks[0].readFromNBT(nbt.getCompoundTag("tank0"));
		} else {
			tanks[0].setFluid(null);
		}

		if(nbt.hasKey("tank1")) {
			tanks[1].readFromNBT(nbt.getCompoundTag("tank1"));
		} else {
			tanks[1].setFluid(null);
		}

		// Read thermodynamic data from packet
		if(nbt.hasKey("boilerTemperature")) {
			this.boilerTemperature = nbt.getDouble("boilerTemperature");
			this.thermalPower = nbt.getDouble("thermalPower");
		}
	}
}
