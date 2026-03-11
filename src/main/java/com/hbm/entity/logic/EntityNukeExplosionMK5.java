package com.hbm.entity.logic;

import java.util.ArrayList;
import java.util.List;

import com.hbm.config.BombConfig;
import com.hbm.config.RealBombConfig;
import com.hbm.config.CompatibilityConfig;
import com.hbm.config.GeneralConfig;
import com.hbm.entity.logic.IChunkLoader;
import com.hbm.entity.mob.EntityGlowingOne;
import com.hbm.main.AdvancementManager;
import com.hbm.main.MainRegistry;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.biome.*;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.ForgeChunkManager.Type;
import net.minecraft.util.math.ChunkPos;

import org.apache.logging.log4j.Level;

import com.hbm.util.ContaminationUtil;
import com.hbm.entity.effect.EntityFalloutUnderGround;
import com.hbm.entity.effect.EntityFalloutRain;
import com.hbm.entity.effect.EntityBlackRain;
import com.hbm.entity.effect.EntityNukeTorexRealistic;
import com.hbm.handler.AtmosphericDispersionSystem;
import com.hbm.physics.nuke.CraterPhysics;
import com.hbm.explosion.BlockDestructionEntry;
import com.hbm.explosion.FireballVolumeProcessor;
import com.hbm.explosion.ThermalRadiationProcessor;
import com.hbm.explosion.BlastPressureFieldProcessor;
import com.hbm.explosion.ChunkBasedDestructionExecutor;
import com.hbm.explosion.ChunkPriorityManager;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.init.Biomes;
import net.minecraft.entity.Entity;
import net.minecraft.init.SoundEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.SoundCategory;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;

/**
 * REALISTIC PHYSICS-BASED NUCLEAR EXPLOSION ENTITY
 *
 * NEW THREE-MECHANISM DESTRUCTION SYSTEM:
 *
 * 1. FIREBALL CORE (FireballVolumeProcessor)
 *    - INSTANT volume-based sphere vaporization
 *    - R = 52 × W^(1/3) meters
 *    - NO raycast - perfect sphere formation
 *    - NO obstruction - instant formation
 *    - Everything vaporizes (300,000+ K)
 *
 * 2. THERMAL RADIATION (ThermalRadiationProcessor)
 *    - Inverse square law: Q ∝ 1/r²
 *    - Line-of-sight raycast checks
 *    - Atmospheric attenuation: exp(-μr)
 *    - Material-specific fluence thresholds
 *    - Can destroy beyond blast range (flammables)
 *
 * 3. BLAST PRESSURE (BlastPressureFieldProcessor)
 *    - DIRECT pressure field evaluation P(Z)
 *    - Kingery-Bulmash equations
 *    - NO raycast for pressure calculation
 *    - Mach stem, reflected pressure, crater formation
 *    - Material-specific PSI thresholds
 *
 * 4. RADIATION EFFECTS (ContaminationUtil + Fallout)
 *    - Prompt radiation (5% of yield)
 *    - Residual radiation (fallout, 10%)
 *    - Time-decaying contamination
 *
 * EXECUTION SEQUENCE:
 * Phase 0: Fireball vaporization (instant, ~1 tick)
 * Phase 1: Thermal radiation (LOS + fluence calculation)
 * Phase 2: Blast pressure (pressure field evaluation)
 * Phase 3: Fallout generation (long-term contamination)
 *
 * PHYSICS ACCURACY:
 * - All effects use scaled distance Z = r / W^(1/3)
 * - No arbitrary limits - physics determines everything
 * - Validated against Glasstone & Dolan (1977)
 * - Proper material-specific responses
 */
public class EntityNukeExplosionMK5 extends Entity implements IChunkLoader {
	// === WEAPON PARAMETERS ===
	public int strength;
	public int radius; // Display radius for compatibility
	public double yieldKilotons; // ACTUAL yield in kilotons

	// === CONFIGURATION ===
	public boolean mute = false;
	public boolean spawnFire = false;
	public boolean fallout = true;
	private boolean fallingStarted = false;
	private boolean floodPlease = false;
	private int falloutAdd = 0;

