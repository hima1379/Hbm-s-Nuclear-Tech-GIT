package com.hbm.main.tileentity.network.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.hbm.entity.missile.EntityMissileSM6;
import com.hbm.lib.ForgeDirection;
import com.hbm.main.tileentity.TileEntityTickingBase;
import com.hbm.radar.PhysicsBasedRadarSystem;
import com.hbm.radar.PhysicsBasedRadarSystem.RadarContact;
import com.hbm.radar.PhysicsBasedRadarSystem.RadarSpec;
import com.hbm.radar.RadarStealthCapability;

import api.hbm.data.DataDeviceType;
import api.hbm.data.DataNet;
import api.hbm.data.DataPacket;
import api.hbm.data.DataPacket.DataPacketType;
import api.hbm.data.IDataConnector;
import api.hbm.energy.IEnergyUser;
import com.hbm.physics.IRCSProvider;
import com.hbm.physics.RadarWavePhysics;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.world.World;

/**
 * SPY-6 Active Electronically Scanned Array (AESA) Radar TileEntity
 *
 * Based on real AN/SPY-6(V)1 AMDR (Air and Missile Defense Radar) specifications.
 *
 * ==================================================================================
 * AESA ARCHITECTURE (Sources: Raytheon, DoT&E FY2024, web search)
 * ==================================================================================
 *  RMA Count per face  : 37  (Radar Modular Assemblies, each 2ft × 2ft × 2ft)
 *  T/R elements per RMA: 144 (GaN Gallium Nitride transmit/receive modules)
 *  T/R elements/face   : 37 × 144 = 5,328
 *  Total T/R elements  : 5,328 × 4 faces = 21,312
 *  Frequency           : S-band, 3.3 GHz center (λ = 0.0909 m)
 *  Aperture per face   : √(4 × 37 × 0.6096² / π) ≈ 4.18 m effective diameter
 *
 * ==================================================================================
 * APERTURE PHYSICS (Nickel 2006, RTO-EN-SET-086 "Fundamentals of Signal Processing
 *                   for Phased Array Radar", Section 2.3)
 * ==================================================================================
 *  Beamwidth formula   : θ_BW = 60λ/D [°] for planar arrays (eq. after eq.(3))
 *  Untapered BW        : 60 × 0.0909 / 4.18 = 1.30°
 *  Taylor n=5, -30dB   : broadening factor 1.27 → θ_BW ≈ 1.65° (matches est. 1.5°-1.6°)
 *  Antenna gain        : G ≈ N_elements (Nickel 2006, Section 2.3)
 *                        G_dBi = 10·log₁₀(5328) + 7 dBi(patch) ≈ 44.3 dBi
 *                        (+15 dB over SPY-1's 42 dBi → system sensitivity improvement)
 *  Sidelobe level      : SL = 1/N = -37.3 dB (uniform); Taylor -30 dB taper applied
 *
 * ==================================================================================
 * MULTI-BEAM DIGITAL BEAMFORMING (Nickel 2006, Section 2.5 "Subarrays")
 * ==================================================================================
 *  37 digitized RMA outputs per face → independent digital beamforming
 *  Can form MAX_SIMULTANEOUS_BEAMS independent beams from stored subarray data
 *  Unlike SPY-1 PESA (single analog beam per face), AESA enables:
 *   - Simultaneous search + track beams
 *   - Multi-target track while scan (TWS)
 *   - Adaptive null steering against jamming (Nickel 2006, Section 4.2)
 *
 * ==================================================================================
 * TRUE TIME DELAY (TTD)
 * ==================================================================================
 *  Standard PESA: phase-only steering → narrowband, beam squint at large scan angles
 *    (Nickel 2006, Section 2.3: "a phased array is always narrowband")
 *  AESA with TTD: true time delays τ_k = r_k · u₀/c applied at each element
 *    → SPY-6 instantaneous bandwidth: ~400 MHz (vs SPY-1 ~50 MHz)
 *    → Enables waveform diversity: LFM chirp, polyphase codes (Nickel 2006, Section 2.1)
 *
 * ==================================================================================
 * PERFORMANCE vs SPY-1
 * ==================================================================================
 *  Sensitivity      : +15 dB system improvement
 *  Detection range  : 500 km vs 300 km (R ∝ SNR^(1/4) → 15dB ≈ +78% range)
 *  Beamwidth        : ~1.65° vs ~1.7° (SPY-1 confirmed ~1.7°)
 *  Scan rate        : 720°/s vs 360°/s (AESA: electronic in microseconds)
 *  Max contacts     : 200 vs 100
 *  Bandwidth        : ~400 MHz (TTD) vs ~50 MHz (phase-only PESA)
 */
public class TileEntitySPY6 extends TileEntityTickingBase implements ITickable, IDataConnector, IEnergyUser {

	private UUID deviceId;
	private DataNet dataNet;

	// ==========================================
	// Power Management
	// ==========================================
	public long power = 0;
	public static final long maxPower        = 5000000; // 5 MHE buffer (~17 min operation)
	public static final long powerConsumption = 4800;   // ~96 kW average (GaN AESA power draw)

