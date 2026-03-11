package com.hbm.entity.effect;

import java.util.*;

import com.hbm.blocks.ModBlocks;
import com.hbm.config.BombConfig;
import com.hbm.config.RealBombConfig;
import com.hbm.config.RadiationConfig;
import com.hbm.config.VersatileConfig;
import com.hbm.config.CompatibilityConfig;
import com.hbm.interfaces.IConstantRenderer;
import com.hbm.render.amlfrom1710.Vec3;
import com.hbm.saveddata.AuxSavedData;
import com.hbm.saveddata.RadiationSavedData;

//Chunkloading stuff
import java.util.ArrayList;
import java.util.List;
import com.hbm.entity.logic.IChunkLoader;
import com.hbm.main.MainRegistry;
import com.hbm.blocks.generic.WasteLog;
import com.hbm.physics.air.WindField;
import com.hbm.world.biome.BiomeCrater;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.ForgeChunkManager.Type;
import net.minecraft.util.math.ChunkPos;

import net.minecraft.block.BlockHugeMushroom;
import net.minecraft.block.BlockSand;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockGravel;
import net.minecraft.block.BlockOre;
import net.minecraft.block.BlockIce;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.BlockSnowBlock;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.init.Blocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockStone;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.material.Material;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.world.World;
import net.minecraft.client.Minecraft;

/**
 * Physics-based radioactive fallout system
 *
 * Fallout formation and deposition:
 * - Fission products rise in mushroom cloud
 * - Particles settle based on size and wind
 * - Radiation intensity follows 7-10 rule: I(t) = Iâ‚€ * t^(-1.2)
 *
 * References:
 * - Glasstone & Dolan, Chapter IX - Residual Nuclear Radiation and Fallout
 * - UNSCEAR 2000 Report: Sources and Effects of Ionizing Radiation
 * - Defense Civil Preparedness Agency: "Fallout Prediction" (1973)
 */
public class EntityFalloutRain extends Entity implements IConstantRenderer, IChunkLoader {
	private static final DataParameter<Integer> SCALE = EntityDataManager.createKey(EntityFalloutRain.class, DataSerializers.VARINT);
	private static final DataParameter<Boolean> DRIFT_PHASE = EntityDataManager.createKey(EntityFalloutRain.class, DataSerializers.BOOLEAN);
	public boolean done = false;
	public boolean doFallout = false;
	public boolean doFlood = false;
	public boolean doDrop = false;
	public int waterLevel = 0;

	private Ticket loaderTicket;

	// Fallout intensity zones based on distance
	// Reference: Glasstone & Dolan, Figure 9.95 - Fallout pattern
	private double s0; // Lethal zone (>1000 R/hr at H+1)
	private double s1; // Severe zone (300-1000 R/hr)
	private double s2; // Moderate zone (100-300 R/hr)
	private double s3; // Light zone (30-100 R/hr)
	private double s4; // Minimal zone (10-30 R/hr)
	private double s5; // Trace zone (3-10 R/hr)
	private double s6; // Background zone (<3 R/hr)
	private int fallingRadius;

	private boolean firstTick = true;
	private final List<Long> chunksToProcess = new ArrayList<>();
	private final List<Long> outerChunksToProcess = new ArrayList<>();
	private int falloutTickNumber = 0;

	// --- DRIFT PHASE (wind-driven contamination after chunk processing) ---
	/** True when all chunks have been processed; triggers drift phase. */
	private boolean chunksDone = false;
	/** Elapsed ticks in the drift phase. */
	private int driftTick = 0;
	/** Duration of the drift phase in ticks (60 seconds). */
	private static final int DRIFT_DURATION = 1200;

	public EntityFalloutRain(World world) {
		super(world);
		this.setSize(4, 20);
		this.ignoreFrustumCheck = false;
		this.isImmuneToFire = true;

		this.waterLevel = getInt(CompatibilityConfig.fillCraterWithWater.get(world.provider.getDimension()));
		if(this.waterLevel == 0) {
			this.waterLevel = world.getSeaLevel();
		} else if(this.waterLevel < 0 && this.waterLevel > -world.getSeaLevel()) {
			this.waterLevel = world.getSeaLevel() - this.waterLevel;
		}
	}

	public EntityFalloutRain(World p_i1582_1_, int maxage) {
		super(p_i1582_1_);
		this.setSize(4, 20);
		this.isImmuneToFire = true;
	}