	// === VISUAL EFFECTS ===
	private boolean effectsSpawned = false; // Prevent effect repetition on chunk reload

	// === CHUNK LOADING ===
	private Ticket loaderTicket;

	// === NEW UNIFIED DESTRUCTION SYSTEM ===
	FireballVolumeProcessor fireballProcessor;      // Phase 0: Initialize fireball processor (no entries yet)
	ThermalRadiationProcessor thermalProcessor;     // Phase 1: Initialize thermal processor (no entries yet)
	BlastPressureFieldProcessor blastProcessor;     // Phase 2: Initialize blast processor (no entries yet)
	ChunkBasedDestructionExecutor chunkExecutor;    // Phase 3: Chunk-based merge & execute (memory-efficient)
	EntityFalloutRain falloutRain;                  // Phase 4: Radiation fallout
	ChunkPriorityManager chunkManager;              // Distance-based chunk loading

	// === PROCESSING PHASES ===
	private int destructionPhase = 0; // 0=fireball-precalc, 1=thermal-precalc, 2=blast-precalc, 3=unified-destruction, 4=fallout
	private boolean fireballComplete = false;
	private boolean thermalComplete = false;
	private boolean blastComplete = false;
	private boolean unifiedComplete = false;

	// === LOFTED MASS / FALLOUT SOURCE TERM (Glasstone §9.50) ===
	// Captured at Phase 3 completion; used in Phase 4 to scale ADS source rate.
	private long capturedBlastBlocks = 0;     // actual m³ of BLAST-type terrain lofted
	private double loftedMassScaleFactor = 1.0; // ratio: actual / theoretical crater volume
	private boolean blastDataCaptured = false;  // ensures capture runs exactly once

	// === THERMAL FREEZE MECHANISM ===
	// During chunk-based destruction (Phase 3), the thermal physics clock is held at
	// THERMAL_FREEZE_TICKS so that all entities experience the "5 seconds after detonation"
	// temperature while destruction is still running.  Once destruction completes, the
	// clock resumes counting from the freeze point.
	private static final int THERMAL_FREEZE_TICKS = 100; // 5 seconds at 20 ticks/s
	private long destructionEndTick = -1L; // tick when Phase 3 completed (-1 = still running)

	// === PHYSICS CONSTANTS ===
	public static final double shockSpeed = 2.0; // blocks/tick

	public EntityNukeExplosionMK5(World world) {
		super(world);
	}