	// ==========================================
	// AESA ARCHITECTURE CONSTANTS
	// AN/SPY-6(V)1 confirmed specifications
	// Sources: Raytheon, DoT&E FY2024 report
	// ==========================================
	public static final int    RMA_COUNT_PER_FACE   = 37;   // Radar Modular Assemblies per face
	public static final int    TR_ELEMENTS_PER_RMA  = 144;  // GaN T/R modules per RMA
	public static final int    TR_ELEMENTS_PER_FACE = RMA_COUNT_PER_FACE * TR_ELEMENTS_PER_RMA; // 5,328
	public static final int    FACE_COUNT           = 4;    // Fixed faces: N / E / S / W
	public static final int    TOTAL_TR_ELEMENTS    = TR_ELEMENTS_PER_FACE * FACE_COUNT;        // 21,312

	// ==========================================
	// APERTURE PHYSICS
	// Reference: Nickel 2006, RTO-EN-SET-086, Section 2.3
	// ==========================================
	// S-band center frequency
	public static final double FREQUENCY_GHZ       = 3.3;
	public static final double WAVELENGTH_M        = 3e8 / (FREQUENCY_GHZ * 1e9); // 0.09091 m

	// Aperture from RMA geometry
	// Each RMA = 2 ft × 2 ft = 0.6096 m × 0.6096 m
	// 37 RMAs → total area = 37 × 0.6096² = 13.75 m²
	// Effective circular diameter: D = √(4A/π) = 4.18 m
	public static final double RMA_SIDE_M          = 0.6096; // 2 ft per RMA
	public static final double APERTURE_AREA_M2    = RMA_COUNT_PER_FACE * RMA_SIDE_M * RMA_SIDE_M; // 13.75 m²
	public static final double APERTURE_DIAM_M     = Math.sqrt(4.0 * APERTURE_AREA_M2 / Math.PI); // 4.18 m
	public static final double APERTURE_IN_LAMBDA  = APERTURE_DIAM_M / WAVELENGTH_M;              // ~46 λ

	// Physics-based 3dB beamwidth (Nickel 2006, eq. after (3)):
	//   θ_BW [°] = 60/A  for planar arrays (A = aperture in wavelengths)
	// With Taylor n=5, -30dB sidelobe taper: broadening factor = 1.27
	// (Nickel 2006, Section 4.1, Fig. 13 — Taylor tapering increases beamwidth)
	public static final double TAYLOR_BROADENING   = 1.27;  // -30dB Taylor n=5 taper
	public static final double BEAMWIDTH_DEG       = TAYLOR_BROADENING * (60.0 / APERTURE_IN_LAMBDA); // ≈ 1.65°

	// Antenna gain from element count (Nickel 2006, Section 2.3: "G ≈ N")
	//   G_dBi = 10·log₁₀(N_elements) + g_element_dBi
	//   Patch element gain g_e ≈ 7 dBi for S-band (standard value)
	//   G_total ≈ 10·log₁₀(5328) + 7 = 37.3 + 7 = 44.3 dBi
	public static final double ELEMENT_GAIN_DBi    = 7.0;   // GaN patch element gain
	public static final double ANTENNA_GAIN_DBi    = 10.0 * Math.log10(TR_ELEMENTS_PER_FACE) + ELEMENT_GAIN_DBi; // ≈ 44.3
	public static final double ANTENNA_GAIN_LINEAR = Math.pow(10.0, ANTENNA_GAIN_DBi / 10.0);

	// Sidelobe levels (Nickel 2006, Section 4, eq.(12): SL = ||w||²·w = 1/N uniform)
	// Untapered uniform: SL = 1/N_elements → -37.3 dB
	// Taylor n=5, -30dB taper: designed sidelobe = -30 dB
	public static final double SIDELOBE_LEVEL_DB   = -30.0; // dB, Taylor -30dB taper

	// ==========================================
	// MULTI-BEAM DBF (AESA-specific)
	// Reference: Nickel 2006, Section 2.5 "Subarrays"
	// 37 digitized RMA outputs → N independent simultaneous beams
	// ==========================================
	public static final int MAX_SIMULTANEOUS_BEAMS = 4; // conservative (Raytheon: "multiple beams")
	// True Time Delay: instantaneous bandwidth (Nickel 2006 Section 2.3)
	public static final double TTD_BANDWIDTH_GHZ   = 0.4; // ~400 MHz instantaneous BW (SPY-6 TTD)

	// ==========================================
	// RECEIVER SENSITIVITY
	// SPY-6 improvement over SPY-1D: +15 dB system improvement
	// References: Raytheon, DoT&E FY2024 Annual Report
	//   SPY-1D baseline : -130 dBm  (RadarWavePhysics.SPY1_SENSITIVITY_DBM)
	//   SPY-6 AMDR      : -130 - 15 = -145 dBm
	// Physical basis: GaN T/R modules → lower noise figure (NF ~2 dB vs SPY-1 ~4 dB)
	//                 + larger aperture → higher gain → improved SNR at receiver
	// ==========================================
	public static final double SENSITIVITY_DBM  = -145.0;                                     // dBm
	public static final double MIN_DETECTABLE_W = Math.pow(10.0, SENSITIVITY_DBM / 10.0) * 1e-3; // W