	public static int getInt(Object e) {
		if(e == null)
			return 0;
		return (int)e;
	}

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		return new AxisAlignedBB(this.posX, this.posY, this.posZ, this.posX, this.posY, this.posZ);
	}

	@Override
	public boolean isInRangeToRender3d(double x, double y, double z) {
		return true;
	}

	@Override
	public boolean isInRangeToRenderDist(double distance) {
		return true;
	}

	@Override
	protected void entityInit() {
		init(ForgeChunkManager.requestTicket(MainRegistry.instance, world, Type.ENTITY));
		this.dataManager.register(SCALE, 0);
		this.dataManager.register(DRIFT_PHASE, false);
	}

	@Override
	public void init(Ticket ticket) {
		if(!world.isRemote) {
			if(ticket != null) {
				if(loaderTicket == null) {
					loaderTicket = ticket;
					loaderTicket.bindEntity(this);
					loaderTicket.getModData();
				}
				ForgeChunkManager.forceChunk(loaderTicket, new ChunkPos(chunkCoordX, chunkCoordZ));
			}
		}
	}

	List<ChunkPos> loadedChunks = new ArrayList<ChunkPos>();

	@Override
	public void loadNeighboringChunks(int newChunkX, int newChunkZ) {
		if(!world.isRemote && loaderTicket != null) {
			for(ChunkPos chunk : loadedChunks) {
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

			for(ChunkPos chunk : loadedChunks) {
				ForgeChunkManager.forceChunk(loaderTicket, chunk);
			}
		}
	}

	private void gatherChunks() {
		Set<Long> chunks = new LinkedHashSet<>();
		Set<Long> outerChunks = new LinkedHashSet<>();
		// Expand outerRange to cover maximum downwind elongation (E ≤ 4.0).
		// Actual zone assignment uses getEffectiveDist(), so blocks beyond the
		// ellipse boundary receive no contamination even if chunks are gathered.
		int baseRange = doFallout ? getScale() : fallingRadius;
		int outerRange = doFallout ? (baseRange * 4) : fallingRadius;
		int adjustedMaxAngle = 20 * outerRange / 32;

		for(int angle = 0; angle <= adjustedMaxAngle; angle++) {
			Vec3 vector = Vec3.createVectorHelper(outerRange, 0, 0);
			vector.rotateAroundY((float)(angle * Math.PI / 180.0 / (adjustedMaxAngle / 360.0)));
			outerChunks.add(ChunkPos.asLong((int)(posX + vector.xCoord) >> 4, (int)(posZ + vector.zCoord) >> 4));
		}

		for(int distance = 0; distance <= outerRange; distance += 8) {
			for(int angle = 0; angle <= adjustedMaxAngle; angle++) {
				Vec3 vector = Vec3.createVectorHelper(distance, 0, 0);
				vector.rotateAroundY((float)(angle * Math.PI / 180.0 / (adjustedMaxAngle / 360.0)));
				long chunkCoord = ChunkPos.asLong((int)(posX + vector.xCoord) >> 4, (int)(posZ + vector.zCoord) >> 4);
				if(!outerChunks.contains(chunkCoord)) chunks.add(chunkCoord);
			}
		}

		chunksToProcess.addAll(chunks);
		outerChunksToProcess.addAll(outerChunks);
		Collections.reverse(chunksToProcess);
		Collections.reverse(outerChunksToProcess);
	}

	private void unloadAllChunks() {
		if(loaderTicket != null) {
			for(ChunkPos chunk : loadedChunks) {
				ForgeChunkManager.unforceChunk(loaderTicket, chunk);
			}
		}
	}

	public void stompAround() {
		if(!chunksToProcess.isEmpty()) {
			long chunkPos = chunksToProcess.remove(chunksToProcess.size() - 1);
			int chunkPosX = (int)(chunkPos & Integer.MAX_VALUE);
			int chunkPosZ = (int)(chunkPos >> 32 & Integer.MAX_VALUE);

			for(int x = chunkPosX << 4; x < (chunkPosX << 4) + 16; x++) {
				for(int z = chunkPosZ << 4; z < (chunkPosZ << 4) + 16; z++) {
					// Use wind-corrected elliptical distance (Glasstone §9.83-9.93)
					stomp(new MutableBlockPos(x, 0, z), getEffectiveDist(x - posX, z - posZ));
				}
			}

		} else if(!outerChunksToProcess.isEmpty()) {
			long chunkPos = outerChunksToProcess.remove(outerChunksToProcess.size() - 1);
			int chunkPosX = (int)(chunkPos & Integer.MAX_VALUE);
			int chunkPosZ = (int)(chunkPos >> 32 & Integer.MAX_VALUE);

			for(int x = chunkPosX << 4; x < (chunkPosX << 4) + 16; x++) {
				for(int z = chunkPosZ << 4; z < (chunkPosZ << 4) + 16; z++) {
					// Gate on effective (elliptical) distance, not raw Euclidean
					double effectiveDist = getEffectiveDist(x - posX, z - posZ);
					if(effectiveDist <= getScale()) {
						stomp(new MutableBlockPos(x, 0, z), effectiveDist);
					}
				}
			}

		} else {
			// All chunks processed — enter wind-driven drift phase.
			this.chunksDone = true;
			this.dataManager.set(DRIFT_PHASE, true);
		}
	}

	@Override
	public void onUpdate() {
		if(!world.isRemote) {
			if(!CompatibilityConfig.isWarDim(world)) {
				this.setDead();
			} else if(firstTick) {
				if(chunksToProcess.isEmpty() && outerChunksToProcess.isEmpty()) gatherChunks();

				// Deposit initial fallout radiation
				// Reference: Glasstone & Dolan, Section 9.13 - Fallout decay
				if(doFallout) {
					depositFalloutRadiation();
					// Force crater biome onto the blast-excavated zone:
					// total sterilisation inside the apparent crater radius.
					if(fallingRadius > 0) {
						forceCraterBiome(fallingRadius);
					}
				}

				firstTick = false;
			}

			// --- CHUNK PROCESSING PHASE ---
			if(!chunksDone) {
				if(falloutTickNumber >= BombConfig.fChunkSpeed) {
					if(!this.isDead) {
						long start = System.currentTimeMillis();
						while(!this.isDead && !chunksDone && System.currentTimeMillis() < start + BombConfig.falloutMS) {
							stompAround();
						}
					}
					falloutTickNumber = 0;
				}
				falloutTickNumber++;
			}

			// --- WIND DRIFT PHASE ---
			// After all chunks are contaminated, fine aerosols continue to drift
			// downwind for DRIFT_DURATION ticks (Glasstone §9.30-9.35).
			if(chunksDone && !this.isDead) {
				if(driftTick >= DRIFT_DURATION) {
					this.setDead();
				} else {
					// Deposit contamination every second (20 ticks)
					if(driftTick % 20 == 0) {
						depositDriftFallout();
					}
					driftTick++;
				}
			}

			if(this.isDead) {
				unloadAllChunks();
				this.done = true;

				// Trigger weather effects for large detonations
				if(RadiationConfig.rain > 0 && doFlood) {
					if((doFallout && getScale() > 120) || (doFlood && getScale() > 100)) {
						world.getWorldInfo().setRaining(true);
						world.getWorldInfo().setRainTime(RadiationConfig.rain);
					}
					if((doFallout && getScale() > 150) || (doFlood && getScale() > 120)) {
						world.getWorldInfo().setThundering(true);
						world.getWorldInfo().setThunderTime(RadiationConfig.rain);
						AuxSavedData.setThunder(world, RadiationConfig.rain);
					}
				}
			}
		}

		// CLIENT: spawn visible fallout drift cloud when in the drift phase.
		if(world.isRemote && this.dataManager.get(DRIFT_PHASE)) {
			spawnFalloutDriftParticles();
		}
	}

	/**
	 * Deposit radioactive fallout using realistic decay model
	 *
	 * Fallout radiation intensity follows the "7-10 rule":
	 * I(t) = Iâ‚ * (t/1)^(-1.2)
	 * where Iâ‚ is intensity at 1 hour after detonation
	 *
	 * Reference: Glasstone & Dolan, Section 9.13
	 */
	private void depositFalloutRadiation() {
		if(!doFallout) return;

		RadiationSavedData data = RadiationSavedData.getData(world);
		if(data == null) return;

		int scale = getScale();
		double maxRange = scale * 1.5; // Fallout extends beyond blast radius

		// Calculate initial radiation based on weapon yield
		// Assume proportional to fallout fraction of total energy
		double falloutFraction = RealBombConfig.FALLOUT_FRACTION;

		// Estimate yield from radius (inverse Sedov-Taylor)
		double estimatedYield = Math.pow(scale / 90.0, 2.5); // kt
		double falloutEnergy = estimatedYield * RealBombConfig.JOULES_PER_KT * falloutFraction;

		// Convert to radiation dose (simplified)
		// 1 Gray = 100 rad (absorbed dose)
		double totalRadiation = falloutEnergy * 1e-9; // Simplified conversion

		MutableBlockPos pos = new MutableBlockPos();

		// Distribute radiation in wind-elongated fallout pattern.
		// Terrain shielding factor 0.7 applied (Glasstone §9.95, §9.101):
		//   "flat countryside reduces dose to ~70 % of smooth-plane values."
		final double TERRAIN_SHIELD = 0.7;

		for(int x = (int)(posX - maxRange); x < posX + maxRange; x += 4) {
			for(int z = (int)(posZ - maxRange); z < posZ + maxRange; z += 4) {
				// Wind-corrected elliptical distance (Glasstone Table 9.93 cigar shape)
				double distance = getEffectiveDist(x - posX, z - posZ);
				if(distance > maxRange) continue;

				// Radiation intensity decreases with effective distance
				// Use exponential decay: I âˆ exp(-r/Î»)
				double decayLength = scale * 0.3; // Characteristic decay length
				double intensityFactor = Math.exp(-distance / decayLength);

				// Distance-based intensity using fallout zones
				double zoneMultiplier = 1.0;
				if(distance > s0) zoneMultiplier = 0.8;
				if(distance > s1) zoneMultiplier = 0.5;
				if(distance > s2) zoneMultiplier = 0.3;
				if(distance > s3) zoneMultiplier = 0.1;
				if(distance > s4) zoneMultiplier = 0.05;
				if(distance > s5) zoneMultiplier = 0.01;
				if(distance > s6) zoneMultiplier = 0.001;

				// Apply terrain shielding factor (Glasstone §9.95)
				float radiation = (float)(totalRadiation * intensityFactor
						* zoneMultiplier * 0.0001 * TERRAIN_SHIELD);

				if(radiation > 0.001) {
					pos.setPos(x, 0, z);
					data.setRadForCoord(pos, radiation);
				}
			}
		}
	}

	// -----------------------------------------------------------------------
	// WIND DRIFT PHASE — server-side contamination deposition
	// -----------------------------------------------------------------------

	/**
	 * Deposits radioactive contamination in an expanding downwind crescent.
	 *
	 * Physical basis (Glasstone §9.30-9.35): after the initial fallout deposition
	 * is complete, fine-particulate aerosols remain suspended and continue to drift
	 * downwind. The leading edge of this residual cloud advances proportional to
	 * wind speed; crosswind spread grows slowly via turbulent diffusion.
	 * The deposition rate diminishes as the plume dilutes with distance.
	 *
	 * Server-side only. Called once per second (every 20 ticks) during drift phase.
	 */
	private void depositDriftFallout() {
		if(!doFallout) return;
		WindField wind = WindField.get(this.world);
		double windRad = Math.toRadians(wind.windDirectionDeg);
		double downX   = Math.sin(windRad);
		double downZ   = Math.cos(windRad);

		int scale       = getScale();
		double progress = (double) driftTick / DRIFT_DURATION; // 0 → 1

		// Front advances from scale to 4×scale over the drift duration.
		double frontDist  = scale * (1.0 + progress * 3.0);
		// Crosswind half-width grows via turbulent diffusion.
		double crossWidth = scale * (0.3 + progress * 0.5);

		// Derive a base deposition rate from yield estimate (same formula as depositFalloutRadiation).
		double estimatedYield = Math.pow(scale / 90.0, 2.5);
		double baseRate = estimatedYield * RealBombConfig.FALLOUT_FRACTION * 1e-9 * 0.001;
		float driftRad = (float)(baseRate * (1.0 - progress * 0.7));
		if(driftRad < 1e-7F) return;

		// Sample 40 random points in the crescent leading edge.
		for(int i = 0; i < 40; i++) {
			double t     = (world.rand.nextDouble() * 2.0 - 1.0) * crossWidth;
			double depth = (world.rand.nextDouble() * 0.25 - 0.05) * frontDist;
			double bx = posX + (frontDist + depth) * downX - t * downZ;
			double bz = posZ + (frontDist + depth) * downZ + t * downX;
			RadiationSavedData.incrementRad(world,
					new BlockPos((int) bx, 64, (int) bz),
					driftRad, driftRad * 20F);
		}
	}

	// -----------------------------------------------------------------------
	// WIND DRIFT PHASE — client-side particle visualisation
	// -----------------------------------------------------------------------

	/**
	 * Spawns particles that visualise the drifting radioactive plume on the client.
	 *
	 * SMOKE_NORMAL gives a dusty yellowish-grey haze characteristic of fallout
	 * aerosols. CRIT_MAGIC (normally the golden-star critical-hit effect) is
	 * repurposed as a subtle radiation "shimmer" near ground level.
	 *
	 * Particles are spawned around the local player, biased toward the downwind
	 * front so the player can see the plume advancing on them.
	 *
	 * Client-side only. Called every tick during drift phase.
	 */
	private void spawnFalloutDriftParticles() {
		net.minecraft.client.entity.EntityPlayerSP player =
				net.minecraft.client.Minecraft.getMinecraft().player;
		if(player == null) return;

		WindField wind  = WindField.get(this.world);
		double windRad  = Math.toRadians(wind.windDirectionDeg);
		double downX    = Math.sin(windRad);
		double downZ    = Math.cos(windRad);

		int scale       = getScale();
		double progress = (double) driftTick / DRIFT_DURATION;
		// Centre of the drift front in world coordinates.
		double frontDist = scale * (1.0 + progress * 3.0);
		double frontWX   = posX + frontDist * downX;
		double frontWZ   = posZ + frontDist * downZ;
		double dToFront  = Math.sqrt((frontWX - player.posX) * (frontWX - player.posX)
				+ (frontWZ - player.posZ) * (frontWZ - player.posZ));

		// Only render particles when the player is close to the plume front.
		if(dToFront > 64.0) return;

		// --- Dusty haze: SMOKE_NORMAL drifting slowly downwind -------------------
		for(int i = 0; i < 8; i++) {
			double t   = (world.rand.nextDouble() * 2.0 - 1.0) * 12.0;
			double fwd = (world.rand.nextDouble() - 0.5) * 16.0;
			double px  = player.posX + fwd * downX - t * downZ;
			double pz  = player.posZ + fwd * downZ + t * downX;
			double py  = player.posY + world.rand.nextDouble() * 4.0;
			world.spawnParticle(EnumParticleTypes.SMOKE_NORMAL,
					px, py, pz,
					downX * (0.04 + world.rand.nextDouble() * 0.04),
					0.01 + world.rand.nextDouble() * 0.02,
					downZ * (0.04 + world.rand.nextDouble() * 0.04));
		}

		// --- Radiation shimmer: CRIT_MAGIC near the ground -----------------------
		for(int i = 0; i < 4; i++) {
			double t   = (world.rand.nextDouble() * 2.0 - 1.0) * 8.0;
			double fwd = (world.rand.nextDouble() - 0.5) * 8.0;
			double px  = player.posX + fwd * downX - t * downZ;
			double pz  = player.posZ + fwd * downZ + t * downX;
			double py  = player.posY + world.rand.nextDouble() * 1.5;
			world.spawnParticle(EnumParticleTypes.CRIT_MAGIC,
					px, py, pz,
					downX * 0.02, 0.005, downZ * 0.02);
		}
	}

	private void letFall(World world, MutableBlockPos pos, int lastGapHeight, int contactHeight) {
		int fallChance = RadiationConfig.blocksFallCh;
		if(fallChance < 1)
			return;
		if(fallChance < 100) {
			int chance = world.rand.nextInt(100);
			if(chance < fallChance)
				return;
		}

		int bottomHeight = lastGapHeight;
		MutableBlockPos gapPos = new MutableBlockPos(pos.getX(), 0, pos.getZ());

		for(int i = lastGapHeight; i <= contactHeight; i++) {
			pos.setY(i);
			Block b = world.getBlockState(pos).getBlock();
			if(!b.isReplaceable(world, pos)) {
				float hardness = b.getExplosionResistance(null);
				if(hardness >= 0 && hardness < 50 && i != bottomHeight) {
					gapPos.setY(bottomHeight);
					world.setBlockState(gapPos, world.getBlockState(pos));
					world.setBlockToAir(pos);
				}
				bottomHeight++;
			}
		}
	}

	private int[] doFallout(MutableBlockPos pos, double dist) {
		int stoneDepth = 0;
		int maxStoneDepth = 0;

		// Determine contamination depth based on distance
		if(dist > s1)
			maxStoneDepth = 0;
		else if(dist > s2)
			maxStoneDepth = 1;
		else if(dist > s3)
			maxStoneDepth = 2;
		else if(dist > s4)
			maxStoneDepth = 3;
		else if(dist > s5)
			maxStoneDepth = 4;
		else if(dist > s6)
			maxStoneDepth = 5;
		else if(dist <= s6)
			maxStoneDepth = 6;

		boolean lastReachedStone = false;
		boolean reachedStone = false;
		int contactHeight = 420;
		int lastGapHeight = 420;
		boolean gapFound = false;

		IBlockState b;
		Block bblock;
		Material bmaterial;

		for(int y = 255; y >= 0; y--) {
			pos.setY(y);
			b = world.getBlockState(pos);
			bblock = b.getBlock();
			bmaterial = b.getMaterial();
			lastReachedStone = reachedStone;

			if(bblock != Blocks.AIR && contactHeight == 420)
				contactHeight = Math.min(y + 1, 255);

			if(reachedStone && bmaterial != Material.AIR) {
				stoneDepth++;
			} else {
				reachedStone = b.getMaterial() == Material.ROCK;
			}

			if(reachedStone && stoneDepth > maxStoneDepth) {
				break;
			}

			if(bmaterial == Material.AIR || bmaterial.isLiquid()) {
				if(y < contactHeight) {
					gapFound = true;
					lastGapHeight = y;
				}
				continue;
			}

			if(bblock == Blocks.BEDROCK || bblock == ModBlocks.ore_bedrock_oil || bblock == ModBlocks.ore_bedrock_block) {
				if(world.isAirBlock(pos.up())) world.setBlockState(pos.up(), ModBlocks.toxic_block.getDefaultState());
				break;
			}

			// Deposit fallout particles on surface
			if(y == contactHeight - 1 && bblock != ModBlocks.fallout && Math.abs(rand.nextGaussian() * (dist * dist) / (s0 * s0)) < 0.05 && rand.nextDouble() < 0.05 && ModBlocks.fallout.canPlaceBlockAt(world, pos.up())) {
				placeBlockFromDist(dist, ModBlocks.fallout, pos.up());
			}

			// Process contaminated blocks
			if(bblock == ModBlocks.waste_leaves) {
				if(!(dist > s1 || (dist > fallingRadius && (world.rand.nextFloat() < (-5F * (fallingRadius / dist) + 5F))))) {
					world.setBlockToAir(pos);
				}
				continue;
			}

			if(bblock instanceof BlockLeaves) {
				if(dist > s1 || (dist > fallingRadius && (world.rand.nextFloat() < (-5F * (fallingRadius / dist) + 5F)))) {
					world.setBlockState(pos, ModBlocks.waste_leaves.getDefaultState());
				} else {
					world.setBlockToAir(pos);
				}
				continue;
			}

			if(bblock == Blocks.BROWN_MUSHROOM || bblock == Blocks.RED_MUSHROOM) {
				if(dist < s0)
					world.setBlockState(pos, ModBlocks.mush.getDefaultState());
				continue;
			}

			if(bblock instanceof BlockOre && reachedStone && !lastReachedStone && dist < s1) {
				world.setBlockState(pos, ModBlocks.toxic_block.getDefaultState());
				continue;
			}

			else if(bblock instanceof BlockStone || bblock == Blocks.COBBLESTONE) {
				double ranDist = dist * (1D + world.rand.nextDouble() * 0.1D);
				if(ranDist > s1 || stoneDepth == maxStoneDepth)
					world.setBlockState(pos, ModBlocks.sellafield_slaked.getStateFromMeta(world.rand.nextInt(4)));
				else if(ranDist > s2 || stoneDepth == maxStoneDepth - 1)
					world.setBlockState(pos, ModBlocks.sellafield_0.getStateFromMeta(world.rand.nextInt(4)));
				else if(ranDist > s3 || stoneDepth == maxStoneDepth - 2)
					world.setBlockState(pos, ModBlocks.sellafield_1.getStateFromMeta(world.rand.nextInt(4)));
				else if(ranDist > s4 || stoneDepth == maxStoneDepth - 3)
					world.setBlockState(pos, ModBlocks.sellafield_2.getStateFromMeta(world.rand.nextInt(4)));
				else if(ranDist > s5 || stoneDepth == maxStoneDepth - 4)
					world.setBlockState(pos, ModBlocks.sellafield_3.getStateFromMeta(world.rand.nextInt(4)));
				else if(ranDist > s6 || stoneDepth == maxStoneDepth - 5)
					world.setBlockState(pos, ModBlocks.sellafield_4.getStateFromMeta(world.rand.nextInt(4)));
				else if(ranDist <= s6 || stoneDepth == maxStoneDepth - 6)
					world.setBlockState(pos, ModBlocks.sellafield_core.getStateFromMeta(world.rand.nextInt(4)));
				else
					break;
				continue;

			} else if(bblock instanceof BlockGrass) {
				placeBlockFromDist(dist, ModBlocks.waste_earth, pos);
				continue;

			} else if(bblock instanceof BlockGravel) {
				placeBlockFromDist(dist, ModBlocks.waste_gravel, pos);
				continue;

			} else if(bblock instanceof BlockDirt) {
				BlockDirt.DirtType meta = b.getValue(BlockDirt.VARIANT);
				if(meta == BlockDirt.DirtType.DIRT)
					placeBlockFromDist(dist, ModBlocks.waste_dirt, pos);
				else if(meta == BlockDirt.DirtType.COARSE_DIRT)
					placeBlockFromDist(dist, ModBlocks.waste_gravel, pos);
				else if(meta == BlockDirt.DirtType.PODZOL)
					placeBlockFromDist(dist, ModBlocks.waste_mycelium, pos);
				continue;
			} else if(bblock == Blocks.FARMLAND) {
				placeBlockFromDist(dist, ModBlocks.waste_dirt, pos);
				continue;
			} else if(bblock instanceof BlockSnow) {
				placeBlockFromDist(dist, ModBlocks.waste_snow, pos);
				continue;

			} else if(bblock instanceof BlockSnowBlock) {
				placeBlockFromDist(dist, ModBlocks.waste_snow_block, pos);
				continue;

			} else if(bblock instanceof BlockIce) {
				world.setBlockState(pos, ModBlocks.waste_ice.getDefaultState());
				continue;

			} else if(bblock instanceof BlockBush) {
				if(world.getBlockState(pos.down()).getBlock() == Blocks.FARMLAND) {
					placeBlockFromDist(dist, ModBlocks.waste_dirt, pos.down());
					placeBlockFromDist(dist, ModBlocks.waste_grass_tall, pos);
				} else if(world.getBlockState(pos.down()).getBlock() instanceof BlockGrass) {
					placeBlockFromDist(dist, ModBlocks.waste_earth, pos.down());
					placeBlockFromDist(dist, ModBlocks.waste_grass_tall, pos);
				} else if(world.getBlockState(pos.down()).getBlock() == Blocks.MYCELIUM) {
					placeBlockFromDist(dist, ModBlocks.waste_mycelium, pos.down());
					world.setBlockState(pos, ModBlocks.mush.getDefaultState());
				}
				continue;

			} else if(bblock == Blocks.MYCELIUM) {
				placeBlockFromDist(dist, ModBlocks.waste_mycelium, pos);
				continue;

			} else if(bblock == Blocks.SANDSTONE) {
				placeBlockFromDist(dist, ModBlocks.waste_sandstone, pos);
				continue;
			} else if(bblock == Blocks.RED_SANDSTONE) {
				placeBlockFromDist(dist, ModBlocks.waste_sandstone_red, pos);
				continue;
			} else if(bblock == Blocks.HARDENED_CLAY || bblock == Blocks.STAINED_HARDENED_CLAY) {
				placeBlockFromDist(dist, ModBlocks.waste_terracotta, pos);
				continue;
			} else if(bblock instanceof BlockSand) {
				BlockSand.EnumType meta = b.getValue(BlockSand.VARIANT);
				if(rand.nextInt(60) == 0) {
					placeBlockFromDist(dist, meta == BlockSand.EnumType.SAND ? ModBlocks.waste_trinitite : ModBlocks.waste_trinitite_red, pos);
				} else {
					placeBlockFromDist(dist, meta == BlockSand.EnumType.SAND ? ModBlocks.waste_sand : ModBlocks.waste_sand_red, pos);
				}
				continue;
			}

			else if(bblock == Blocks.CLAY) {
				world.setBlockState(pos, Blocks.HARDENED_CLAY.getDefaultState());
				continue;
			}

			else if(bblock == Blocks.MOSSY_COBBLESTONE) {
				world.setBlockState(pos, Blocks.COAL_ORE.getDefaultState());
				continue;
			}

			else if(bblock == Blocks.COAL_ORE) {
				if(dist < s5) {
					int ra = rand.nextInt(150);
					if(ra < 7) {
						world.setBlockState(pos, Blocks.DIAMOND_ORE.getDefaultState());
					} else if(ra < 10) {
						world.setBlockState(pos, Blocks.EMERALD_ORE.getDefaultState());
					}
				}
				continue;
			}

			else if(bblock == Blocks.BROWN_MUSHROOM_BLOCK || bblock == Blocks.RED_MUSHROOM_BLOCK) {
				if(dist < s0) {
					BlockHugeMushroom.EnumType meta = b.getValue(BlockHugeMushroom.VARIANT);
					if(meta == BlockHugeMushroom.EnumType.STEM) {
						world.setBlockState(pos, ModBlocks.mush_block_stem.getDefaultState());
					} else {
						world.setBlockState(pos, ModBlocks.mush_block.getDefaultState());
					}
				}
				continue;
			}

			else if(bblock instanceof BlockLog) {
				if(dist < s0)
					world.setBlockState(pos, ((WasteLog)ModBlocks.waste_log).getSameRotationState(b));
				continue;
			}

			else if(bmaterial == Material.WOOD && bblock != ModBlocks.waste_log && bblock != ModBlocks.waste_planks) {
				if(dist < s0)
					world.setBlockState(pos, ModBlocks.waste_planks.getDefaultState());
				continue;
			}

			// Enhanced transmutation effects
			else if(b.getBlock() == ModBlocks.sellafield_4) {
				world.setBlockState(pos, ModBlocks.sellafield_core.getStateFromMeta(world.rand.nextInt(4)));
				continue;
			}
			else if(b.getBlock() == ModBlocks.sellafield_3) {
				world.setBlockState(pos, ModBlocks.sellafield_4.getStateFromMeta(world.rand.nextInt(4)));
				continue;
			}
			else if(b.getBlock() == ModBlocks.sellafield_2) {
				world.setBlockState(pos, ModBlocks.sellafield_3.getStateFromMeta(world.rand.nextInt(4)));
				continue;
			}
			else if(b.getBlock() == ModBlocks.sellafield_1) {
				world.setBlockState(pos, ModBlocks.sellafield_2.getStateFromMeta(world.rand.nextInt(4)));
				continue;
			}
			else if(b.getBlock() == ModBlocks.sellafield_0) {
				world.setBlockState(pos, ModBlocks.sellafield_1.getStateFromMeta(world.rand.nextInt(4)));
				continue;
			}
			else if(b.getBlock() == ModBlocks.sellafield_slaked) {
				world.setBlockState(pos, ModBlocks.sellafield_0.getStateFromMeta(world.rand.nextInt(4)));
				continue;
			}
			else if(b.getBlock() == Blocks.VINE) {
				world.setBlockToAir(pos);
				continue;
			}

			// Neutron-induced transmutation of uranium ores
			else if(bblock == ModBlocks.ore_uranium) {
				if(dist <= s5) {
					if(rand.nextInt(VersatileConfig.getSchrabOreChance()) == 0 || dist < s6)
						world.setBlockState(pos, ModBlocks.ore_schrabidium.getDefaultState());
					else
						world.setBlockState(pos, ModBlocks.ore_uranium_scorched.getDefaultState());
				}
				break;
			}

			else if(bblock == ModBlocks.ore_nether_uranium) {
				if(dist <= s5) {
					if(rand.nextInt(VersatileConfig.getSchrabOreChance()) == 0)
						world.setBlockState(pos, ModBlocks.ore_nether_schrabidium.getDefaultState());
					else
						world.setBlockState(pos, ModBlocks.ore_nether_uranium_scorched.getDefaultState());
				}
				break;
			}

			else if(bblock == ModBlocks.ore_gneiss_uranium) {
				if(dist <= s4) {
					if(rand.nextInt(VersatileConfig.getSchrabOreChance()) == 0)
						world.setBlockState(pos, ModBlocks.ore_gneiss_schrabidium.getDefaultState());
					else
						world.setBlockState(pos, ModBlocks.ore_gneiss_uranium_scorched.getDefaultState());
				}
				break;
			}
			else if(bblock == ModBlocks.brick_concrete) {
				if(rand.nextInt(80) == 0)
					world.setBlockState(pos, ModBlocks.brick_concrete_broken.getDefaultState());
				break;
			}
			else if(bblock.getExplosionResistance(null) > 300) {
				break;
			}
		}
		return new int[]{gapFound ? 1 : 0, lastGapHeight, contactHeight};
	}

	private int[] doNoFallout(MutableBlockPos pos, double dist) {
		int stoneDepth = 0;
		int maxStoneDepth = 6;

		boolean lastReachedStone = false;
		boolean reachedStone = false;
		int contactHeight = 420;
		int lastGapHeight = 420;
		boolean gapFound = false;

		for(int y = 255; y >= 0; y--) {
			pos.setY(y);
			IBlockState b = world.getBlockState(pos);
			Block bblock = b.getBlock();
			Material bmaterial = b.getMaterial();
			lastReachedStone = reachedStone;

			if(bblock.isCollidable() && contactHeight == 420)
				contactHeight = Math.min(y + 1, 255);

			if(reachedStone && bmaterial != Material.AIR) {
				stoneDepth++;
			} else {
				reachedStone = b.getMaterial() == Material.ROCK;
			}

			if(reachedStone && stoneDepth > maxStoneDepth) {
				break;
			}

			if(bmaterial == Material.AIR || bmaterial.isLiquid()) {
				if(y < contactHeight) {
					gapFound = true;
					lastGapHeight = y;
				}
			}
		}
		return new int[]{gapFound ? 1 : 0, lastGapHeight, contactHeight};
	}

	public void placeBlockFromDist(double dist, Block b, BlockPos pos) {
		double ranDist = dist * (1D + world.rand.nextDouble() * 0.2);
		if(ranDist > s1)
			world.setBlockState(pos, b.getStateFromMeta(0));
		else if(ranDist > s2)
			world.setBlockState(pos, b.getStateFromMeta(1));
		else if(ranDist > s3)
			world.setBlockState(pos, b.getStateFromMeta(2));
		else if(ranDist > s4)
			world.setBlockState(pos, b.getStateFromMeta(3));
		else if(ranDist > s5)
			world.setBlockState(pos, b.getStateFromMeta(4));
		else if(ranDist > s6)
			world.setBlockState(pos, b.getStateFromMeta(5));
		else if(ranDist <= s6)
			world.setBlockState(pos, b.getStateFromMeta(6));
	}

	private void flood(MutableBlockPos pos) {
		if(CompatibilityConfig.doFillCraterWithWater && waterLevel > 1) {
			for(int y = waterLevel - 1; y > 1; y--) {
				pos.setY(y);
				if(world.isAirBlock(pos) || world.getBlockState(pos).getBlock() == Blocks.FLOWING_WATER) {
					world.setBlockState(pos, Blocks.WATER.getDefaultState());
				}
			}
		}
	}

	private void drain(MutableBlockPos pos) {
		for(int y = 255; y > 1; y--) {
			pos.setY(y);
			if(!world.isAirBlock(pos) && (world.getBlockState(pos).getBlock() == Blocks.WATER || world.getBlockState(pos).getBlock() == Blocks.FLOWING_WATER)) {
				world.setBlockToAir(pos);
			}
		}
	}

	private void stomp(MutableBlockPos pos, double dist) {
		if(dist > s0) {
			if(world.rand.nextFloat() > 0.05F + (5F * (s0 / dist) - 4F)) {
				return;
			}
		}

		int[] gapData;
		if(doFallout)
			gapData = doFallout(pos, dist);
		else
			gapData = doNoFallout(pos, dist);

		if(dist < fallingRadius) {
			if(doDrop && gapData[0] == 1)
				letFall(world, pos, gapData[1], gapData[2]);
			if(doFlood)
				flood(pos);
			else
				drain(pos);
		}
	}

	@Override
	protected void readEntityFromNBT(NBTTagCompound nbt) {
		setScale(nbt.getInteger("scale"), nbt.getInteger("dropRadius"));
		if(nbt.hasKey("chunks"))
			chunksToProcess.addAll(readChunksFromIntArray(nbt.getIntArray("chunks")));
		if(nbt.hasKey("outerChunks"))
			outerChunksToProcess.addAll(readChunksFromIntArray(nbt.getIntArray("outerChunks")));
		doFallout = nbt.getBoolean("doFallout");
		doFlood = nbt.getBoolean("doFlood");
		if(nbt.hasKey("chunksDone")) {
			chunksDone = nbt.getBoolean("chunksDone");
			if(chunksDone) this.dataManager.set(DRIFT_PHASE, true);
		}
		if(nbt.hasKey("driftTick")) driftTick = nbt.getInteger("driftTick");
	}

	private Collection<Long> readChunksFromIntArray(int[] data) {
		List<Long> coords = new ArrayList<>();
		boolean firstPart = true;
		int x = 0;
		for(int coord : data) {
			if(firstPart)
				x = coord;
			else
				coords.add(ChunkPos.asLong(x, coord));
			firstPart = !firstPart;
		}
		return coords;
	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt) {
		nbt.setInteger("scale", getScale());
		nbt.setInteger("dropRadius", fallingRadius);
		nbt.setBoolean("doFallout", doFallout);
		nbt.setBoolean("doFlood", doFlood);
		nbt.setBoolean("chunksDone", chunksDone);
		nbt.setInteger("driftTick", driftTick);

		nbt.setIntArray("chunks", writeChunksToIntArray(chunksToProcess));
		nbt.setIntArray("outerChunks", writeChunksToIntArray(outerChunksToProcess));
	}

	private int[] writeChunksToIntArray(List<Long> coords) {
		int[] data = new int[coords.size() * 2];
		for(int i = 0; i < coords.size(); i++) {
			data[i * 2] = (int)(coords.get(i) & Integer.MAX_VALUE);
			data[i * 2 + 1] = (int)(coords.get(i) >> 32 & Integer.MAX_VALUE);
		}
		return data;
	}

	public void setScale(int i, int craterRadius) {
		this.dataManager.set(SCALE, i);
		this.s0 = 0.8D * i;
		this.s1 = 0.65D * i;
		this.s2 = 0.5D * i;
		this.s3 = 0.4D * i;
		this.s4 = 0.3D * i;
		this.s5 = 0.2D * i;
		this.s6 = 0.1D * i;
		this.fallingRadius = craterRadius > 15 ? craterRadius : 0;
		this.doDrop = this.fallingRadius > 20;
	}

	public int getScale() {
		int scale = this.dataManager.get(SCALE);
		return scale == 0 ? 1 : scale;
	}

	// -----------------------------------------------------------------------
	// ELLIPTICAL FALLOUT PATTERN (Glasstone & Dolan §9.83-9.93)
	// -----------------------------------------------------------------------

	/**
	 * Returns the wind-corrected effective distance for a position offset (dx, dz).
	 *
	 * Physical basis: Glasstone & Dolan Table 9.93 shows the fallout pattern is an
	 * elongated cigar shape.  The downwind extent is ~12–24× the crosswind half-width
	 * for typical wind speeds.  This method compresses the downwind axis by the
	 * elongation factor E so that zone comparisons against s0–s6 naturally produce
	 * the cigar shape.
	 *
	 * <ul>
	 *   <li>Downwind side (d_down ≥ 0): effectiveDist = √(d_cross² + (d_down/E)²)</li>
	 *   <li>Upwind  side (d_down < 0): effectiveDist = EuclideanDist × E × 0.5
	 *       — much shorter upwind extent (Glasstone §9.93: upwind ≈ half crosswind width).
	 *   </li>
	 * </ul>
	 *
	 * Wind speed → elongation E:
	 *   E = clamp(1.0 + windSpeedMs × 0.30,  1.5,  4.0)
	 *   PDF §9.97: F = 1 + (v−15)/60 for v > 15 mph; we adapt for m/s.
	 *
	 * Terrain shielding factor 0.7 (Glasstone §9.95, §9.101) is applied to all
	 * radiated values in depositFalloutRadiation(), not here.
	 *
	 * @param dx  block X offset from explosion centre
	 * @param dz  block Z offset from explosion centre
	 * @return    effective scalar distance for zone comparison
	 */
	private double getEffectiveDist(double dx, double dz) {
		WindField wind = WindField.get(this.world);
		double rad  = Math.toRadians(wind.windDirectionDeg);
		double downX = Math.sin(rad);
		double downZ = Math.cos(rad);

		// Decompose into downwind / crosswind components
		double dDown  =  dx * downX + dz * downZ;   // positive = downwind
		double dCross = -dx * downZ + dz * downX;   // perpendicular

		// Elongation ratio E: higher wind → more elongated plume (Glasstone §9.85)
		double E = Math.max(1.5, Math.min(4.0, 1.0 + wind.windSpeedMs * 0.30));

		if (dDown >= 0) {
			// Downwind side: elliptical (compressed downwind axis)
			return Math.sqrt(dCross * dCross + (dDown / E) * (dDown / E));
		} else {
			// Upwind side: semicircular but much shorter
			double r = Math.sqrt(dx * dx + dz * dz);
			return r * E * 0.5;
		}
	}

	// -----------------------------------------------------------------------
	// CRATER BIOME FORCING
	// -----------------------------------------------------------------------

	/**
	 * Forces the Nuclear Crater biome onto all chunk columns within the crater radius.
	 *
	 * Biome IDs are stored per-column (1×1 XZ) in {@link Chunk#getBiomeArray()}.
	 * We overwrite the byte values for positions within {@code craterRadius} and
	 * mark affected chunks dirty so the change is saved.
	 *
	 * This is called once (on firstTick) from onUpdate(), after the blast phase
	 * has already reshaped the terrain in Phase 3 of EntityNukeExplosionMK5.
	 *
	 * @param craterRadius radius in blocks within which the biome is overwritten
	 */
	private void forceCraterBiome(int craterRadius) {
		if (com.hbm.main.MainRegistry.biome_crater == null) return;
		int biomeId = Biome.getIdForBiome(com.hbm.main.MainRegistry.biome_crater);
		if (biomeId < 0) return;

		int cx = (int) posX;
		int cz = (int) posZ;
		int minCX = (cx - craterRadius) >> 4;
		int maxCX = (cx + craterRadius) >> 4;
		int minCZ = (cz - craterRadius) >> 4;
		int maxCZ = (cz + craterRadius) >> 4;

		double r2 = (double) craterRadius * craterRadius;

		for (int chunkX = minCX; chunkX <= maxCX; chunkX++) {
			for (int chunkZ = minCZ; chunkZ <= maxCZ; chunkZ++) {
				if (!world.isChunkGeneratedAt(chunkX, chunkZ)) continue;
				Chunk chunk = world.getChunk(chunkX, chunkZ);
				byte[] biomeArray = chunk.getBiomeArray();
				boolean changed = false;
				for (int bx = 0; bx < 16; bx++) {
					for (int bz = 0; bz < 16; bz++) {
						double dx = (chunkX * 16 + bx) - cx;
						double dz = (chunkZ * 16 + bz) - cz;
						if (dx * dx + dz * dz <= r2) {
							biomeArray[bz * 16 + bx] = (byte) biomeId;
							changed = true;
						}
					}
				}
				if (changed) chunk.markDirty();
			}
		}
	}
}