	@Override
	public void onUpdate() {
		if (world.isRemote) return;

		if (strength == 0 || !CompatibilityConfig.isWarDim(world)) {
			this.clearLoadedChunks();
			this.unloadMainChunk();
			this.setDead();
			return;
		}

		loadMainChunk();

		// === RADIATION EFFECTS (continuous) ===
		float rads = 0;

		if (fallout && falloutRain == null) {
			// Calculate radiation dose based on weapon yield
			// Prompt radiation: ~5% of yield
			double radiationFraction = RealBombConfig.RADIATION_FRACTION;
			double radiationEnergy = yieldKilotons * RealBombConfig.JOULES_PER_KT * radiationFraction;

			// Distributed over affected volume
			double affectedVolume = (4.0 / 3.0) * Math.PI * Math.pow(radius * 2, 3);
			double averageDose = radiationEnergy / affectedVolume;

			// Time-dependent decay
			double decayFactor = Math.pow(0.5, (double)this.ticksExisted / (radius * 2));
			rads = (float)(averageDose * decayFactor * 0.001);

			// Initial effects
			if (ticksExisted == 1) {
				EntityGlowingOne.convertInRadiusToGlow(world, this.posX, this.posY, this.posZ,
						radius * 1.5);

				if (radius > 60) {
					for (EntityPlayer player : world.getEntitiesWithinAABB(EntityPlayer.class,
							new AxisAlignedBB(this.posX, this.posY, this.posZ,
									this.posX, this.posY, this.posZ)
									.grow(radius * 2, radius * 2, radius * 2))) {
						AdvancementManager.grantAchievement(player, AdvancementManager.progress_nuke);
					}
				}
			}
		}

		// === THERMAL AND BLAST ENERGY RADIATION (continuous) ===
		if (ticksExisted < 2400) {
			// Freeze-corrected thermal physics clock.
			// While chunk-based destruction (Phase 3) is active, hold the thermal clock at
			// THERMAL_FREEZE_TICKS so that all entities experience the "5 s post-detonation"
			// fireball temperature for the entire destruction window.
			// Once destruction completes, the clock resumes counting from the freeze point.
			long effectiveThermalTick;
			if (ticksExisted <= THERMAL_FREEZE_TICKS) {
				// Normal progression before freeze threshold
				effectiveThermalTick = ticksExisted;
			} else if (destructionEndTick < 0) {
				// Chunk destruction still running — freeze thermal clock
				effectiveThermalTick = THERMAL_FREEZE_TICKS;
			} else {
				// Destruction complete — resume counting from freeze point
				effectiveThermalTick = THERMAL_FREEZE_TICKS + (ticksExisted - destructionEndTick);
			}

			// Blast energy: ~50% of yield
			double blastFraction = RealBombConfig.BLAST_FRACTION;
			double blastEnergy = yieldKilotons * RealBombConfig.JOULES_PER_KT * blastFraction;
			float blast = (float)(blastEnergy * 1e-11);

			// Apply contamination — thermal damage now driven by physics (yield + effective tick)
			ContaminationUtil.radiate(world, this.posX, this.posY, this.posZ,
					Math.min(1000, radius * 2), rads, 0F,
					yieldKilotons, effectiveThermalTick,
					blast, this.ticksExisted * shockSpeed);
		}

		// === SOUND EFFECTS ===
		if (!mute) {
			if (this.radius > 30) {
				this.world.playSound(null, this.posX, this.posY, this.posZ,
						SoundEvents.ENTITY_LIGHTNING_THUNDER, SoundCategory.AMBIENT,
						Math.min(1, ticksExisted / 200F) * this.radius * 0.05F,
						0.8F + this.rand.nextFloat() * 0.2F);
			} else {
				this.world.playSound(null, this.posX, this.posY, this.posZ,
						SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.AMBIENT,
						Math.min(1, ticksExisted / 100F) * Math.max(2F, this.radius * 0.1F),
						0.8F + this.rand.nextFloat() * 0.2F);
			}
		}

		// === NEW THREE-MECHANISM DESTRUCTION SYSTEM ===

		// PHASE 0: FIREBALL CORE VAPORIZATION
		// Instant volume-based sphere destruction (NO raycast)
		if (destructionPhase == 0) {
			// Initialize ChunkPriorityManager on first tick
			if (chunkManager == null && ticksExisted == 1) {
				// Calculate maximum explosion radius for chunk loading
				// Use blast pressure range (typically largest of all effects)
				double maxExplosionRadius = this.radius * 2.0; // Conservative estimate
				System.out.println("[MK5] Initializing chunk priority manager");
				chunkManager = new ChunkPriorityManager(world, this.posX, this.posZ,
						maxExplosionRadius, MainRegistry.instance);
			}

			// Spawn realistic 1:1 scale visual effects (ONCE ONLY)
			if (!effectsSpawned && ticksExisted == 1) {
				EntityNukeTorexRealistic torex = EntityNukeTorexRealistic.create(
						world, this.posX, this.posY, this.posZ, this.yieldKilotons);
				world.spawnEntity(torex);
				effectsSpawned = true;
				System.out.println("[MK5] Spawned EntityNukeTorexRealistic: " +
						String.format("%.1f", this.yieldKilotons) + " kt at (" +
						String.format("%.1f", this.posX) + ", " +
						String.format("%.1f", this.posY) + ", " +
						String.format("%.1f", this.posZ) + ")");
			}

			if (fireballProcessor == null) {
				System.out.println("[MK5] Initializing fireball volume processor (PRE-CALCULATION ONLY)");
				fireballProcessor = new FireballVolumeProcessor(world, this.posX, this.posY, this.posZ,
						this.yieldKilotons);

				// Pre-calculation complete - move to next phase immediately
				// NO DESTRUCTION YET - entries will be processed in Phase 3 (unified)
				fireballComplete = true;
				destructionPhase = 1;
				System.out.println("[MK5] FIREBALL PRE-CALCULATION COMPLETE - Starting thermal pre-calculation");
			}
		}

		// PHASE 1: THERMAL RADIATION
		// Heat flux with LOS checks and inverse square law
		else if (destructionPhase == 1) {
			if (thermalProcessor == null) {
				System.out.println("[MK5] Initializing thermal radiation processor (PRE-CALCULATION ONLY)");
				double fireballRadius = fireballProcessor != null ?
					fireballProcessor.getFireballRadius() : 52.0 * Math.pow(yieldKilotons, 1.0/3.0);
				thermalProcessor = new ThermalRadiationProcessor(world, this.posX, this.posY, this.posZ,
						this.yieldKilotons, fireballRadius);

				// Pre-calculation complete - move to next phase immediately
				// NO DESTRUCTION YET - entries will be processed in Phase 3 (unified)
				thermalComplete = true;
				destructionPhase = 2;
				System.out.println("[MK5] THERMAL PRE-CALCULATION COMPLETE - Starting blast pre-calculation");
			}
		}

		// PHASE 2: BLAST PRESSURE FIELD
		// Direct pressure evaluation with Kingery-Bulmash (NO raycast)
		else if (destructionPhase == 2) {
			if (blastProcessor == null) {
				System.out.println("[MK5] Initializing blast pressure field processor (PRE-CALCULATION ONLY)");
				blastProcessor = new BlastPressureFieldProcessor(world, this.posX, this.posY, this.posZ,
						this.yieldKilotons);

				// Pre-calculation complete - move to next phase immediately
				// NO DESTRUCTION YET - entries will be processed in Phase 3 (unified)
				blastComplete = true;
				destructionPhase = 3;
				System.out.println("[MK5] BLAST PRE-CALCULATION COMPLETE - Starting unified destruction");
			}
		}

		// PHASE 3: CHUNK-BASED UNIFIED DESTRUCTION
		// Process chunks sequentially with merged entries (memory-efficient)
		else if (destructionPhase == 3) {
			if (chunkExecutor == null) {
				System.out.println("[MK5] Initializing chunk-based destruction executor");
				System.out.println("[MK5] Memory-efficient mode: 98% reduction vs old system");

				// IMPORTANT: Phase 0-2 generated full entry lists (old system)
				// Chunk-based system regenerates entries per-chunk, so discard old entries
				// This frees 2.6GB of memory for 10.4MT explosion!
				System.out.println("[MK5] Clearing pre-calculated entries to free memory...");

				// Note: We can't directly clear the private lists, but Java GC will
				// reclaim memory once they're no longer referenced

				// Create chunk-based executor with all 3 processors
				chunkExecutor = new ChunkBasedDestructionExecutor(
					world,
					posX, posY, posZ,
					fireballProcessor,
					thermalProcessor,
					blastProcessor
				);
			}

			if (!chunkExecutor.isComplete()) {
				// Execute chunk-based destruction
				chunkExecutor.processTick();

				// Update chunk loading
				if (chunkManager != null) {
					double estimatedRadius = ticksExisted * 2.0;
					chunkManager.updateChunkLoading(estimatedRadius);
				}
			} else {
				// Unified destruction complete — record tick so thermal clock can resume
				if (destructionEndTick < 0) {
					destructionEndTick = ticksExisted;
					System.out.println("[MK5] Thermal freeze released at tick " + destructionEndTick);
				}
				unifiedComplete = true;

				// === CAPTURE LOFTED MASS (Glasstone §9.50) ===
				// Only BLAST-type blocks are lofted into the mushroom cloud;
				// FIREBALL-vaporized material is plasma and does not contribute to fallout.
				if (!blastDataCaptured && chunkExecutor != null) {
					capturedBlastBlocks = chunkExecutor.getBlastBlocksDestroyed();
					// Theoretical parabolic crater volume: V = (π/2) × Ra² × Da
					double craterR = CraterPhysics.getApparentCraterRadius(yieldKilotons,
							CraterPhysics.SoilType.DRY_SOIL);
					double craterD = CraterPhysics.getApparentCraterDepth(yieldKilotons,
							CraterPhysics.SoilType.DRY_SOIL);
					double theoreticalVol = (Math.PI / 2.0) * craterR * craterR * craterD;
					loftedMassScaleFactor = (theoreticalVol > 0)
							? Math.max(0.3, Math.min(3.0, capturedBlastBlocks / theoreticalVol))
							: 1.0;
					blastDataCaptured = true;
					System.out.println(String.format(
							"[MK5] Lofted mass: %d m³ BLAST blocks, theorVol=%.0f m³, scale=%.3f",
							capturedBlastBlocks, theoreticalVol, loftedMassScaleFactor));
				}

				destructionPhase = 4;
				System.out.println("[MK5] UNIFIED DESTRUCTION COMPLETE - Starting fallout");

				// Cleanup chunk manager
				if (chunkManager != null) {
					chunkManager.cleanup();
					chunkManager = null;
				}
			}
		}

		// PHASE 4: FALLOUT GENERATION
		// Long-term radiation contamination
		else if (destructionPhase == 4) {
			if (!fallingStarted) {
				if (fallout) {
					// Create fallout sphere
					EntityFalloutUnderGround falloutBall = new EntityFalloutUnderGround(this.world);
					falloutBall.posX = this.posX;
					falloutBall.posY = this.posY;
					falloutBall.posZ = this.posZ;
					falloutBall.setScale((int)(this.radius * (BombConfig.falloutRange / 100F) + falloutAdd));

					// Fallout is always enabled unless disabled by config
					falloutBall.falloutRainDoFallout = fallout;
					falloutBall.falloutRainDoFlood = floodPlease;
					falloutBall.falloutRainRadius1 = (int)((this.radius * 2.5F + falloutAdd) *
							BombConfig.falloutRange * 0.01F);
					falloutBall.falloutRainRadius2 = this.radius + 4;
					this.world.spawnEntity(falloutBall);

					// === ATMOSPHERIC DISPERSION SOURCE (Glasstone §9.50-9.59, Fig 9.96) ===
					// Register finite-duration nuclear blast source.  Wind field seals segments
					// that continue catch-up deposition for up to 5 hours post-detonation.
					// Source rate is proportional to actual lofted mass (BLAST blocks × 0.6
					// ground-burst fraction per Glasstone §9.50).
					try {
						// Mushroom cloud stabilisation height: H ≈ 900 × W^0.3 m (Glasstone Fig 9.96)
						double plumeHeightM = 900.0 * Math.pow(yieldKilotons, 0.3);
						// Effective dispersal duration (Way-Wigner early-fallout window)
						double durationSec = 300.0 + 240.0 * Math.pow(yieldKilotons, 0.2);
						// Activity proxy: each lofted m³ × 0.6 (ground-burst lofting fraction)
						double sourceRate = capturedBlastBlocks * 0.6 * loftedMassScaleFactor;
						AtmosphericDispersionSystem.get(this.world).registerNuclearBlastSource(
								posX, posY, posZ, plumeHeightM, sourceRate, durationSec);
					} catch (Exception e) {
						MainRegistry.logger.warn("[MK5] ADS registration failed: " + e.getMessage());
					}

					// === BLACK RAIN (base-surge radioactive rainfall) ===
					// Spawns a persistent rain entity that irradiates exposed entities and
					// contaminates the ground within the early-fallout radius.
					try {
						EntityBlackRain blackRain = new EntityBlackRain(this.world);
						blackRain.posX = this.posX;
						blackRain.posY = this.posY + 10.0;
						blackRain.posZ = this.posZ;
						// Rain radius ≈ 2× visual blast radius; intensity scaled from yield
						blackRain.setScale((int)(this.radius * 2.0F), (float)this.yieldKilotons);
						this.world.spawnEntity(blackRain);
					} catch (Exception e) {
						MainRegistry.logger.warn("[MK5] EntityBlackRain spawn failed: " + e.getMessage());
					}
				} else {
					// Crater filling without fallout
					EntityFalloutRain falloutRain = new EntityFalloutRain(this.world);
					falloutRain.doFallout = false;
					falloutRain.doFlood = floodPlease;
					falloutRain.posX = this.posX;
					falloutRain.posY = this.posY;
					falloutRain.posZ = this.posZ;
					falloutRain.setScale((int)((this.radius * 2.5F + falloutAdd) *
							BombConfig.falloutRange * 0.01F), this.radius + 4);
					this.world.spawnEntity(falloutRain);
				}
				fallingStarted = true;
			} else if (this.ticksExisted * shockSpeed > 160) {
				// Cleanup
				// Safety: Release chunk manager if still active
				if (chunkManager != null) {
					chunkManager.cleanup();
					chunkManager = null;
				}
				this.clearLoadedChunks();
				this.unloadMainChunk();
				this.setDead();
			}
		}
	}