	// Beam scheduling state
	private int simultaneousBeamsActive    = 1; // starts in single-beam search mode
	private boolean dbfMultiBeamEnabled    = true; // AESA digital beamforming always enabled
	private boolean ttdEnabled             = true; // True Time Delay: wideband operation

	// ==========================================
	// Radar Systems — 4 Fixed AESA Faces
	// ==========================================
	private PhysicsBasedRadarSystem[] radarSystems = new PhysicsBasedRadarSystem[FACE_COUNT]; // N,E,S,W
	private static final String[] ARRAY_NAMES    = {"NORTH", "EAST", "SOUTH", "WEST"};
	private static final double[] ARRAY_AZIMUTHS = {0.0, 90.0, 180.0, 270.0};
	private int radarUpdateTick = 0;
	private static final int RADAR_UPDATE_INTERVAL = 5; // Update every 5 ticks (0.25 s)

	// ==========================================
	// Operating State
	// ==========================================
	private boolean isActive     = false;
	private String  operatingMode = "SEARCH"; // SEARCH, TWS (Track While Scan), STANDBY

	// Scan configuration
	private boolean scanMissiles = true;
	private boolean scanPlayers  = false;
	private boolean scanAirborne = true;

	// Statistics
	private int  totalContactsDetected = 0;
	private int  lastScanContactCount  = 0;

	// Offline grace period
	private int  offlineTicks = 0;
	private static final int OFFLINE_GRACE_TICKS = 100;

	// Midcourse guidance
	private Map<UUID, Integer>  activeMissiles        = new HashMap<>();
	private Map<UUID, double[]> lastKnownTargetStates = new HashMap<>();
	private int guidanceUpdateTick = 0;
	private static final int GUIDANCE_UPDATE_INTERVAL = 1;

	public TileEntitySPY6() {
		this.deviceId = UUID.randomUUID();
	}

	@Override
	public void validate() {
		super.validate();
		for (int i = 0; i < FACE_COUNT; i++) {
			radarSystems[i] = null;
		}
		activeMissiles.clear();
		lastKnownTargetStates.clear();
		System.out.println("[SPY-6] AESA radar systems cleared on validate at " + pos);
	}

	@Override
	public String getInventoryName() {
		return "SPY-6 AESA Radar";
	}

	@Override
	public void update() {
		if (!world.isRemote) {
			this.updateMultiblockConnections();

			if (world.getTotalWorldTime() % 200 == 0) {
				System.out.println("[SPY6 DIAG] tick=" + world.getTotalWorldTime()
					+ " power=" + power + "/" + maxPower
					+ " isActive=" + isActive()
					+ " dataNet=" + (dataNet != null ? "#" + dataNet.getNetId() + " subs=" + dataNet.getSubscribers().size() : "null")
					+ " BW=" + String.format("%.2f°", BEAMWIDTH_DEG)
					+ " R_nom=500km");
			}

			if (power >= powerConsumption) {
				power -= powerConsumption;
				isActive = true;
				offlineTicks = 0;

				if (radarSystems[0] == null) {
					initializeRadarSystems();
				}

				radarUpdateTick++;
				if (radarUpdateTick >= RADAR_UPDATE_INTERVAL) {
					radarUpdateTick = 0;
					performRadarScan();
					sendRadarDataToNetwork();
				}
			} else {
				isActive = false;
				offlineTicks++;
				for (int i = 0; i < FACE_COUNT; i++) {
					radarSystems[i] = null;
				}
			}

			guidanceUpdateTick++;
			if (guidanceUpdateTick >= GUIDANCE_UPDATE_INTERVAL) {
				guidanceUpdateTick = 0;
				if (!activeMissiles.isEmpty()) {
					updateMidcourseGuidance();
				}
			}

			if (world.getTotalWorldTime() % 40 == 0) {
				sendStatusUpdate();
			}
		}
	}

	// ==========================================
	// RADAR INITIALIZATION
	// Uses physics-based parameters derived from
	// aperture equations (Nickel 2006, Section 2.3)
	// ==========================================

