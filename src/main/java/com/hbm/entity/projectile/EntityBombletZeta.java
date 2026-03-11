package com.hbm.entity.projectile;

import java.util.ArrayList;
import java.util.List;

import com.hbm.config.BombConfig;
import com.hbm.entity.logic.EntityNukeExplosionMK5;
import com.hbm.entity.logic.IChunkLoader;
import com.hbm.explosion.ExplosionChaos;
import com.hbm.explosion.ExplosionLarge;
import com.hbm.explosion.ExplosionRealisticBomb;
import com.hbm.explosion.ExplosionRealisticBomb.BombType;
import com.hbm.interfaces.IConstantRenderer;
import com.hbm.lib.HBMSoundHandler;
import com.hbm.main.MainRegistry;
import com.hbm.entity.effect.EntityNukeTorex;

import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.ForgeChunkManager.Type;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class EntityBombletZeta extends EntityThrowable implements IConstantRenderer, IChunkLoader {

	public int type = 0;
	public BombType bombSize = BombType.BOMB_500LB; // Default to 500lb

	// Realistic bomb weights (affects fall rate and air resistance)
	// Weight factors: higher = heavier = faster fall, less air resistance
	private static final double WEIGHT_STANDARD = 1.0;   // Type 0: Standard 500lb bombs
	private static final double WEIGHT_NAPALM = 0.7;     // Type 1: Napalm bombs (lighter, more drag)
	private static final double WEIGHT_POISON = 0.6;     // Type 2: Gas canisters (light, dispersal design)
	private static final double WEIGHT_NUCLEAR = 2.0;    // Type 4: Nuclear bombs (very heavy, ~10,000 lbs)

	// Chunk loading for preventing despawn during fall
	// EntityMissileBaseAdvanced-style: loads only current chunk
	private Ticket loaderTicket;
	private List<ChunkPos> loadedChunks = new ArrayList<ChunkPos>(); // Used by deprecated loadNeighboringChunks()

	// Debug tracking
	private int debugLogCounter = 0;
	private double initialAltitude = -1;

	// Delayed fuse system - bomb embeds in ground then explodes after delay
	private boolean hasImpacted = false;
	private int fuseTimer = 0;
	private static final int FUSE_DELAY = 20; // 1 second (20 ticks)

	// Bomb group chunk loading - bomber UUID for group tracking
	private java.util.UUID bomberUUID = null;

	// Performance optimization for mass bombing (400+ bombs)
	private int physicsTickCounter = 0;             // Counter for physics calculation frequency
	private double cachedAirDensity = -1.0;         // Cached air density to avoid expensive exp() calls
	private int lastAirDensityUpdateAltitude = -1;  // Last altitude where air density was calculated

	// GAU8-style reduced logging (パフォーマンス最適化)
	private static long impactLogCounter = 0;
	private static long detonationLogCounter = 0;
	private static final int LOG_INTERVAL = 100; // 100発に1回ログ出力

	public EntityBombletZeta(World p_i1582_1_) {
		super(p_i1582_1_);
		this.ignoreFrustumCheck = true;
	}

	/**
	 * Set bomber UUID for group chunk loading
	 * When set, bomb will NOT load chunks individually - relies on bomber's pre-loaded chunks
	 */
	public void setBomberUUID(java.util.UUID bomberUUID) {
		this.bomberUUID = bomberUUID;
	}

	/**
	 * Set bomb size for realistic explosions
	 */
	public void setBombSize(BombType size) {
		this.bombSize = size;
	}

	/**
	 * Get bomb mass in kg for physics calculations
	 */
	public double getBombMassKg() {
		return bombSize.totalWeightKg;
	}

	/**
	 * Initialize entity - EntityMissileBaseAdvanced-style chunk loading
	 * Each bomb loads only its current chunk (not 3x3 grid)
	 * More efficient - reduces lag by minimizing forced chunks
	 */
	@Override
	protected void entityInit() {
		super.entityInit();

		// Request chunk loading ticket for this bomb
		// EntityMissileBaseAdvanced-style: loads only current chunk
		if (!world.isRemote) {
			this.forceSpawn = true;
			Ticket ticket = ForgeChunkManager.requestTicket(MainRegistry.instance, world, Type.ENTITY);
			if(ticket != null) {
				init(ticket);
			}
		}
	}

	/**
	 * Initialize chunk loader with ticket
	 * EntityMissileBaseAdvanced-style: Each bomb loads only its current chunk
	 */
	@Override
	public void init(Ticket ticket) {
		if(!world.isRemote && ticket != null && loaderTicket == null) {
			loaderTicket = ticket;
			loaderTicket.bindEntity(this);
			loaderTicket.getModData();
		}
	}

	/**
	 * Load chunks around bomb to prevent despawn during fall
	 * NOTE: This method is kept for compatibility but is no longer used
	 * EntityMissileBaseAdvanced-style loading uses loadMainChunk() instead
	 */
	@Override
	public void loadNeighboringChunks(int newChunkX, int newChunkZ) {
		if(!world.isRemote && loaderTicket != null) {
			// Unload old chunks
			for(ChunkPos chunk : loadedChunks) {
				ForgeChunkManager.unforceChunk(loaderTicket, chunk);
			}

			loadedChunks.clear();

			// Load 3x3 chunk grid around bomb
			loadedChunks.add(new ChunkPos(newChunkX, newChunkZ));
			loadedChunks.add(new ChunkPos(newChunkX + 1, newChunkZ + 1));
			loadedChunks.add(new ChunkPos(newChunkX - 1, newChunkZ - 1));
			loadedChunks.add(new ChunkPos(newChunkX + 1, newChunkZ - 1));
			loadedChunks.add(new ChunkPos(newChunkX - 1, newChunkZ + 1));
			loadedChunks.add(new ChunkPos(newChunkX + 1, newChunkZ));
			loadedChunks.add(new ChunkPos(newChunkX, newChunkZ + 1));
			loadedChunks.add(new ChunkPos(newChunkX - 1, newChunkZ));
			loadedChunks.add(new ChunkPos(newChunkX, newChunkZ - 1));

			// Force load new chunks
			for(ChunkPos chunk : loadedChunks) {
				ForgeChunkManager.forceChunk(loaderTicket, chunk);
			}
		}
	}

	/**
	 * EntityMissileBaseAdvanced-style chunk loading
	 * Loads only the current chunk the bomb is in, unloads previous chunk when moving
	 * More efficient than 3x3 grid - reduces lag by minimizing forced chunks
	 */
	private ChunkPos mainChunk;
	public void loadMainChunk() {
		if(!world.isRemote && loaderTicket != null) {
			ChunkPos currentChunk = new ChunkPos((int) Math.floor(this.posX / 16D), (int) Math.floor(this.posZ / 16D));
			if(mainChunk == null) {
				// First time - load initial chunk
				ForgeChunkManager.forceChunk(loaderTicket, currentChunk);
				this.mainChunk = currentChunk;
			} else if(!mainChunk.equals(currentChunk)) {
				// Moved to new chunk - load new, unload old
				ForgeChunkManager.forceChunk(loaderTicket, currentChunk);
				ForgeChunkManager.unforceChunk(loaderTicket, this.mainChunk);
				this.mainChunk = currentChunk;
			}
		}
	}

	/**
	 * Unload the main chunk when bomb is removed
	 */
	public void unloadMainChunk() {
		if(!world.isRemote && loaderTicket != null && this.mainChunk != null) {
			ForgeChunkManager.unforceChunk(loaderTicket, this.mainChunk);
		}
	}

	@Override
	public void onUpdate() {
		// === DELAYED FUSE TIMER ===
		// If bomb has impacted, count down to detonation
		if(hasImpacted) {
			fuseTimer++;
			if(fuseTimer >= FUSE_DELAY) {
				if(!this.world.isRemote) {
					// Detonate after delay
					detonateBomb();
				}
				this.setDead();
			}
			return; // Don't process physics while waiting to detonate
		}

		// EntityMissileBaseAdvanced-style chunk loading
		// Load only the current chunk the bomb is in
		loadMainChunk();

		// DEBUG: Track initial altitude
		if(initialAltitude < 0 && !world.isRemote) {
			initialAltitude = posY;
		}

		// DEBUG: Log trajectory every 2 seconds (40 ticks) during fall
		if(!world.isRemote && debugLogCounter % 40 == 0 && posY > 10) {
			double currentVelocity = Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ) * 20.0;
			double horizontalVelocity = Math.sqrt(motionX * motionX + motionZ * motionZ) * 20.0;
			double verticalVelocity = Math.abs(motionY) * 20.0;
			double altitudeLost = initialAltitude - posY;
			double fallTime = debugLogCounter / 20.0;

			com.hbm.main.MainRegistry.logger.info(">>> BOMB TRAJECTORY: T+" + String.format("%.1f", fallTime) + "s | Alt:" + (int)posY + "m (" + (int)altitudeLost + "m fallen) | Pos:(" + (int)posX + "," + (int)posZ + ") | Vel:" + String.format("%.1f", currentVelocity) + "m/s (H:" + String.format("%.1f", horizontalVelocity) + " V:" + String.format("%.1f", verticalVelocity) + ")");
		}
		debugLogCounter++;

		this.lastTickPosX = this.prevPosX = posX;
		this.lastTickPosY = this.prevPosY = posY;
		this.lastTickPosZ = this.prevPosZ = posZ;
		this.setPosition(posX + this.motionX, posY + this.motionY, posZ + this.motionZ);

		/*this.prevPosX = this.posX;
		this.prevPosY = this.posY;
		this.prevPosZ = this.posZ;

		this.posX += this.motionX;
		this.posY += this.motionY;
		this.posZ += this.motionZ;*/

		// === REALISTIC WW2 BOMB PHYSICS ===
		// Based on actual WW2 bombing data: 45 seconds fall from 10,000m
		// Terminal velocity: 320-364 m/s
		// Sources: WW2Aircraft.net Forums, USAAF ballistic tables

		// Get actual bomb mass in kg
		double massKg = bombSize.totalWeightKg;

		// Minecraft scaling factor: 1 block = 1 meter
		// Real gravity: 9.81 m/s², but scaled for Minecraft tick rate
		// 1 second = 20 ticks, so acceleration per tick² = g / 400
		double gravity = 9.81 / 400.0; // m/tick² (scaled for Minecraft)

		// REALISTIC DRAG PARAMETERS from WW2 bomb data
		// Mk 82 / AN-M64 500lb GP bomb specifications
		double dragCoefficient = 0.28; // Streamlined WW2 bomb shape

		// EFFECTIVE cross-sectional area including fins and form drag (m²)
		// Physical cross-section ~0.04 m², but effective drag area is larger
		double crossSectionArea;
		if(massKg < 300) {
			crossSectionArea = 0.20; // 500lb bombs (effective drag area)
		} else if(massKg < 600) {
			crossSectionArea = 0.30; // 1000lb bombs (effective drag area)
		} else {
			crossSectionArea = 0.40; // 2000lb bombs (effective drag area)
		}

		// === PERFORMANCE OPTIMIZATION: Adaptive physics calculation frequency ===
		// At high altitude (>7000m), calculate drag every 3 ticks (still accurate, 3x faster)
		// At medium altitude (3000-7000m), calculate every 2 ticks
		// At low altitude (<3000m), calculate every tick (maximum accuracy near ground)
		physicsTickCounter++;
		int physicsFrequency = 1; // Default: every tick
		if(posY > 7000) {
			physicsFrequency = 3; // High altitude: every 3 ticks
		} else if(posY > 3000) {
			physicsFrequency = 2; // Medium altitude: every 2 ticks
		}

		boolean shouldCalculateDrag = (physicsTickCounter % physicsFrequency == 0);

		// EXPONENTIAL AIR DENSITY MODEL - Barometric formula (CACHED for performance)
		// ρ(h) = ρ₀ × exp(-h/H) where H = 8400m (scale height)
		// Only recalculate when altitude changes significantly (>100m)
		double airDensity;
		if(cachedAirDensity < 0 || Math.abs(posY - lastAirDensityUpdateAltitude) > 100) {
			double rho0 = 1.225; // Sea level density (kg/m³)
			double scaleHeight = 8400.0; // Atmospheric scale height (m)
			cachedAirDensity = rho0 * Math.exp(-posY / scaleHeight);
			lastAirDensityUpdateAltitude = (int)posY;
		}
		airDensity = cachedAirDensity;

		// Calculate velocity in m/s for physics calculations
		double velocityXMS = motionX * 20.0;
		double velocityYMS = motionY * 20.0;
		double velocityZMS = motionZ * 20.0;
		double velocityMagnitudeMS = Math.sqrt(velocityXMS * velocityXMS + velocityYMS * velocityYMS + velocityZMS * velocityZMS);

		// === VERTICAL PHYSICS ===
		// Apply gravitational acceleration
		this.motionY -= gravity;

		// Calculate terminal velocity at current altitude
		// v_t = sqrt((2 * m * g) / (ρ * C_d * A))
		double terminalVelocityMPS = Math.sqrt((2.0 * massKg * 9.81) / (airDensity * dragCoefficient * crossSectionArea));
		double terminalVelocity = terminalVelocityMPS / 20.0; // Convert to blocks/tick

		// Apply vertical drag using drag equation (OPTIMIZED: frequency-based calculation)
		// F_drag = 0.5 * ρ * v² * C_d * A
		// a_drag = F / m
		if(shouldCalculateDrag && Math.abs(velocityYMS) > 0.001) {
			double dragForce = 0.5 * airDensity * velocityYMS * velocityYMS * dragCoefficient * crossSectionArea;
			double dragAccelMS2 = dragForce / massKg; // m/s²
			double dragAccelTick2 = dragAccelMS2 / 400.0 * physicsFrequency; // Scale by frequency

			// Apply drag in opposite direction of motion
			if(this.motionY < 0) {
				this.motionY += dragAccelTick2; // Drag opposes downward motion
			} else {
				this.motionY -= dragAccelTick2; // Drag opposes upward motion
			}
		}

		// Limit to terminal velocity as safety check
		if(Math.abs(this.motionY) > terminalVelocity) {
			this.motionY = -terminalVelocity * Math.signum(this.motionY);
		}

		// === HORIZONTAL PHYSICS (OPTIMIZED: frequency-based calculation) ===
		// Apply horizontal air drag
		double horizSpeedMS = Math.sqrt(velocityXMS * velocityXMS + velocityZMS * velocityZMS);
		if(shouldCalculateDrag && horizSpeedMS > 0.001) {
			// Horizontal drag uses reduced effective area (streamlined in forward direction)
			double horizDragArea = crossSectionArea * 0.08; // 8% of full area for horizontal

			// Calculate drag force
			double dragForce = 0.5 * airDensity * horizSpeedMS * horizSpeedMS * dragCoefficient * horizDragArea;
			double dragAccelMS2 = dragForce / massKg;
			double dragAccelTick2 = dragAccelMS2 / 400.0;

			// Apply drag proportionally to each horizontal component (scaled by frequency)
			double dragFraction = dragAccelTick2 / (horizSpeedMS / 20.0); // Fraction of velocity to remove
			dragFraction = Math.min(0.05 * physicsFrequency, dragFraction * physicsFrequency); // Scale and limit

			this.motionX *= (1.0 - dragFraction * (Math.abs(velocityXMS) / horizSpeedMS));
			this.motionZ *= (1.0 - dragFraction * (Math.abs(velocityZMS) / horizSpeedMS));
		}
        
        this.rotation();

        // === GROUND IMPACT DETECTION ===
        // Check if bomb has hit solid ground
        BlockPos pos = new BlockPos((int)this.posX, (int)this.posY, (int)this.posZ);
        if(this.world.getBlockState(pos).getBlock() != Blocks.AIR)
        {
    		if(!this.world.isRemote && !hasImpacted)
    		{
    			// DEBUG: Log bomb impact point
    			double impactVelocity = Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ) * 20.0; // blocks/sec
    			String bombTypeName = "Unknown";
    			switch(type) {
    				case 0: bombTypeName = "Standard (" + bombSize.displayName + ")"; break;
    				case 1: bombTypeName = "Napalm"; break;
    				case 2: bombTypeName = "Poison Gas"; break;
    				case 4: bombTypeName = "Nuclear"; break;
    				default: bombTypeName = "Type " + type; break;
    			}

    			double fallTime = debugLogCounter / 20.0;
    			double altitudeFallen = initialAltitude > 0 ? (initialAltitude - posY) : 0;
    			double horizontalVelocity = Math.sqrt(motionX * motionX + motionZ * motionZ) * 20.0;
    			double verticalVelocity = Math.abs(motionY) * 20.0;

    			// GAU8-style reduced logging (100発に1回のみ - パフォーマンス最適化)
    			impactLogCounter++;
    			boolean shouldLog = (impactLogCounter % LOG_INTERVAL == 0);

    			if(shouldLog) {
    				com.hbm.main.MainRegistry.logger.info("===== BOMB IMPACT (" + impactLogCounter + " total) =====");
    				com.hbm.main.MainRegistry.logger.info("Type: " + bombTypeName);
    				com.hbm.main.MainRegistry.logger.info("Impact Location: X=" + (int)posX + " Y=" + (int)posY + " Z=" + (int)posZ);
    				com.hbm.main.MainRegistry.logger.info("Fall Stats: " + String.format("%.1f", fallTime) + "s fall time | " + (int)altitudeFallen + "m altitude lost");
    				com.hbm.main.MainRegistry.logger.info("Impact Velocity: " + String.format("%.2f", impactVelocity) + " m/s (H:" + String.format("%.1f", horizontalVelocity) + " V:" + String.format("%.1f", verticalVelocity) + ")");
    				com.hbm.main.MainRegistry.logger.info("Velocity Vector: X=" + String.format("%.3f", motionX * 20) + " Y=" + String.format("%.3f", motionY * 20) + " Z=" + String.format("%.3f", motionZ * 20));
    				if(type == 0) {
    					com.hbm.main.MainRegistry.logger.info("Bomb Mass: " + (int)bombSize.totalWeightKg + " kg");
    				}
    				com.hbm.main.MainRegistry.logger.info("Fuse armed - detonation in 1 second...");
    				com.hbm.main.MainRegistry.logger.info("=======================");
    			}

    			// Embed bomb in ground - stop all motion
    			this.motionX = 0;
    			this.motionY = 0;
    			this.motionZ = 0;

    			// Arm delayed fuse
    			hasImpacted = true;
    			fuseTimer = 0;
    		}
        }
	}

	/**
	 * Detonate bomb after delay - separated into method for clarity
	 */
	private void detonateBomb() {
		BlockPos pos = new BlockPos((int)this.posX, (int)this.posY, (int)this.posZ);

		if(type == 0) {
			// Use realistic TNT-based explosion system
			ExplosionRealisticBomb.logExplosion(bombSize, this.posX, this.posY, this.posZ);
			ExplosionRealisticBomb.explode(world, this.posX + 0.5F, this.posY + 0.5F, this.posZ + 0.5F,
				bombSize, true, true, true);
        	world.playSound((double)(posX + 0.5F), (double)(posY + 0.5F), (double)(posZ + 0.5F), HBMSoundHandler.bombDet, SoundCategory.HOSTILE, 25.0F, 0.8F + rand.nextFloat() * 0.4F, true);
		}
		if(type == 1) {
			ExplosionLarge.explode(world, this.posX + 0.5F, this.posY + 0.5F, this.posZ + 0.5F, 2.5F, false, false, false);
			ExplosionChaos.burn(world, pos, 9);
			ExplosionChaos.flameDeath(world, pos, 14);
        	world.playSound((double)(posX + 0.5F), (double)(posY + 0.5F), (double)(posZ + 0.5F), HBMSoundHandler.bombDet, SoundCategory.HOSTILE, 25.0F, 1.0F, true);

        	for(int i = 0; i < 5; i++)
        		ExplosionLarge.spawnBurst(world, this.posX + 0.5F, this.posY + 1.0F, this.posZ + 0.5F, rand.nextInt(10) + 15, rand.nextFloat() * 2 + 2);
		}
		if(type == 2) {
        	world.playSound((double)(posX + 0.5F), (double)(posY + 0.5F), (double)(posZ + 0.5F), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.HOSTILE, 5.0F, 2.6F + (rand.nextFloat() - rand.nextFloat()) * 0.8F, true);
			ExplosionChaos.spawnChlorine(world, this.posX + 0.5F - motionX, this.posY + 0.5F - motionY, this.posZ + 0.5F - motionZ, 75, 2, 0);
		}
		if(type == 4) {
			world.spawnEntity(EntityNukeExplosionMK5.statFac(world, (int) (BombConfig.fatmanRadius * 1.5), posX, posY, posZ).mute());

			if(BombConfig.enableNukeClouds) {
				EntityNukeTorex.statFac(world, this.posX, this.posY, this.posZ, (int) (BombConfig.fatmanRadius * 1.5));
			}
			world.playSound(null, posX, posY, posZ, HBMSoundHandler.mukeExplosion, SoundCategory.HOSTILE, 15.0F, 1.0F);
		}

		// Notify bomber that bomb has detonated (for group chunk loading cleanup)
		if(!world.isRemote && bomberUUID != null) {
			// Find bomber entity by UUID
			for(net.minecraft.entity.Entity entity : world.loadedEntityList) {
				if(entity instanceof com.hbm.entity.logic.EntityBomber) {
					com.hbm.entity.logic.EntityBomber bomber = (com.hbm.entity.logic.EntityBomber) entity;
					if(bomber.getUniqueID().equals(bomberUUID)) {
						bomber.notifyBombDetonation(this.getUniqueID());
						break;
					}
				}
			}
		}
	}
	

	public void rotation() {
        float f2 = MathHelper.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);
        this.rotationYaw = (float)(Math.atan2(this.motionX, this.motionZ) * 180.0D / Math.PI);

        for (this.rotationPitch = (float)(Math.atan2(this.motionY, f2) * 180.0D / Math.PI) - 90; this.rotationPitch - this.prevRotationPitch < -180.0F; this.prevRotationPitch -= 360.0F)
        {
            ;
        }

        while (this.rotationPitch - this.prevRotationPitch >= 180.0F)
        {
            this.prevRotationPitch += 360.0F;
        }

        while (this.rotationYaw - this.prevRotationYaw < -180.0F)
        {
            this.prevRotationYaw -= 360.0F;
        }

        while (this.rotationYaw - this.prevRotationYaw >= 180.0F)
        {
            this.prevRotationYaw += 360.0F;
        }
	}

	@Override
	protected void onImpact(RayTraceResult p_70184_1_) {
	}
	
    @Override
	@SideOnly(Side.CLIENT)
    public boolean isInRangeToRenderDist(double distance)
    {
        return distance < 25000;
    }

	/**
	 * Release chunk loading ticket when bomb is destroyed
	 * EntityMissileBaseAdvanced-style cleanup
	 */
	@Override
	public void setDead() {
		super.setDead();
		if(!world.isRemote && loaderTicket != null) {
			// Unload main chunk
			unloadMainChunk();
			// Release ticket
			ForgeChunkManager.releaseTicket(loaderTicket);
			loaderTicket = null;
		}
	}
}