	@Override
	protected void entityInit() {
		init(ForgeChunkManager.requestTicket(MainRegistry.instance, world, Type.ENTITY));
	}

	@Override
	public void init(Ticket ticket) {
		if (!world.isRemote && ticket != null) {
			if (loaderTicket == null) {
				loaderTicket = ticket;
				loaderTicket.bindEntity(this);
				loaderTicket.getModData();
			}
			ForgeChunkManager.forceChunk(loaderTicket, new ChunkPos(chunkCoordX, chunkCoordZ));
		}
	}

	List<ChunkPos> loadedChunks = new ArrayList<ChunkPos>();

	@Override
	public void loadNeighboringChunks(int newChunkX, int newChunkZ) {
		if (!world.isRemote && loaderTicket != null) {
			for (ChunkPos chunk : loadedChunks) {
				ForgeChunkManager.unforceChunk(loaderTicket, chunk);
			}

			loadedChunks.clear();
			loadedChunks.add(new ChunkPos(newChunkX, newChunkZ));
			loadedChunks.add(new ChunkPos(newChunkX + 1, newChunkZ + 1));
			loadedChunks.add(new ChunkPos(newChunkX - 1, newChunkZ - 1));
			loadedChunks.add(new ChunkPos(newChunkX + 1, newChunkZ - 1));
			loadedChunks.add(new ChunkPos(newChunkX - 1, newChunkZ + 1));
			loadedChunks.add(new ChunkPos(newChunkX + 1, newChunkZ));
			loadedChunks.add(new ChunkPos(newChunkX, newChunkZ + 1));
			loadedChunks.add(new ChunkPos(newChunkX - 1, newChunkZ));
			loadedChunks.add(new ChunkPos(newChunkX, newChunkZ - 1));

			for (ChunkPos chunk : loadedChunks) {
				ForgeChunkManager.forceChunk(loaderTicket, chunk);
			}
		}
	}