	/**
	 * Initialize all 4 SPY-6 AESA array faces with physics-based parameters.
	 *
	 * Key parameters derived from aperture physics (Nickel 2006):
	 *  beamWidth   = θ_BW = 1.27 × 60λ/D ≈ 1.65° (Taylor -30dB taper)
	 *  transmitPower = 1500 kW per face (GaN T/R modules, 1500/5328 ≈ 281W per element)
	 *  maxRange    = 500 km (nominal, full 37 RMAs)
	 *  scanRate    = 720°/s (AESA: electronic beam repositioning in microseconds)
	 */
	private void initializeRadarSystems() {
		Entity radarEntity = new DummyRadarEntity(world, pos.getX(), pos.getY(), pos.getZ());

		for (int i = 0; i < FACE_COUNT; i++) {
			RadarSpec spec = new RadarSpec();
			spec.radarType      = "SPY6_AESA_" + ARRAY_NAMES[i];

			// Physics-based beamwidth (Nickel 2006, Section 2.3, eq. after (3)):
			// θ = 60λ/D with Taylor -30dB broadening factor 1.27
			// Result: ~1.65° (vs SPY-1 PESA ~1.7°, slightly narrower due to larger aperture)
			spec.beamWidth      = BEAMWIDTH_DEG;

			// Nominal detection range 500 km (full aperture, 37 RMAs operational)
			spec.maxRange       = 500.0;

			// 95° per face (slight overlap for gapless coverage at face transitions)
			spec.azimuthScan    = 95.0;
			spec.elevationScan  = 90.0;  // Full hemisphere coverage

			// Atmospheric beam spreading — reduced for AESA (coherent GaN Tx)
			// Lower beamSpreadRate = less range-dependent beam broadening
			spec.beamSpreadRate = 0.3;

			// 1500 kW peak per face (GaN T/R modules)
			// Per-element: 1500 kW / 5328 elements ≈ 281 W/element (GaN achievable)
			spec.transmitPower  = 1500.0;

			// S-band center frequency
			spec.frequency      = FREQUENCY_GHZ;

			// AESA beam repositioning: electronic in microseconds
			// Scan rate 720°/s = twice SPY-1 (360°/s)
			spec.scanRate       = 720;

			// Not applicable for AESA (kept for framework compatibility)
			spec.scanBars       = 1;

			// AESA DBF: simultaneous beams in all directions — no narrow-beam gate
			// SPY-6 forms independent beams electronically via 37 digitized RMA outputs,
			// so targets at any elevation angle within max range are detectable.
			spec.beamAngleGatingEnabled = false;

			// ── Physics parameters for radar range equation (via RadarWavePhysics)
			// Antenna gain: aperture-derived from 5328 GaN T/R elements (Nickel 2006 G≈N)
			//   G_dBi = 10·log₁₀(5328) + 7 dBi = 44.3 dBi → ~26,916 linear
			spec.antennaGainLinear   = ANTENNA_GAIN_LINEAR;     // 44.3 dBi (+2.3 dB vs SPY-1 42 dBi)

			// Wavelength: S-band 3.3 GHz center, λ = c/f = 0.0909 m (same as SPY-1)
			spec.wavelengthM         = WAVELENGTH_M;             // 0.0909 m

			// Receiver sensitivity: -145 dBm (SPY-1 -130 dBm + 15 dB AESA/GaN improvement)
			// References: Raytheon FY2024, DoT&E FY2024 ("+15 dB system improvement")
			spec.minDetectablePowerW = MIN_DETECTABLE_W;         // -145 dBm → ~3.16e-18 W

			// System losses: same S-band hardware → 8 dB (feed + noise figure + processing)
			spec.systemLossesLinear  = RadarWavePhysics.SPY1_LOSSES; // 8 dB → ~6.31 linear

			radarEntity.rotationYaw = (float) ARRAY_AZIMUTHS[i];

			final int arrayIndex = i;
			radarSystems[i] = new PhysicsBasedRadarSystem(radarEntity, spec) {
				@Override
				protected boolean isValidRadarTarget(Entity entity) {
					return isEntityValidTarget(entity);
				}
			};
		}

		System.out.println("[SPY-6] All 4 AESA faces initialized"
			+ " | BW=" + String.format("%.2f°", BEAMWIDTH_DEG)
			+ " | G=" + String.format("%.1fdBi", ANTENNA_GAIN_DBi)
			+ " | SL=" + String.format("%.1fdB", SIDELOBE_LEVEL_DB)
			+ " | TTD=" + ttdEnabled
			+ " | DBF=" + dbfMultiBeamEnabled
			+ " | MaxBeams=" + MAX_SIMULTANEOUS_BEAMS
			+ " at " + pos);
	}

	// ==========================================
	// RADAR SCAN
	// ==========================================

	private void performRadarScan() {
		if (radarSystems[0] == null) return;

		Map<UUID, RadarContact> allContacts = new java.util.HashMap<>();

		// AESA DBF: MAX_SIMULTANEOUS_BEAMS independent beams per face
		simultaneousBeamsActive = MAX_SIMULTANEOUS_BEAMS;

		for (int i = 0; i < FACE_COUNT; i++) {
			if (radarSystems[i] != null) {
				radarSystems[i].update();
				allContacts.putAll(radarSystems[i].getContacts());
			}
		}

		lastScanContactCount = allContacts.size();
		totalContactsDetected += allContacts.size();

		if (world.getTotalWorldTime() % 100 == 0) {
			System.out.println("[SPY-6] 360° AESA scan | Contacts: " + allContacts.size()
				+ " | Beams: " + simultaneousBeamsActive + "/" + MAX_SIMULTANEOUS_BEAMS
				+ " | Power: " + power + "/" + maxPower);
		}
	}

	private boolean isEntityValidTarget(Entity entity) {
		if (entity == null)  return false;
		if (entity.isDead)   return false;
		if (!entity.addedToChunk) return false;
		if (entity instanceof DummyRadarEntity) return false;

		String name = entity.getClass().getSimpleName();
		if (name.contains("Shrapnel") || name.contains("Debris") ||
		    name.contains("Fragment") || name.contains("Particle") ||
		    name.contains("FX")       || name.contains("Fx")       ||
		    name.contains("Flame")    || name.contains("Explosion") ||
		    name.contains("Effect")   || name.contains("Gas")       ||
		    name.contains("Smoke")    || name.contains("Fire")) {
			return false;
		}

		if (scanMissiles && entity instanceof IRCSProvider) return true;
		if (entity instanceof net.minecraft.entity.item.EntityItem) return false;

		if (scanAirborne) {
			double hSpeed = Math.sqrt(entity.motionX * entity.motionX + entity.motionZ * entity.motionZ);
			double vSpeed = Math.abs(entity.motionY);
			if ((hSpeed >= 0.25 || vSpeed >= 0.25) && (entity.isAirBorne || !entity.onGround)) {
				return true;
			}
		}

		if (scanPlayers && entity instanceof net.minecraft.entity.player.EntityPlayer) {
			if (entity.isAirBorne || !entity.onGround) return true;
		}

		return false;
	}

	// ==========================================
	// DATANET
	// ==========================================

	private void sendRadarDataToNetwork() {
		if (dataNet == null || dataNet.isEmpty()) {
			getDataNet();
			if (dataNet == null) return;
		}

		Map<UUID, RadarContact> contacts = new java.util.HashMap<>();
		for (int i = 0; i < FACE_COUNT; i++) {
			if (radarSystems[i] != null) contacts.putAll(radarSystems[i].getContacts());
		}

		NBTTagCompound data = new NBTTagCompound();

		List<RadarContact> contactList = new ArrayList<>(contacts.values());
		contactList.removeIf(c -> c.entity == null || c.entity.isDead);
		contactList.sort((a, b) -> Double.compare(a.distance, b.distance));

		int maxContacts = Math.min(200, contactList.size());
		data.setInteger("ContactCount", maxContacts);

		for (int i = 0; i < maxContacts; i++) {
			RadarContact contact = contactList.get(i);
			NBTTagCompound contactNBT = new NBTTagCompound();
			contactNBT.setDouble("x",             contact.entity.posX);
			contactNBT.setDouble("y",             contact.entity.posY);
			contactNBT.setDouble("z",             contact.entity.posZ);
			contactNBT.setDouble("distance",      contact.distance);
			contactNBT.setDouble("azimuth",       contact.azimuth);
			contactNBT.setDouble("elevation",     contact.elevation);
			contactNBT.setDouble("signalStrength", contact.signalStrength);
			contactNBT.setDouble("closureRate",   contact.closureRate);
			contactNBT.setDouble("velocityX",     contact.entity.motionX * 20.0);
			contactNBT.setDouble("velocityY",     contact.entity.motionY * 20.0);
			contactNBT.setDouble("velocityZ",     contact.entity.motionZ * 20.0);
			contactNBT.setInteger("entityId",     contact.entity.getEntityId());
			boolean isMissile = contact.entity instanceof com.hbm.entity.missile.EntityMissileSM6;
			contactNBT.setBoolean("isMissile", isMissile);
			data.setTag("Contact_" + i, contactNBT);
		}

		DataPacket packet = new DataPacket(DataPacketType.RADAR_SCAN_DATA, deviceId, data);
		dataNet.broadcastData(packet);
	}

	private void sendStatusUpdate() {
		sendStatusUpdate(false);
	}

	private void sendStatusUpdate(boolean forceOnlineTrue) {
		if (dataNet == null) {
			getDataNet();
			if (dataNet == null) return;
		}

		boolean reportOnline;
		if (forceOnlineTrue) {
			reportOnline = true;
		} else if (isActive()) {
			reportOnline = true;
		} else {
			reportOnline = offlineTicks < OFFLINE_GRACE_TICKS;
		}

		NBTTagCompound data = new NBTTagCompound();
		data.setString("DeviceType",    DataDeviceType.RADAR_PHASED_ARRAY.name());
		data.setBoolean("Online",       reportOnline);
		data.setString("Mode",          operatingMode);
		data.setLong("Power",           power);
		data.setInteger("ContactCount", lastScanContactCount);

		// AESA capability data
		data.setInteger("SimultaneousBeams", simultaneousBeamsActive);
		data.setBoolean("DBF_Enabled", dbfMultiBeamEnabled);
		data.setBoolean("TTD_Enabled", ttdEnabled);
		data.setDouble("AntennaGain_dBi", ANTENNA_GAIN_DBi);
		data.setDouble("Beamwidth_deg",   BEAMWIDTH_DEG);
		data.setDouble("Sidelobe_dB",     SIDELOBE_LEVEL_DB);

		String deviceName = getDeviceName();
		if (deviceName.contains(" @ ")) {
			deviceName = deviceName.substring(0, deviceName.indexOf(" @ "));
		}
		data.setString("DeviceName", deviceName);

		DataPacket packet = new DataPacket(DataPacketType.STATUS_UPDATE, deviceId, data);
		dataNet.broadcastData(packet);
	}