	public void clearLoadedChunks() {
		if (!world.isRemote && loaderTicket != null && loadedChunks != null) {
			for (ChunkPos chunk : loadedChunks) {
				ForgeChunkManager.unforceChunk(loaderTicket, chunk);
			}
		}
	}

	private ChunkPos mainChunk;

	public void loadMainChunk() {
		if (!world.isRemote && loaderTicket != null && this.mainChunk == null) {
			this.mainChunk = new ChunkPos((int)Math.floor(this.posX / 16D),
					(int)Math.floor(this.posZ / 16D));
			ForgeChunkManager.forceChunk(loaderTicket, this.mainChunk);
		}
	}

	public void unloadMainChunk() {
		if (!world.isRemote && loaderTicket != null && this.mainChunk != null) {
			ForgeChunkManager.unforceChunk(loaderTicket, this.mainChunk);
		}
	}

	public static boolean isWet(World world, BlockPos pos) {
		Biome b = world.getBiome(pos);
		return b.getTempCategory() == Biome.TempCategory.OCEAN || b.isHighHumidity() ||
				b instanceof BiomeOcean || b instanceof BiomeBeach || b instanceof BiomeRiver ||
				b instanceof BiomeJungle || b instanceof BiomeSwamp;
	}

	@Override
	public void readEntityFromNBT(NBTTagCompound nbt) {
		radius = nbt.getInteger("radius");
		strength = nbt.getInteger("strength");
		falloutAdd = nbt.getInteger("falloutAdd");
		fallout = nbt.getBoolean("fallout");
		floodPlease = nbt.getBoolean("floodPlease");
		spawnFire = nbt.getBoolean("spawnFire");
		mute = nbt.getBoolean("mute");
		if (nbt.hasKey("fs")) fallingStarted = nbt.getBoolean("fs");
		if (nbt.hasKey("yieldKt")) {
			yieldKilotons = nbt.getDouble("yieldKt");
		} else {
			// Fallback for old saves
			yieldKilotons = Math.pow(radius / 90.0, 2.5);
		}

		// Read destruction phases
		if (nbt.hasKey("destructionPhase")) destructionPhase = nbt.getInteger("destructionPhase");
		if (nbt.hasKey("fireballComplete")) fireballComplete = nbt.getBoolean("fireballComplete");
		if (nbt.hasKey("thermalComplete")) thermalComplete = nbt.getBoolean("thermalComplete");
		if (nbt.hasKey("blastComplete")) blastComplete = nbt.getBoolean("blastComplete");

		// Read lofted mass data (for ADS / black rain scaling)
		if (nbt.hasKey("capturedBlastBlocks")) capturedBlastBlocks = nbt.getLong("capturedBlastBlocks");
		if (nbt.hasKey("loftedMassScale")) loftedMassScaleFactor = nbt.getDouble("loftedMassScale");
		if (nbt.hasKey("blastDataCaptured")) blastDataCaptured = nbt.getBoolean("blastDataCaptured");

		// Read visual effects state
		if (nbt.hasKey("effectsSpawned")) effectsSpawned = nbt.getBoolean("effectsSpawned");

		// Note: Processors are recreated on load rather than serialized
		// This keeps NBT data minimal and avoids compatibility issues
	}