	// ==========================================
	// IDataConnector
	// ==========================================

	@Override
	public void receiveData(DataPacket packet) {
		System.out.println("[SPY-6] Received packet: " + packet.getType());
	}

	public DataNet getDataNet() {
		if (dataNet == null && !world.isRemote) {
			scanForCables();
		}
		return dataNet;
	}

	public void setDataNet(DataNet net) {
		this.dataNet = net;
	}

	@Override
	public void invalidate() {
		if (!world.isRemote && dataNet != null) {
			dataNet.removeSubscriber(this);
			dataNet = null;
		}
		super.invalidate();
	}

	@Override
	public void onChunkUnload() {
		if (!world.isRemote && dataNet != null) {
			dataNet.removeSubscriber(this);
			dataNet = null;
		}
		super.onChunkUnload();
	}

	// Scan bounds match getDimensions(): West=5, East=6, North=5, South=6
	private void scanForCables() {
		int coreX = pos.getX(), coreY = pos.getY(), coreZ = pos.getZ();

		System.out.println("[SPY-6 SCAN] Starting cable scan from core: " + pos);

		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -5; dx <= 6; dx++) {
				for (int dz = -5; dz <= 6; dz++) {
					net.minecraft.util.math.BlockPos checkPos = new net.minecraft.util.math.BlockPos(
						coreX + dx, coreY + dy, coreZ + dz);

					net.minecraft.tileentity.TileEntity te = world.getTileEntity(checkPos);

					if (te instanceof api.hbm.data.IDataConductor) {
						api.hbm.data.IDataConductor conductor = (api.hbm.data.IDataConductor) te;
						System.out.println("[SPY-6 SCAN] Found IDataConductor at offset (" + dx + "," + dy + "," + dz + ")");

						if (conductor.getDataNet() != null) {
							dataNet = conductor.getDataNet();
							dataNet.addSubscriber(this);
							System.out.println("[SPY-6] Connected to DataNet #" + dataNet.getNetId() + " via cable at " + checkPos);
							sendStatusUpdate(true);
							return;
						} else {
							api.hbm.data.DataNet bootstrapped = new api.hbm.data.DataNet();
							bootstrapped.addConductor(conductor);
							dataNet = bootstrapped;
							dataNet.addSubscriber(this);
							System.out.println("[SPY-6] Bootstrapped DataNet #" + dataNet.getNetId() + " via cable at " + checkPos);
							sendStatusUpdate(true);
							return;
						}
					}
				}
			}
		}

		System.out.println("[SPY-6] No DataNet cables found in multiblock area");
	}

	@Override
	public boolean canConnect(EnumFacing dir) {
		return dir == EnumFacing.DOWN;
	}

	@Override
	public DataDeviceType getDeviceType() {
		return DataDeviceType.RADAR_PHASED_ARRAY;
	}

	@Override
	public UUID getDeviceId() {
		return this.deviceId;
	}

	@Override
	public String getDeviceName() {
		return "SPY-6 AESA Radar @ " + pos.toString();
	}

	@Override
	public boolean isActive() {
		return !this.isInvalid() && isActive && power >= powerConsumption;
	}

	// ==========================================
	// IEnergyUser
	// ==========================================

	@Override public long getPower()            { return power; }
	@Override public long getMaxPower()         { return maxPower; }
	@Override public void setPower(long power)  { this.power = power; }

	private void updateMultiblockConnections() {
		for (int dx = -5; dx <= 6; dx++) {
			for (int dz = -5; dz <= 6; dz++) {
				this.trySubscribe(world, pos.add(dx, -1, dz), ForgeDirection.DOWN);
			}
		}
	}

	// ==========================================
	// NBT — includes full AESA health persistence
	// ==========================================

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);
		this.power = nbt.getLong("power");

		String deviceIdStr = nbt.getString("deviceId");
		if (deviceIdStr != null && !deviceIdStr.isEmpty()) {
			try {
				this.deviceId = UUID.fromString(deviceIdStr);
			} catch (IllegalArgumentException e) {
				this.deviceId = UUID.randomUUID();
			}
		} else {
			this.deviceId = UUID.randomUUID();
		}

		this.isActive      = nbt.getBoolean("isActive");
		this.operatingMode = nbt.getString("operatingMode");
		this.scanMissiles  = nbt.hasKey("scanMissiles")  ? nbt.getBoolean("scanMissiles")  : true;
		this.scanPlayers   = nbt.hasKey("scanPlayers")   ? nbt.getBoolean("scanPlayers")   : false;
		this.scanAirborne  = nbt.hasKey("scanAirborne")  ? nbt.getBoolean("scanAirborne")  : true;
		this.totalContactsDetected = nbt.getInteger("totalContactsDetected");

		// DBF / TTD state
		this.dbfMultiBeamEnabled = !nbt.hasKey("dbfEnabled") || nbt.getBoolean("dbfEnabled");
		this.ttdEnabled          = !nbt.hasKey("ttdEnabled") || nbt.getBoolean("ttdEnabled");
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);
		nbt.setLong("power",    power);
		nbt.setString("deviceId", deviceId.toString());
		nbt.setBoolean("isActive", isActive);
		nbt.setString("operatingMode", operatingMode);
		nbt.setBoolean("scanMissiles", scanMissiles);
		nbt.setBoolean("scanPlayers",  scanPlayers);
		nbt.setBoolean("scanAirborne", scanAirborne);
		nbt.setInteger("totalContactsDetected", totalContactsDetected);

		// DBF / TTD state
		nbt.setBoolean("dbfEnabled", dbfMultiBeamEnabled);
		nbt.setBoolean("ttdEnabled", ttdEnabled);

		return nbt;
	}

	// ==========================================
	// Helper Classes
	// ==========================================

	private static class DummyRadarEntity extends EntityLivingBase {
		public DummyRadarEntity(World world, int x, int y, int z) {
			super(world);
			this.setPosition(x + 0.5, y + 1.5, z + 0.5);
			this.rotationYaw   = 0.0F;
			this.rotationPitch = 0.0F;
		}

		@Override public void onUpdate() {}

		@Override
		public boolean isEntityInvulnerable(net.minecraft.util.DamageSource source) { return true; }

		@Override
		public net.minecraft.util.EnumHandSide getPrimaryHand() {
			return net.minecraft.util.EnumHandSide.RIGHT;
		}

		@Override
		public Iterable<net.minecraft.item.ItemStack> getArmorInventoryList() {
			return java.util.Collections.emptyList();
		}

		@Override
		public net.minecraft.item.ItemStack getItemStackFromSlot(net.minecraft.inventory.EntityEquipmentSlot slotIn) {
			return net.minecraft.item.ItemStack.EMPTY;
		}

		@Override
		public void setItemStackToSlot(net.minecraft.inventory.EntityEquipmentSlot slotIn, net.minecraft.item.ItemStack stack) {}

		@Override
		public net.minecraft.util.EnumActionResult applyPlayerInteraction(net.minecraft.entity.player.EntityPlayer player,
				net.minecraft.util.math.Vec3d vec, net.minecraft.util.EnumHand hand) {
			return net.minecraft.util.EnumActionResult.PASS;
		}
	}

	// ==========================================
	// Getters for GUI and external access
	// ==========================================

	public int     getContactCount()           { return lastScanContactCount; }
	public int     getTotalContactsDetected()  { return totalContactsDetected; }
	public String  getOperatingMode()          { return operatingMode; }
	public boolean isScanningMissiles()        { return scanMissiles; }
	public boolean isScanningPlayers()         { return scanPlayers; }
	public boolean isScanningAirborne()        { return scanAirborne; }
	public boolean isRadarActive()             { return isActive && radarSystems[0] != null; }

	// DBF / AESA capability getters
	public int     getSimultaneousBeamsActive() { return simultaneousBeamsActive; }
	public boolean isDBFEnabled()               { return dbfMultiBeamEnabled; }
	public boolean isTTDEnabled()               { return ttdEnabled; }
	public double  getAntennaGainDBi()          { return ANTENNA_GAIN_DBi; }
	public double  getNominalBeamwidthDeg()     { return BEAMWIDTH_DEG; }
	public double  getTTDBandwidthGHz()         { return TTD_BANDWIDTH_GHZ; }

	public double getPowerScaled(int scale) {
		return (power * scale) / (double) maxPower;
	}

	public PhysicsBasedRadarSystem   getRadarSystem()  { return radarSystems[0]; }
	public PhysicsBasedRadarSystem[] getRadarSystems() { return radarSystems; }

	public Map<UUID, RadarContact> getContacts() {
		Map<UUID, RadarContact> all = new java.util.HashMap<>();
		for (int i = 0; i < FACE_COUNT; i++) {
			if (radarSystems[i] != null) all.putAll(radarSystems[i].getContacts());
		}
		return all;
	}

	public double getCurrentAzimuth() {
		if (radarSystems[0] == null) return 0.0;
		return radarSystems[0].getCurrentAzimuthScan();
	}

	public double getCurrentElevation() {
		if (radarSystems[0] == null) return 0.0;
		return radarSystems[0].getCurrentElevationScan();
	}

	public int      getCurrentBar()   { return 0; }

	public RadarSpec getRadarSpec() {
		if (radarSystems[0] == null) return RadarSpec.getDefault();
		return radarSystems[0].getSpec();
	}

	public Map<UUID, Integer> getActiveMissiles() { return activeMissiles; }

	// ==========================================
	// MIDCOURSE GUIDANCE
	// ==========================================

	public void registerMissile(UUID missileId, int targetEntityId) {
		registerMissile(missileId, targetEntityId, 0, 0, 0, 0, 0, 0);
	}

	/**
	 * Register an SM-6 missile for midcourse guidance with fallback seed coordinates.
	 * Seed coordinates are used when the target entity is in an unloaded chunk at
	 * registration time; SPY-6 will extrapolate from them until the entity is visible.
	 */
	public void registerMissile(UUID missileId, int targetEntityId,
			double seedX, double seedY, double seedZ,
			double seedVelX, double seedVelY, double seedVelZ) {
		activeMissiles.put(missileId, targetEntityId);
		if (world != null) {
			Entity target = world.getEntityByID(targetEntityId);
			if (target != null && !target.isDead) {
				lastKnownTargetStates.put(missileId, new double[]{
					target.posX, target.posY, target.posZ,
					target.motionX * 20.0, target.motionY * 20.0, target.motionZ * 20.0,
					world.getTotalWorldTime()
				});
				System.out.println("[SPY-6 GUIDANCE] Registered SM-6 | MissileID: " + missileId
					+ " | TargetID: " + targetEntityId);
			} else if (seedX != 0 || seedY != 0 || seedZ != 0) {
				// Target entity not yet loaded — seed with provided coordinates
				lastKnownTargetStates.put(missileId, new double[]{
					seedX, seedY, seedZ, seedVelX, seedVelY, seedVelZ,
					world.getTotalWorldTime()
				});
				System.out.println("[SPY-6 GUIDANCE] Registered SM-6 (seed coords) | MissileID: " + missileId
					+ " | TargetID: " + targetEntityId);
			}
		}
	}

	private void updateMidcourseGuidance() {
		if (activeMissiles.isEmpty()) return;

		List<EntityMissileSM6> missiles = world.getEntities(EntityMissileSM6.class, entity -> true);

		Iterator<Map.Entry<UUID, Integer>> iterator = activeMissiles.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Integer> entry = iterator.next();
			UUID missileId     = entry.getKey();
			int  targetEntityId = entry.getValue();

			EntityMissileSM6 missile = null;
			for (EntityMissileSM6 m : missiles) {
				if (m.getDeviceId().equals(missileId)) { missile = m; break; }
			}

			if (missile == null || missile.isDead) {
				iterator.remove();
				lastKnownTargetStates.remove(missileId);
				continue;
			}

			Entity target = world.getEntityByID(targetEntityId);
			double tPosX, tPosY, tPosZ, tVelX, tVelY, tVelZ;

			if (target != null && !target.isDead) {
				tPosX = target.posX; tPosY = target.posY; tPosZ = target.posZ;
				tVelX = target.motionX * 20.0;
				tVelY = target.motionY * 20.0;
				tVelZ = target.motionZ * 20.0;
				lastKnownTargetStates.put(missileId,
					new double[]{tPosX, tPosY, tPosZ, tVelX, tVelY, tVelZ, world.getTotalWorldTime()});
			} else {
				double[] lastState = lastKnownTargetStates.get(missileId);
				if (lastState == null) { iterator.remove(); continue; }
				long   ticksElapsed = world.getTotalWorldTime() - (long) lastState[6];
				double dt           = ticksElapsed * 0.05;
				tPosX = lastState[0] + lastState[3] * dt;
				tPosY = lastState[1] + lastState[4] * dt;
				tPosZ = lastState[2] + lastState[5] * dt;
				tVelX = lastState[3]; tVelY = lastState[4]; tVelZ = lastState[5];
			}

			double dx = tPosX - missile.posX;
			double dy = tPosY - missile.posY;
			double dz = tPosZ - missile.posZ;
			double distanceToTarget = Math.sqrt(dx*dx + dy*dy + dz*dz);

			if (distanceToTarget < 5000.0) {
				iterator.remove();
				lastKnownTargetStates.remove(missileId);
				continue;
			}

			NBTTagCompound data = new NBTTagCompound();
			data.setDouble("targetX",    tPosX); data.setDouble("targetY",    tPosY);
			data.setDouble("targetZ",    tPosZ); data.setDouble("targetVelX", tVelX);
			data.setDouble("targetVelY", tVelY); data.setDouble("targetVelZ", tVelZ);

			DataPacket guidancePacket = new DataPacket(
				DataPacketType.MIDCOURSE_GUIDANCE, this.deviceId, missileId, data);

			if (dataNet != null) dataNet.broadcastData(guidancePacket);
			missile.receiveMidcourseGuidance(guidancePacket);
		}
	}

	public boolean isUsableByPlayer(net.minecraft.entity.player.EntityPlayer player) {
		if (this.world.getTileEntity(this.pos) != this) return false;
		return player.getDistanceSq(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
	}
}