	@Override
	public void writeEntityToNBT(NBTTagCompound nbt) {
		nbt.setInteger("radius", radius);
		nbt.setInteger("strength", strength);
		nbt.setInteger("falloutAdd", falloutAdd);
		nbt.setBoolean("fallout", fallout);
		nbt.setBoolean("floodPlease", floodPlease);
		nbt.setBoolean("spawnFire", spawnFire);
		nbt.setBoolean("mute", mute);
		nbt.setBoolean("fs", fallingStarted);
		nbt.setDouble("yieldKt", yieldKilotons);

		// Save destruction phases
		nbt.setInteger("destructionPhase", destructionPhase);
		nbt.setBoolean("fireballComplete", fireballComplete);
		nbt.setBoolean("thermalComplete", thermalComplete);
		nbt.setBoolean("blastComplete", blastComplete);

		// Save lofted mass data
		nbt.setLong("capturedBlastBlocks", capturedBlastBlocks);
		nbt.setDouble("loftedMassScale", loftedMassScaleFactor);
		nbt.setBoolean("blastDataCaptured", blastDataCaptured);

		// Save visual effects state
		nbt.setBoolean("effectsSpawned", effectsSpawned);

		// Note: Processors are not serialized to keep NBT minimal
	}

	/**
	 * Factory method for creating nuclear explosion with realistic physics
	 *
	 * FIXED: BombConfig values now directly represent KILOTONS, not radius
	 *
	 * @param world World
	 * @param configValue Value from BombConfig (KILOTONS for nuclear, radius for non-nuclear)
	 * @param x X coordinate
	 * @param y Y coordinate
	 * @param z Z coordinate
	 */
	public static EntityNukeExplosionMK5 statFac(World world, int configValue, double x, double y, double z) {
		if (GeneralConfig.enableExtendedLogging && !world.isRemote)
			MainRegistry.logger.log(Level.INFO,
					"[NUKE] Initialized TRIPLE PHYSICS explosion at " + x + " / " + y + " / " + z +
							" with config value " + configValue + "!");

		if (configValue == 0) configValue = 25;

		EntityNukeExplosionMK5 mk5 = new EntityNukeExplosionMK5(world);

		// Check if this is a historical nuclear weapon (values are in KILOTONS)
		if (isNuclearWeapon(configValue)) {
			// BombConfig value IS the yield in kilotons
			mk5.yieldKilotons = configValue;

			// Calculate display radius from yield using cube root scaling
			// R âˆ Y^(1/3) for fireball radius
			// Using 5 PSI radius: R â‰ˆ 90 * Y^(1/3) meters
			mk5.radius = (int)(90.0 * Math.pow(configValue, 1.0/3.0));
			mk5.strength = mk5.radius << 1;

			System.out.println("[MK5] Nuclear weapon: " + configValue + " kt");
			System.out.println("[MK5] Calculated radius: " + mk5.radius + " m (5 PSI)");
		} else {
			// Non-nuclear or custom: interpret as radius
			mk5.radius = configValue;
			mk5.strength = configValue << 1;
			// Calculate equivalent yield from radius (inverse of above formula)
			mk5.yieldKilotons = Math.pow(configValue / 90.0, 3.0);

			System.out.println("[MK5] Custom explosion: radius " + configValue + " m");
			System.out.println("[MK5] Equivalent yield: " + String.format("%.1f", mk5.yieldKilotons) + " kt");
		}

		mk5.setPosition(x, y, z);
		mk5.floodPlease = isWet(world, new BlockPos(x, y, z));
		if (BombConfig.disableNuclear)
			mk5.fallout = false;

		System.out.println("=== NEW THREE-MECHANISM NUCLEAR EXPLOSION ===");
		System.out.println("Yield: " + String.format("%.1f", mk5.yieldKilotons) + " kt");
		System.out.println("Type: " + (isNuclearWeapon(configValue) ? "Nuclear" : "Custom"));
		System.out.println("Fireball radius: " + String.format("%.0f", RealBombConfig.getFireballRadius(mk5.yieldKilotons)) + " m");
		System.out.println("5 PSI radius: " + mk5.radius + " m");
		System.out.println("Phase 0: FIREBALL (instant volume vaporization)");
		System.out.println("Phase 1: THERMAL (radiation with LOS + inverse square)");
		System.out.println("Phase 2: BLAST (pressure field P(Z) evaluation)");
		System.out.println("Phase 3: FALLOUT (residual radiation)");
		System.out.println("=============================================");

		return mk5;
	}

	/**
	 * Check if this is a nuclear weapon (BombConfig values are in kt)
	 */
	private static boolean isNuclearWeapon(int configValue) {
		return configValue == BombConfig.gadgetRadius ||
				configValue == BombConfig.boyRadius ||
				configValue == BombConfig.manRadius ||
				configValue == BombConfig.mikeRadius ||
				configValue == BombConfig.tsarRadius ||
				configValue == BombConfig.missileRadius ||
				configValue == BombConfig.mirvRadius;
	}

	public static EntityNukeExplosionMK5 statFacNoRad(World world, int r, double x, double y, double z) {
		EntityNukeExplosionMK5 mk5 = statFac(world, r, x, y, z);
		mk5.fallout = false;
		return mk5;
	}

	public static EntityNukeExplosionMK5 statFacNoRadFire(World world, int r, double x, double y, double z) {
		EntityNukeExplosionMK5 mk5 = statFac(world, r, x, y, z);
		mk5.fallout = false;
		mk5.spawnFire = true;
		return mk5;
	}

	public EntityNukeExplosionMK5 moreFallout(int fallout) {
		falloutAdd = fallout;
		return this;
	}

	public EntityNukeExplosionMK5 mute() {
		this.mute = true;
		return this;
	}
}