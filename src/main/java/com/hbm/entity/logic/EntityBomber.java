package com.hbm.entity.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.hbm.config.CompatibilityConfig;
import com.hbm.config.GeneralConfig;
import com.hbm.entity.particle.EntityGasFlameFX;
import com.hbm.entity.projectile.EntityBombletZeta;
import com.hbm.entity.projectile.EntityBoxcar;
import com.hbm.entity.projectile.EntityRocketHoming;
import com.hbm.explosion.ExplosionChaos;
import com.hbm.explosion.ExplosionLarge;
import com.hbm.interfaces.IConstantRenderer;
import com.hbm.lib.HBMSoundHandler;
import com.hbm.physics.IRCSProvider;
import com.hbm.lib.ModDamageSource;
import com.hbm.main.MainRegistry;
import com.hbm.packet.LoopedEntitySoundPacket;
import com.hbm.packet.PacketDispatcher;

import net.minecraft.entity.Entity;
import net.minecraft.init.SoundEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.ForgeChunkManager.Type;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class EntityBomber extends Entity implements IChunkLoader, IConstantRenderer, IRCSProvider {

	public static final DataParameter<Integer> HEALTH = EntityDataManager.createKey(EntityBomber.class, DataSerializers.VARINT);
	public static final DataParameter<Byte> STYLE = EntityDataManager.createKey(EntityBomber.class, DataSerializers.BYTE);

	// Realistic B-29 parameters
	private static final double CRUISE_SPEED = 6.25; // 450 km/h in blocks/tick
	private static final double ESCAPE_SPEED = 9.028; // 650 km/h in blocks/tick
	private static final double FLIGHT_ALTITUDE_HIGH = 10000.0; // 10,000m high altitude for precision bombing
	private static final double FLIGHT_ALTITUDE_LOW = 1000.0; // 1,000m low altitude for gas deployment
	private static final double SPAWN_DISTANCE = 50000.0; // 50 km
	private static final double DESPAWN_DISTANCE = 50000.0; // 50 km

	// High altitude used for standard bombs, napalm, nuclear weapons
	// Low altitude used for poison gas (type 2) and Agent Orange (type 3) for effective dispersal

	int timer = 2000; // Extended for longer flight
	int bombStart = 75;
	int bombStop = 125;
	int bombRate = 1; // Faster bomb dropping for payload
	int type = 0;
	double flightAltitude = FLIGHT_ALTITUDE_HIGH; // Default to high altitude
	com.hbm.explosion.ExplosionRealisticBomb.BombType bombLoadout = com.hbm.explosion.ExplosionRealisticBomb.BombType.BOMB_500LB; // Default loadout

	// Target coordinates
	double targetX;
	double targetY;
	double targetZ;

	// Flight state
	boolean hasDroppedBombs = false;
	boolean isEscaping = false;
	int bombsDropped = 0;
	int maxBombs = 40; // B-29 realistic bomb load
	boolean hasNotifiedFirstBomb = false; // Track if we've sent first bomb notification

	// Speed control
	double currentSpeed = CRUISE_SPEED;

	// Calling player tracking for countdown
	java.util.UUID callingPlayerUUID = null;
	int lastCountdownSecond = -1; // Track last countdown value sent

	// Formation system
	java.util.UUID formationId = null;      // Formation this bomber belongs to
	int formationRow = 0;                   // 0=first row, 1=second row, 2=third row (front)
	int formationColumn = 0;                // 0-9 position within row
	boolean isFormationLeader = false;      // First bomber in formation
	int formationBombingDelay = 0;          // Delay before this bomber can drop bombs (based on row)
	boolean hasNotifiedFormationAdd = false; // Track if we've sent formation add notification
	int dropZoneReachedTick = -1;           // Tick when bomber first reached drop zone (-1 = not reached yet)
	boolean formationOffsetApplied = false; // Track if formation offset has been applied

	// Drop distance caching (to avoid expensive recalculation every tick)
	private double cachedDropDistance = -1.0;      // Cached drop distance value
	private int lastDropDistanceCalcTick = -100;   // Last tick when drop distance was calculated

	// Bomb tracking and chunk loading system
	private java.util.Map<java.util.UUID, com.hbm.entity.projectile.EntityBombletZeta> activeBombs = new java.util.HashMap<>(); // Track active bombs
	private java.util.Set<net.minecraft.util.math.ChunkPos> bombingCorridorChunks = new java.util.HashSet<>(); // Pre-loaded bombing corridor chunks
	private boolean hasBombingStarted = false;     // Track if bombing has started
	private int bombsDetonated = 0;                // Count of bombs that have detonated
	private boolean hasLoadedBombingCorridor = false; // Track if bombing corridor chunks are loaded

	// Chunk loading (from EntityMissileBaseAdvanced)
	int chunkX = 0;
	int chunkZ = 0;
	private ChunkPos mainChunk;

	public int health = 50;
	
	public EntityBomber(World worldIn) {
		super(worldIn);
		
		this.ignoreFrustumCheck = true;
    	this.setSize(8.0F, 4.0F);
	}
	
	@Override
	public double getRadarCrossSection() {
		return 100.0; // B-29 bomber RCS: ~100 m² (Skolnik, "Introduction to Radar Systems")
	}

	@Override
	public boolean canBeCollidedWith() {
		return health > 0;
	}
	
	@Override
	public boolean attackEntityFrom(DamageSource source, float amount) {
		if(source == ModDamageSource.nuclearBlast)
    		return false;
    	
        if (this.isEntityInvulnerable(source))
        {
            return false;
        }
        else
        {
            if (!this.isDead && !this.world.isRemote && this.health > 0)
            {
            	health -= amount;
            	
                if (this.health <= 0)
                {
                    this.killBomber();
                }
            }

            return true;
        }
	}
	
	private void killBomber() {
		// Notify formation of bomber death for leader succession
		if(!world.isRemote && formationId != null) {
			EntityBomberFormationManager formationManager = EntityBomberFormationManager.getInstance();
			EntityBomberFormation formation = formationManager.getFormation(world, formationId);
			if(formation != null) {
				boolean formationWipedOut = formation.handleBomberDeath(this.getUniqueID());

				// If formation is completely wiped out, remove it from manager
				if(formationWipedOut) {
					formationManager.removeFormation(world, formationId);
					MainRegistry.logger.info("[FORMATION] Formation " + formationId + " removed from manager (all bombers destroyed)");

					// Notify calling player that entire formation was destroyed
					if(callingPlayerUUID != null) {
						net.minecraft.entity.player.EntityPlayer player = world.getPlayerEntityByUUID(callingPlayerUUID);
						if(player != null) {
							player.sendMessage(new net.minecraft.util.text.TextComponentString(
								net.minecraft.util.text.TextFormatting.DARK_RED + "" + net.minecraft.util.text.TextFormatting.BOLD +
								"編隊全滅！全B-29が撃墜されました！"
							));
						}
					}
				}
			}
		}

		// Notify calling player of shootdown
		if(!world.isRemote && callingPlayerUUID != null) {
			net.minecraft.entity.player.EntityPlayer player = world.getPlayerEntityByUUID(callingPlayerUUID);
			if(player != null) {
				player.sendMessage(new net.minecraft.util.text.TextComponentTranslation(
					net.minecraft.util.text.TextFormatting.RED + "" + net.minecraft.util.text.TextFormatting.BOLD + "B-29が撃墜されました！"
				));
			}
			// Stop countdown by setting hasDroppedBombs = true
			hasDroppedBombs = true;
		}

        ExplosionLarge.explode(world, posX, posY, posZ, 5, true, false, true);
    	world.playSound((double)(posX + 0.5F), (double)(posY + 0.5F), (double)(posZ + 0.5F), HBMSoundHandler.planeShotDown, SoundCategory.HOSTILE, 25.0F, 1.0F, false);
    }
	
	public boolean isBomberAlive(){
		return this.health > 0;
	}
	
	@Override
	public void onUpdate() {

		// Enhanced chunk loading
		loadMainChunk();

		// Apply formation offset on first tick (after spawn)
		// This ensures the entity spawns at the base position (where chunks are loaded)
		// then moves to formation position on first update
		if(!formationOffsetApplied && formationId != null) {
			applyFormationOffset();
			formationOffsetApplied = true;
			MainRegistry.logger.info("Applied formation offset for bomber at row=" + formationRow + ", column=" + formationColumn);
		}

		// Formation synchronization - match leader's velocity
		// Non-leader bombers adjust speed to stay in formation with leader
		if(formationId != null && !isFormationLeader && formationOffsetApplied) {
			syncWithFormationLeader();
		}

		this.lastTickPosX = this.prevPosX = posX;
		this.lastTickPosY = this.prevPosY = posY;
		this.lastTickPosZ = this.prevPosZ = posZ;

		// Update chunk loading for neighboring chunks
		if((int) (posX / 16) != chunkX || (int) (posZ / 16) != chunkZ){
			chunkX = (int) (posX / 16);
			chunkZ = (int) (posZ / 16);
			loadNeighboringChunks(chunkX, chunkZ);
		}

		// === UPDATE FORMATION LEADER STATUS ===
		// Check if this bomber is the current formation leader (handles leader succession)
		if(!world.isRemote && formationId != null) {
			EntityBomberFormation formation = EntityBomberFormationManager.getInstance().getFormation(world, formationId);
			if(formation != null) {
				boolean wasLeader = isFormationLeader;
				isFormationLeader = formation.isLeader(this.getUniqueID());

				// Log leader succession
				if(!wasLeader && isFormationLeader) {
					MainRegistry.logger.info("[FORMATION] Bomber " + this.getUniqueID() + " promoted to leader!");
				}
			}
		}

		// === TRAJECTORY CORRECTION SYSTEM ===
		// Continuously adjust bomber's course to ensure bombs land exactly at target [0,0]
		// Only apply to formation leaders or solo bombers, and only before dropping bombs
		// Runs every 2 ticks (10 times/second) for high precision with good performance
		if(!world.isRemote && (formationId == null || isFormationLeader) && bombsDropped == 0 && targetX != 0 && this.ticksExisted % 2 == 0) {
			correctTrajectory();
		}

		this.setPosition(posX + motionX, posY + motionY, posZ + motionZ);

		if(!world.isRemote) {

			this.getDataManager().set(HEALTH, health);

			if(health > 0)
				PacketDispatcher.wrapper.sendToAll(new LoopedEntitySoundPacket(this.getEntityId()));
		} else {
			health = this.getDataManager().get(HEALTH);
		}

		this.rotation();

		// DYNAMIC COUNTDOWN SYSTEM - Recalculates every second based on real-time physics
		// Only formation leader shows countdown to avoid duplication
		boolean shouldShowCountdown = (formationId == null) || isFormationLeader;

		if(!world.isRemote && callingPlayerUUID != null && bombsDropped == 0 && shouldShowCountdown) {
			// Recalculate every second for maximum accuracy
			if(this.ticksExisted % 20 == 0) {
				// Calculate current distance to target
				double distanceToTarget = Math.sqrt((posX - targetX) * (posX - targetX) + (posZ - targetZ) * (posZ - targetZ));

				// Calculate optimal drop distance based on CURRENT bomber state (altitude, velocity, bomb load)
				// Use cached value (recalculated every 20 ticks automatically)
				double optimalDropDistance = getCachedDropDistance();

				// Calculate remaining distance until bombing point
				double remainingDistance = Math.max(0, distanceToTarget - optimalDropDistance);

				// Calculate remaining time using CURRENT ACTUAL SPEED (not fixed cruise speed)
				// This accounts for acceleration/deceleration and formation synchronization
				double actualCurrentSpeed = Math.sqrt(motionX * motionX + motionZ * motionZ); // blocks/tick

				// Prevent division by zero
				if(actualCurrentSpeed < 0.001) {
					actualCurrentSpeed = CRUISE_SPEED; // Fallback to cruise speed if stationary
				}

				// Calculate time in seconds: distance (blocks) / speed (blocks/tick) / 20 (ticks/second)
				int remainingSeconds = (int) Math.ceil(remainingDistance / (actualCurrentSpeed * 20.0));

				// Only send message if countdown changed (avoid spam)
				if(remainingSeconds != lastCountdownSecond) {
					lastCountdownSecond = remainingSeconds;

					// Find player and send dynamic countdown message
					net.minecraft.entity.player.EntityPlayer player = world.getPlayerEntityByUUID(callingPlayerUUID);
					if(player != null) {
						int minutes = remainingSeconds / 60;
						int seconds = remainingSeconds % 60;

						// Enhanced message showing both time and calculated drop distance
						player.sendMessage(new net.minecraft.util.text.TextComponentTranslation(
							net.minecraft.util.text.TextFormatting.AQUA + "B-29到着まで: " +
							net.minecraft.util.text.TextFormatting.GOLD + minutes + "分" + seconds + "秒" +
							net.minecraft.util.text.TextFormatting.GRAY + " (投下距離: " + (int)optimalDropDistance + "m | 速度: " + String.format("%.0f", actualCurrentSpeed * 20.0) + "m/s)"
						));
					}
				}
			}
		}

		// Handle damage state
		if(this.health <= 0) {
			motionY -= 0.025;
			if(!CompatibilityConfig.isWarDim(world)){
				clearLoadedChunks();
				unloadMainChunk();
				this.setDead();
				return;
			}
        	for(int i = 0; i < 10; i++)
        		this.world.spawnEntity(new EntityGasFlameFX(this.world, this.posX + rand.nextGaussian() * 0.5 - motionX * 2, this.posY + rand.nextGaussian() * 0.5 - motionY * 2, this.posZ + rand.nextGaussian() * 0.5 - motionZ * 2, 0.0, 0.1, 0.0));

			if(world.getBlockState(new BlockPos((int)posX, (int)posY, (int)posZ)).isNormalCube() && !world.isRemote) {
				clearLoadedChunks();
				unloadMainChunk();
				this.setDead();

				ExplosionLarge.explodeFire(world, posX, posY, posZ, 25, true, false, true);
		    	world.playSound((double)(posX + 0.5F), (double)(posY + 0.5F), (double)(posZ + 0.5F), HBMSoundHandler.planeCrash, SoundCategory.HOSTILE, 10.0F, 1.0F, true);

				return;
			}
		}

		// Check despawn distance (50km from target after bombing)
		if(hasDroppedBombs) {
			double distanceToTarget = Math.sqrt((posX - targetX) * (posX - targetX) + (posZ - targetZ) * (posZ - targetZ));
			if(distanceToTarget > DESPAWN_DISTANCE) {
				// Bomber successfully escaped after completing mission - remove from formation
				if(!world.isRemote && formationId != null) {
					EntityBomberFormationManager formationManager = EntityBomberFormationManager.getInstance();
					EntityBomberFormation formation = formationManager.getFormation(world, formationId);
					if(formation != null) {
						boolean formationWipedOut = formation.handleBomberDeath(this.getUniqueID());

						// If this was the last bomber, remove formation from manager
						if(formationWipedOut) {
							formationManager.removeFormation(world, formationId);
							MainRegistry.logger.info("[FORMATION] Formation " + formationId + " completed mission and removed from manager");

							// Notify calling player that formation completed its mission
							if(callingPlayerUUID != null) {
								net.minecraft.entity.player.EntityPlayer player = world.getPlayerEntityByUUID(callingPlayerUUID);
								if(player != null) {
									player.sendMessage(new net.minecraft.util.text.TextComponentString(
										net.minecraft.util.text.TextFormatting.GREEN + "" + net.minecraft.util.text.TextFormatting.BOLD +
										"編隊任務完了！全B-29が帰投しました。"
									));
								}
							}
						}
					}
				}

				clearLoadedChunks();
				unloadMainChunk();
				this.setDead();
				return;
			}
		}

		// Handle bombing phase
		if(!world.isRemote && this.health > 0 && bombsDropped < maxBombs) {
			// Calculate optimal drop distance based on current altitude, velocity, and bomb weight
			// Use cached value (recalculated every 20 ticks automatically)
			double optimalDropDistance = getCachedDropDistance();

			// Check distance to target for precision bombing
			double distanceToTarget = Math.sqrt((posX - targetX) * (posX - targetX) + (posZ - targetZ) * (posZ - targetZ));

			// === FORMATION SYNCHRONIZED BOMBING CONTROL ===
			// Only leader manages row bombing authorization
			if(formationId != null && isFormationLeader && !world.isRemote) {
				EntityBomberFormation formation = EntityBomberFormationManager.getInstance().getFormation(world, formationId);
				if(formation != null) {
					// Increment formation tick counter (for synchronized dropping)
					formation.incrementFormationTick();

					// === FORMATION-WIDE CHUNK PRE-LOADING ===
					// Pre-load ALL bombing corridors when approaching drop zone (3000m away)
					// This ensures all 30 bombers have chunks loaded BEFORE any bombing starts
					// DISABLED: Bombs now load their own chunks (3x3 radius) as they fall
					// if(!formation.isFormationChunksPreloaded() && distanceToTarget <= 15000.0) {
					// 	triggerFormationChunkPreload(formation);
					// 	formation.setFormationChunksPreloaded(true);
					// 	MainRegistry.logger.info("[FORMATION] Pre-loading bombing corridors for entire formation (" + formation.getBomberCount() + " bombers)");
					// }

					// Row timing based on leader's distance to target:
					// Row 2 (front, 800m ahead): starts when leader is 1600m away
					// Row 0 (leader): starts when leader reaches drop zone
					// Row 1 (back, 800m behind): starts when leader is 800m past drop zone

					final double ROW_SPACING = 800.0; // 800m between rows

					// Row 2 authorization (front row - drops first)
					if(distanceToTarget <= optimalDropDistance + ROW_SPACING * 2) {
						formation.setRow2CanBomb(true);
					}

					// Row 0 authorization (leader row)
					if(distanceToTarget <= optimalDropDistance) {
						formation.setRow0CanBomb(true);
						if(dropZoneReachedTick == -1) {
							dropZoneReachedTick = this.ticksExisted;
							formation.setReachedDropPosition(true);
						}
					}

					// Row 1 authorization (back row - drops last)
					if(distanceToTarget <= optimalDropDistance - ROW_SPACING) {
						formation.setRow1CanBomb(true);
					}
				}
			}

			// === PRECISION BOMBING STATUS LOGGING ===
			// Calculate and log alignment status (for monitoring AI performance, NOT for blocking bombing)
			if(!world.isRemote && bombsDropped == 0 && this.ticksExisted % 100 == 0 && distanceToTarget < optimalDropDistance * 1.5) {
				double currentVelMag = Math.sqrt(motionX * motionX + motionZ * motionZ);
				double currentDirX = motionX / (currentVelMag + 0.001);
				double currentDirZ = motionZ / (currentVelMag + 0.001);

				// Calculate ideal direction to target
				double toTargetX = targetX - posX;
				double toTargetZ = targetZ - posZ;
				double toTargetMag = Math.sqrt(toTargetX * toTargetX + toTargetZ * toTargetZ);
				double targetDirX = toTargetX / (toTargetMag + 0.001);
				double targetDirZ = toTargetZ / (toTargetMag + 0.001);

				// Calculate dot product (alignment: 1.0 = perfect, 0.0 = perpendicular, -1.0 = opposite)
				double alignment = currentDirX * targetDirX + currentDirZ * targetDirZ;

				// Calculate predicted landing point
				double predictedLandingX = posX + currentDirX * optimalDropDistance;
				double predictedLandingZ = posZ + currentDirZ * optimalDropDistance;
				double landingError = Math.sqrt(
					(predictedLandingX - targetX) * (predictedLandingX - targetX) +
					(predictedLandingZ - targetZ) * (predictedLandingZ - targetZ)
				);

				// Log status (this is INFORMATIONAL ONLY - does not affect bombing)
				MainRegistry.logger.info("[PRECISION STATUS] Distance:" + (int)distanceToTarget + "m | Alignment:" + String.format("%.4f", alignment) + " | Predicted Error:" + (int)landingError + "m");
			}

			// === INDIVIDUAL BOMBING CONTROL ===
			// NEW: Individual bombing - each bomber bombs when IT reaches optimal drop distance
			// This spreads bombing over time to reduce lag (10km spacing = ~40 second delay between planes)
			boolean canBomb = false;
			int syncedTickModulo = 0;

			if(formationId == null) {
				// Solo bomber: bomb when reaching drop distance
				canBomb = (distanceToTarget <= optimalDropDistance);
				syncedTickModulo = this.ticksExisted % bombRate;
			} else {
				// Formation bomber: ALSO bomb when reaching individual drop distance (not synchronized by row)
				// Each plane has different spawn distance, so reaches drop zone at different times
				canBomb = (distanceToTarget <= optimalDropDistance);
				syncedTickModulo = this.ticksExisted % bombRate; // Use own tick counter, not formation
			}

			// Drop bomb when conditions are met (synchronized across formation)
			if(canBomb && syncedTickModulo == 0) {
				boolean isWarDimension = CompatibilityConfig.isWarDim(world);
				int currentDim = world.provider.getDimension();

				if(!isWarDimension){
					MainRegistry.logger.error("[BOMB DROP BLOCKED] isWarDim() returned FALSE for dimension " + currentDim + "! Bombs will NOT spawn. Check hbm_dimensions.cfg peaceDimensions settings.");
					return;
				}

				if(bombsDropped == 0) {
					MainRegistry.logger.info("[BOMB DROP OK] isWarDim() = true for dimension " + currentDim + ". Proceeding with bombing.");
				}

				// Log precision bombing data
				if(bombsDropped == 0) {
					MainRegistry.logger.info("===== PRECISION BOMBING INITIATED =====");
					MainRegistry.logger.info("Bomber Position: X=" + (int)posX + ", Y=" + (int)posY + ", Z=" + (int)posZ);
					MainRegistry.logger.info("Target Position: X=" + (int)targetX + ", Y=" + (int)targetY + ", Z=" + (int)targetZ);
					MainRegistry.logger.info("Distance to target: " + (int)distanceToTarget + " blocks");
					MainRegistry.logger.info("Calculated drop distance: " + (int)optimalDropDistance + " blocks");
					MainRegistry.logger.info("Loadout: " + bombLoadout.displayName);
					MainRegistry.logger.info("Bomb mass: " + (int)bombLoadout.totalWeightKg + " kg");
					MainRegistry.logger.info("Altitude: " + (int)posY + "m");
					MainRegistry.logger.info("Bomber Velocity: X=" + String.format("%.4f", motionX) + ", Z=" + String.format("%.4f", motionZ));
					MainRegistry.logger.info("Speed: " + (int)(Math.sqrt(motionX*motionX + motionZ*motionZ) * 20 * 3.6) + " km/h");
					MainRegistry.logger.info("Formation: " + (formationId != null ? "Row " + formationRow + ", Column " + formationColumn : "Solo"));
					MainRegistry.logger.info("======================================");
				}

				bombsDropped++;

				// Send first bomb notification
				if(bombsDropped == 1 && !hasNotifiedFirstBomb && callingPlayerUUID != null) {
					hasNotifiedFirstBomb = true;
					net.minecraft.entity.player.EntityPlayer player = world.getPlayerEntityByUUID(callingPlayerUUID);
					if(player != null) {
						player.sendMessage(new net.minecraft.util.text.TextComponentTranslation(
							net.minecraft.util.text.TextFormatting.RED + "" + net.minecraft.util.text.TextFormatting.BOLD + "爆弾投下"
						));
					}
				}

				// Pre-load bombing corridor chunks when first bomb drops (for solo bombers only)
				// Formation bombers have chunks pre-loaded by formation leader before bombing starts
				// FAILSAFE: Always preload even for formation bombers
				// DISABLED: Bombs now load their own chunks (3x3 radius) as they fall
				// if(bombsDropped == 1) {
				// 	preloadBombingCorridor();
				// }

				if(type == 3) {
		        	world.playSound((double)(posX + 0.5F), (double)(posY + 0.5F), (double)(posZ + 0.5F), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.HOSTILE, 5.0F, 2.6F + (rand.nextFloat() - rand.nextFloat()) * 0.8F, true);
					ExplosionChaos.spawnChlorine(world, this.posX, this.posY - 1F, this.posZ, 10, 0.5, 3);

				} else if(type == 5) {
		        	world.playSound((double)(posX + 0.5F), (double)(posY + 0.5F), (double)(posZ + 0.5F), HBMSoundHandler.missileTakeoff, SoundCategory.HOSTILE, 10.0F, 0.9F + rand.nextFloat() * 0.2F, true);
		        	EntityRocketHoming rocket = new EntityRocketHoming(world);
		        	rocket.setIsCritical(true);
		        	rocket.motionY = -1;
		        	rocket.shootingEntity = this;
		        	rocket.homingRadius = 50;
		        	rocket.homingMod = 5;

		        	rocket.posX = posX + rand.nextDouble() - 0.5;
		        	rocket.posY = posY - rand.nextDouble();
		        	rocket.posZ = posZ + rand.nextDouble() - 0.5;

					world.spawnEntity(rocket);

				} else if(type == 6) {
		        	world.playSound((double)(posX + 0.5F), (double)(posY + 0.5F), (double)(posZ + 0.5F), HBMSoundHandler.missileTakeoff, SoundCategory.HOSTILE, 10.0F, 0.9F + rand.nextFloat() * 0.2F, true);
		        	EntityBoxcar rocket = new EntityBoxcar(world);

		        	rocket.posX = posX + rand.nextDouble() - 0.5;
		        	rocket.posY = posY - rand.nextDouble();
		        	rocket.posZ = posZ + rand.nextDouble() - 0.5;

					world.spawnEntity(rocket);

				} else if(type == 7) {
		        	world.playSound((double)(posX + 0.5F), (double)(posY + 0.5F), (double)(posZ + 0.5F), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.HOSTILE, 5.0F, 2.6F + (rand.nextFloat() - rand.nextFloat()) * 0.8F, true);
					ExplosionChaos.spawnChlorine(world, this.posX, world.getHeight((int)this.posX, (int)this.posZ) + 2, this.posZ, 10, 1, 2);

				} else {
		        	world.playSound((double)(posX + 0.5F), (double)(posY + 0.5F), (double)(posZ + 0.5F), HBMSoundHandler.bombWhistle, SoundCategory.HOSTILE, 10.0F, 0.9F + rand.nextFloat() * 0.2F, true);

					EntityBombletZeta zeta = new EntityBombletZeta(world);

					zeta.rotation();

					zeta.type = type;
					zeta.setBombSize(bombLoadout); // Set realistic bomb size

					// Set bomber UUID for group chunk loading (bomb won't load chunks individually)
					zeta.setBomberUUID(this.getUniqueID());

					// Account for high-altitude precision bombing (10km drop)
					zeta.posX = posX + rand.nextDouble() - 0.5;
					zeta.posY = posY - rand.nextDouble();
					zeta.posZ = posZ + rand.nextDouble() - 0.5;

					// Realistic velocity transfer: Bomb inherits full horizontal velocity from bomber
					// This is critical for precision bombing from 10,000m altitude
					// Without proper velocity inheritance, bombs would miss by kilometers
					zeta.motionX = motionX;  // Full velocity transfer
					zeta.motionZ = motionZ;  // Full velocity transfer
					zeta.motionY = 0.0;      // Initial vertical velocity is zero

					world.spawnEntity(zeta);

					// Register bomb in group tracking system
					registerBomb(zeta);

					// DEBUG: Log each bomb drop
					if(bombsDropped == 1 || bombsDropped == maxBombs) {
						MainRegistry.logger.info("--- BOMB DROP #" + bombsDropped + " ---");
						MainRegistry.logger.info("Bomb Spawn: X=" + (int)zeta.posX + ", Y=" + (int)zeta.posY + ", Z=" + (int)zeta.posZ);
						MainRegistry.logger.info("Bomb Velocity: X=" + String.format("%.4f", zeta.motionX) + ", Y=" + String.format("%.4f", zeta.motionY) + ", Z=" + String.format("%.4f", zeta.motionZ));
						MainRegistry.logger.info("Expected fall time: ~" + (int)(posY / 40.0) + " seconds (from " + (int)posY + "m altitude)");
					}
				}

				// Check if bombing complete
				if(bombsDropped >= maxBombs) {
					hasDroppedBombs = true;
					isEscaping = true;
					// Start turning to escape
					currentSpeed = ESCAPE_SPEED;
				}
			}
		}

		// Update speed if escaping
		if(isEscaping) {
			// Gradually turn away from target
			Vec3d toTarget = new Vec3d(targetX - posX, 0, targetZ - posZ).normalize();
			Vec3d currentDir = new Vec3d(motionX, 0, motionZ).normalize();

			// Turn 180 degrees gradually
			double turnRate = 0.02; // Slow realistic turn
			motionX = currentDir.x - toTarget.x * turnRate;
			motionZ = currentDir.z - toTarget.z * turnRate;

			// Normalize and apply escape speed
			Vec3d newDir = new Vec3d(motionX, 0, motionZ).normalize();
			motionX = newDir.x * currentSpeed;
			motionZ = newDir.z * currentSpeed;
		}
	}
	
	public void setCallingPlayer(java.util.UUID playerUUID) {
		this.callingPlayerUUID = playerUUID;
	}

	/**
	 * Calculate optimal bomb drop distance based on REALISTIC bomb physics simulation
	 * Uses actual WW2 bomb specifications and real-world physics
	 *
	 * @param bombType The type of bomb being dropped (ignored for type 0, uses bombLoadout)
	 * @return Distance from target (in blocks) where bomb should be released
	 */
	/**
	 * Helper function: Calculate air density at given altitude using EXPONENTIAL barometric formula
	 * ρ(h) = ρ₀ × exp(-h/H) where H = 8400m (atmospheric scale height)
	 * Source: Wikipedia - Barometric formula
	 * @param altitude Altitude in meters
	 * @return Air density in kg/m³
	 */
	private double getAirDensity(double altitude) {
		double rho0 = 1.225; // Sea level density (kg/m³)
		double scaleHeight = 8400.0; // Atmospheric scale height (m)
		return rho0 * Math.exp(-altitude / scaleHeight);
	}

	/**
	 * Helper function: Calculate terminal velocity at given altitude for a bomb
	 * Terminal velocity formula: v_t = sqrt((2 * m * g) / (ρ * C_d * A))
	 * @param massKg Bomb mass in kg
	 * @param altitude Current altitude in meters
	 * @param dragCoeff Drag coefficient (dimensionless)
	 * @param crossSection Cross-sectional area in m²
	 * @return Terminal velocity in blocks/tick
	 */
	private double calculateTerminalVelocity(double massKg, double altitude, double dragCoeff, double crossSection) {
		double airDensity = getAirDensity(altitude);
		double gravityMPS2 = 9.81;  // m/s²

		// Terminal velocity in m/s
		double terminalVelMPS = Math.sqrt((2.0 * massKg * gravityMPS2) / (airDensity * dragCoeff * crossSection));

		// Convert to blocks/tick (1 block/tick = 20 blocks/s = 20 m/s assuming 1 block = 1 meter)
		double terminalVelBlocksPerTick = terminalVelMPS / 20.0;

		return terminalVelBlocksPerTick;
	}

	/**
	 * Calculate bomb drop distance using REALISTIC PHYSICS
	 * This simulation will be matched by EntityBombletZeta for precision bombing
	 */
	private double calculateBombDropDistance(int bombType) {
		// For non-standard bombs, use simplified calculation
		if(bombType != 0) {
			return 500.0; // Default fallback
		}

		// Get actual bomb mass from loadout
		double massKg = bombLoadout.totalWeightKg;

		// REALISTIC PHYSICS CONSTANTS - Based on actual WW2 bomb data
		// Target: 45 seconds fall time from 10,000m (historical data)
		double gravity = 9.81 / 400.0; // m/tick² (scaled: 9.81 m/s² / (20 ticks/s)²)
		double dragCoefficient = 0.28; // Streamlined WW2 bomb shape (Mk 82 / AN-M64)

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

		// Get initial horizontal velocity (in blocks/tick)
		double initialHorizVelX = this.motionX;
		double initialHorizVelZ = this.motionZ;
		double initialHorizSpeed = Math.sqrt(initialHorizVelX * initialHorizVelX + initialHorizVelZ * initialHorizVelZ);

		// DEBUG: Log calculation start
		if(!world.isRemote) {
			MainRegistry.logger.info("========== BOMB DROP DISTANCE CALCULATION (REALISTIC PHYSICS) ==========");
			MainRegistry.logger.info("Bomb Mass: " + (int)massKg + " kg");
			MainRegistry.logger.info("Initial Altitude: " + (int)posY + "m");
			MainRegistry.logger.info("Target Altitude: " + (int)targetY + "m");
			MainRegistry.logger.info("Altitude Difference: " + (int)(posY - targetY) + "m");
			MainRegistry.logger.info("Initial Horizontal Speed: " + String.format("%.2f", initialHorizSpeed * 20.0) + " m/s (" + String.format("%.4f", initialHorizSpeed) + " blocks/tick)");
			MainRegistry.logger.info("Drag Coefficient: " + dragCoefficient);
			MainRegistry.logger.info("Cross-Section Area: " + String.format("%.2f", crossSectionArea) + " m²");
			MainRegistry.logger.info("Starting physics simulation...");
		}

		// Simulate bomb trajectory using REALISTIC PHYSICS WITH ALTITUDE-VARYING AIR DENSITY
		double simAltitude = this.posY;  // Current altitude
		double simVelX = initialHorizVelX;   // Horizontal velocity X
		double simVelZ = initialHorizVelZ;   // Horizontal velocity Z
		double simVelY = 0.0;            // Initial vertical velocity (bomb released from aircraft)

		double totalHorizontalDistance = 0.0;
		int maxIterations = 20000; // Prevent infinite loop (allows for ~166 second fall)
		int iterations = 0;
		int debugLogInterval = 1000; // Log every 50 seconds of simulation

		// Simulate until bomb hits ground (Y <= targetY)
		while(simAltitude > targetY && iterations < maxIterations) {
			// Calculate current air density using exponential barometric formula
			double currentAirDensity = getAirDensity(simAltitude);
			double currentTerminalVel = calculateTerminalVelocity(massKg, simAltitude, dragCoefficient, crossSectionArea);

			// Convert velocities to m/s for physics calculations
			double velocityYMS = simVelY * 20.0;
			double velocityXMS = simVelX * 20.0;
			double velocityZMS = simVelZ * 20.0;

			// === VERTICAL PHYSICS ===
			// Apply gravity (downward acceleration)
			simVelY -= gravity;

			// Apply vertical drag using correct physics
			// F_drag = 0.5 * ρ * v² * C_d * A
			if(Math.abs(velocityYMS) > 0.001) {
				double dragForce = 0.5 * currentAirDensity * velocityYMS * velocityYMS * dragCoefficient * crossSectionArea;
				double dragAccelMS2 = dragForce / massKg; // m/s²
				double dragAccelTick2 = dragAccelMS2 / 400.0; // Convert to blocks/tick²

				// Apply drag in opposite direction of motion
				if(simVelY < 0) {
					simVelY += dragAccelTick2; // Drag opposes downward motion
				} else {
					simVelY -= dragAccelTick2; // Drag opposes upward motion
				}
			}

			// Limit to terminal velocity as safety check
			if(Math.abs(simVelY) > currentTerminalVel) {
				simVelY = -currentTerminalVel * Math.signum(simVelY);
			}

			// === HORIZONTAL PHYSICS ===
			// Apply horizontal air drag
			double horizSpeedMS = Math.sqrt(velocityXMS * velocityXMS + velocityZMS * velocityZMS);
			if(horizSpeedMS > 0.001) {
				// Horizontal drag uses reduced effective area (streamlined in forward direction)
				double horizDragArea = crossSectionArea * 0.08; // 8% of full area for horizontal

				// Calculate drag force
				double dragForce = 0.5 * currentAirDensity * horizSpeedMS * horizSpeedMS * dragCoefficient * horizDragArea;
				double dragAccelMS2 = dragForce / massKg;
				double dragAccelTick2 = dragAccelMS2 / 400.0;

				// Apply drag proportionally to each horizontal component
				double horizSpeed = Math.sqrt(simVelX * simVelX + simVelZ * simVelZ);
				double dragFraction = dragAccelTick2 / horizSpeed; // Fraction of velocity to remove
				dragFraction = Math.min(0.05, dragFraction); // Limit to 5% per tick

				simVelX *= (1.0 - dragFraction * (Math.abs(velocityXMS) / horizSpeedMS));
				simVelZ *= (1.0 - dragFraction * (Math.abs(velocityZMS) / horizSpeedMS));
			}

			// === UPDATE POSITION ===
			simAltitude += simVelY;
			double horizontalMovement = Math.sqrt(simVelX * simVelX + simVelZ * simVelZ);
			totalHorizontalDistance += horizontalMovement;

			// === DEBUG LOGGING (every 50 seconds) ===
			if(!world.isRemote && iterations % debugLogInterval == 0 && iterations > 0) {
				double elapsedTime = iterations / 20.0;
				double currentHorizSpeed = Math.sqrt(simVelX * simVelX + simVelZ * simVelZ) * 20.0;
				double currentVertSpeed = Math.abs(simVelY) * 20.0;
				MainRegistry.logger.info("  [T+" + String.format("%.1f", elapsedTime) + "s] Alt:" + (int)simAltitude + "m | Dist:" + (int)totalHorizontalDistance + "m | VelH:" + String.format("%.1f", currentHorizSpeed) + "m/s VelV:" + String.format("%.1f", currentVertSpeed) + "m/s | AirDensity:" + String.format("%.3f", currentAirDensity) + " | TermVel:" + String.format("%.1f", currentTerminalVel * 20) + "m/s");
			}

			iterations++;
		}

		// === FINAL DEBUG OUTPUT ===
		if(!world.isRemote) {
			double totalFallTime = iterations / 20.0;
			double finalHorizSpeed = Math.sqrt(simVelX * simVelX + simVelZ * simVelZ) * 20.0;
			double finalVertSpeed = Math.abs(simVelY) * 20.0;
			double avgHorizSpeed = (totalHorizontalDistance / iterations) * 20.0;

			MainRegistry.logger.info("========== SIMULATION COMPLETE ==========");
			MainRegistry.logger.info("Total Fall Time: " + String.format("%.1f", totalFallTime) + " seconds (" + iterations + " ticks)");
			MainRegistry.logger.info("Total Horizontal Distance: " + (int)totalHorizontalDistance + " meters");
			MainRegistry.logger.info("Average Horizontal Speed: " + String.format("%.1f", avgHorizSpeed) + " m/s");
			MainRegistry.logger.info("Final Horizontal Speed: " + String.format("%.1f", finalHorizSpeed) + " m/s");
			MainRegistry.logger.info("Final Vertical Speed: " + String.format("%.1f", finalVertSpeed) + " m/s");
			MainRegistry.logger.info("Speed Change: " + String.format("%.1f", initialHorizSpeed * 20.0) + " m/s -> " + String.format("%.1f", finalHorizSpeed) + " m/s (" + String.format("%.1f%%", (finalHorizSpeed / (initialHorizSpeed * 20.0)) * 100) + ")");
			MainRegistry.logger.info("Final Altitude: " + (int)simAltitude + "m");
			MainRegistry.logger.info("===========================================");
		}

		// Warn if simulation didn't complete
		if(iterations >= maxIterations) {
			MainRegistry.logger.warn("!!! Bomb trajectory simulation reached max iterations! Results may be inaccurate.");
		}

		// Return the calculated drop distance
		return totalHorizontalDistance;
	}

	/**
	 * Trajectory correction system - continuously adjusts bomber's course for maximum precision
	 *
	 * GOAL: Achieve landing error < 10m (does NOT block bombing if goal not reached)
	 * This system continuously calculates predicted landing point and corrects course deviation
	 * The bomber will drop bombs when reaching drop distance regardless of achieved precision
	 */
	/**
	 * Get cached drop distance (recalculates only every 20 ticks to save performance)
	 */
	private double getCachedDropDistance() {
		// Recalculate every 20 ticks (1 second) or if never calculated
		if(cachedDropDistance < 0 || this.ticksExisted - lastDropDistanceCalcTick >= 20) {
			cachedDropDistance = calculateBombDropDistance(type);
			lastDropDistanceCalcTick = this.ticksExisted;
		}
		return cachedDropDistance;
	}

	private void correctTrajectory() {
		// Calculate predicted bomb landing point if dropped NOW
		// Use cached value to avoid expensive recalculation every tick
		double dropDistance = getCachedDropDistance();

		// Current velocity vector (normalized)
		double currentVelMag = Math.sqrt(motionX * motionX + motionZ * motionZ);
		if(currentVelMag < 0.001) return; // Avoid division by zero

		double currentDirX = motionX / currentVelMag;
		double currentDirZ = motionZ / currentVelMag;

		// Predicted landing point = current position + velocity direction * drop distance
		double predictedLandingX = posX + currentDirX * dropDistance;
		double predictedLandingZ = posZ + currentDirZ * dropDistance;

		// Calculate error (deviation from target)
		double errorX = targetX - predictedLandingX;
		double errorZ = targetZ - predictedLandingZ;
		double errorMagnitude = Math.sqrt(errorX * errorX + errorZ * errorZ);

		// If error is significant, correct the trajectory
		if(errorMagnitude > 1.0) { // Only correct if error > 1 block
			// Calculate IDEAL direction (from current position to target)
			// The bomber should fly so that current_pos + ideal_direction * dropDistance = target
			// Therefore: ideal_direction = (target - current_pos) / dropDistance
			double idealDirX = (targetX - posX) / dropDistance;
			double idealDirZ = (targetZ - posZ) / dropDistance;
			double idealDirMag = Math.sqrt(idealDirX * idealDirX + idealDirZ * idealDirZ);

			if(idealDirMag > 0.001) {
				// Normalize ideal direction
				idealDirX /= idealDirMag;
				idealDirZ /= idealDirMag;

				// Calculate alignment (dot product): 1.0 = perfect, 0.0 = perpendicular, -1.0 = opposite
				double alignment = currentDirX * idealDirX + currentDirZ * idealDirZ;

				// AGGRESSIVE correction for precision bombing
				// Use proportional correction based on error magnitude
				double correctionStrength = Math.min(1.0, errorMagnitude / 50.0);

				// ADAPTIVE correction strength based on alignment:
				// - If flying opposite direction (alignment < 0): 80% correction for quick turnaround
				// - If flying wrong way (alignment < 0.9): 60% correction
				// - If nearly aligned (alignment > 0.9): 30% correction for fine-tuning
				double baseCorrection;
				if(alignment < 0.0) {
					baseCorrection = 0.8; // Flying backward - maximum correction needed!
				} else if(alignment < 0.9) {
					baseCorrection = 0.6; // Significant misalignment - strong correction
				} else if(errorMagnitude > 100.0) {
					baseCorrection = 0.5; // Far from target - moderate correction
				} else {
					baseCorrection = 0.3; // Close and aligned - gentle fine-tuning
				}

				double newDirX = currentDirX + (idealDirX - currentDirX) * correctionStrength * baseCorrection;
				double newDirZ = currentDirZ + (idealDirZ - currentDirZ) * correctionStrength * baseCorrection;

				// Normalize new direction
				double newDirMag = Math.sqrt(newDirX * newDirX + newDirZ * newDirZ);
				if(newDirMag > 0.001) {
					newDirX /= newDirMag;
					newDirZ /= newDirMag;

					// Apply new velocity (maintaining speed, only changing direction)
					this.motionX = newDirX * currentSpeed;
					this.motionZ = newDirZ * currentSpeed;

					// Debug logging (every 50 corrections)
					if(this.ticksExisted % 500 == 0 && errorMagnitude > 10.0) {
						MainRegistry.logger.info("TRAJECTORY CORRECTION: Error=" + (int)errorMagnitude + "m | PredictedLanding:(" + (int)predictedLandingX + "," + (int)predictedLandingZ + ") | Target:(" + (int)targetX + "," + (int)targetZ + ")");
					}
				}
			}
		}
	}

	public void fac(World world, double x, double y, double z) {
		fac(world, x, y, z, null, -1, -1);
	}

	/**
	 * Factory method with formation support
	 * @param world World instance
	 * @param x Target X
	 * @param y Target Y
	 * @param z Target Z
	 * @param formation Formation to use (null for solo bomber)
	 * @param row Formation row (-1 for solo)
	 * @param column Formation column (-1 for solo)
	 */
	public void fac(World world, double x, double y, double z, EntityBomberFormation formation, int row, int column) {

		// Store target coordinates
		this.targetX = x;
		this.targetY = y;
		this.targetZ = z;

		// Get approach direction from formation or generate random
		Vec3d vector;
		if (formation != null) {
			// Use formation's shared approach vector
			vector = new Vec3d(formation.getApproachVectorX(), 0, formation.getApproachVectorZ());
		} else {
			// Solo bomber: generate random approach direction
			vector = new Vec3d(world.rand.nextDouble() - 0.5, 0, world.rand.nextDouble() - 0.5);
			vector = vector.normalize();
		}

		// Calculate base spawn position 50km away from target
		double spawnX = x - vector.x * SPAWN_DISTANCE;
		double spawnZ = z - vector.z * SPAWN_DISTANCE;

		MainRegistry.logger.info(">>> SPAWN CALC: Target=(" + (int)x + "," + (int)z + ") | BaseSpawn=(" + (int)spawnX + "," + (int)spawnZ + ") | Formation=" + (formation != null ? formation.getFormationId() : "SOLO") + " | Row=" + row + " Col=" + column);

		// Apply formation offset if in formation
		if (formation != null && row >= 0 && column >= 0) {
			// NEW FORMATION SYSTEM: 10km longitudinal + lateral zigzag pattern for carpet bombing
			final double SEQUENTIAL_SPACING = 10000.0; // 10km spacing between sequential planes (longitudinal)
			final double LATERAL_SPACING = 100.0;      // 100 block lateral spacing for carpet bombing
			final double ROW_SPACING = 800.0;          // Row timing for bombing sequence

			// === LONGITUDINAL OFFSET (along approach direction) ===
			// Sequential offset: column number determines how far back the plane spawns
			double sequentialOffset = -column * SEQUENTIAL_SPACING;

			// Calculate timing group from column number (preserves 3-group timing behavior)
			// Columns 0-9: timingRow = 0 (standard timing)
			// Columns 10-19: timingRow = 1 (delayed timing, spawns further back)
			// Columns 20-29: timingRow = 2 (early timing, spawns closer)
			int timingRow = column / 10;

			// Row offset for bombing timing based on timing group
			double rowOffset = 0.0;
			if (timingRow == 0) {
				rowOffset = 0.0;
			} else if (timingRow == 1) {
				rowOffset = -ROW_SPACING;
			} else if (timingRow == 2) {
				rowOffset = ROW_SPACING;
			}

			// Total longitudinal offset (sequential + row timing)
			double longitudinalOffset = sequentialOffset + rowOffset;

			// === LATERAL OFFSET (perpendicular to approach direction) ===
			// Carpet bombing zigzag pattern:
			// Column 0: 0m (center)
			// Column 1: +100m (right)
			// Column 2: -100m (left)
			// Column 3: +200m (right)
			// Column 4: -200m (left)
			// etc.
			double lateralOffset;
			if (column == 0) {
				lateralOffset = 0;
			} else if (column % 2 == 1) {
				// Odd columns: +100, +200, +300, ...
				lateralOffset = LATERAL_SPACING * ((column + 1) / 2);
			} else {
				// Even columns (> 0): -100, -200, -300, ...
				lateralOffset = -LATERAL_SPACING * (column / 2);
			}

			// Calculate perpendicular vector for lateral offset
			// Perpendicular = rotate approach vector 90 degrees
			Vec3d perpendicular = new Vec3d(-vector.z, 0, vector.x);

			// Apply offsets
			// Longitudinal: along approach vector
			spawnX += vector.x * longitudinalOffset;
			spawnZ += vector.z * longitudinalOffset;
			// Lateral: perpendicular to approach vector
			spawnX += perpendicular.x * lateralOffset;
			spawnZ += perpendicular.z * lateralOffset;

			// Calculate actual distance for logging
			double actualDistance = SPAWN_DISTANCE - sequentialOffset;

			MainRegistry.logger.info("[CARPET BOMBING FORMATION] Row=" + row + " Col=" + column + " TimingGroup=" + timingRow);
			MainRegistry.logger.info("  Longitudinal: " + (int)sequentialOffset + "m + RowTiming: " + (int)rowOffset + "m = " + (int)longitudinalOffset + "m");
			MainRegistry.logger.info("  Lateral: " + (int)lateralOffset + "m (zigzag pattern)");
			MainRegistry.logger.info("  Distance from target: " + (int)actualDistance + "m");
			MainRegistry.logger.info(">>> AFTER OFFSET: FinalSpawn=(" + (int)spawnX + "," + (int)spawnZ + ") | Vector=(" + String.format("%.3f", vector.x) + "," + String.format("%.3f", vector.z) + ") | Perp=(" + String.format("%.3f", perpendicular.x) + "," + String.format("%.3f", perpendicular.z) + ")");
		}

		// CRITICAL: Force load spawn chunks BEFORE setting position
		// This allows spawning directly at 50km away even if player hasn't loaded those chunks
		if(!world.isRemote && loaderTicket != null) {
			int chunkXPos = (int) Math.floor(spawnX / 16D);
			int chunkZPos = (int) Math.floor(spawnZ / 16D);

			MainRegistry.logger.info("Force loading spawn chunks for B-29 at chunk [" + chunkXPos + ", " + chunkZPos + "]");

			// Synchronously load/generate the spawn chunk and neighboring chunks
			// provideChunk() is guaranteed to return a chunk (loads from file or generates)
			for(int cx = chunkXPos - 1; cx <= chunkXPos + 1; cx++) {
				for(int cz = chunkZPos - 1; cz <= chunkZPos + 1; cz++) {
					// Synchronously provide chunk (loads or generates)
					// This method is in IChunkProvider interface and always returns a chunk
					world.getChunkProvider().provideChunk(cx, cz);

					// Keep it loaded with our Forge ticket
					ChunkPos chunkPos = new ChunkPos(cx, cz);
					ForgeChunkManager.forceChunk(loaderTicket, chunkPos);
					loadedChunks.add(chunkPos);
				}
			}

			// Set main chunk
			this.mainChunk = new ChunkPos(chunkXPos, chunkZPos);
			this.chunkX = chunkXPos;
			this.chunkZ = chunkZPos;

			MainRegistry.logger.info("Chunks loaded successfully. Positioning B-29 at X=" + spawnX + ", Z=" + spawnZ);
		}

		// Now set position at the 50km spawn point
		// Chunks are already loaded, so this will work
		MainRegistry.logger.info("=== BOMBER SPAWN DEBUG ===");
		MainRegistry.logger.info("Target: X=" + x + ", Z=" + z);
		MainRegistry.logger.info("Approach vector: X=" + vector.x + ", Z=" + vector.z);
		MainRegistry.logger.info("FINAL SPAWN POSITION: X=" + (int)spawnX + ", Z=" + (int)spawnZ);
		if (formation != null) {
			MainRegistry.logger.info("Formation ID: " + formation.getFormationId());
			MainRegistry.logger.info("Row: " + row + ", Column: " + column);
		}
		MainRegistry.logger.info("==========================");

    	this.setLocationAndAngles(
			spawnX,
			flightAltitude,
			spawnZ,
			0.0F, 0.0F
		);

		// Set velocity towards target at cruise speed
    	this.motionX = vector.x * CRUISE_SPEED;
    	this.motionZ = vector.z * CRUISE_SPEED;
    	this.motionY = 0.0D;

    	this.rotation();

		// DEBUG: Log B-29 spawn and flight parameters
		if(!world.isRemote) {
			double distanceToTarget = Math.sqrt((spawnX - x) * (spawnX - x) + (spawnZ - z) * (spawnZ - z));
			double velocityMagnitude = Math.sqrt(motionX * motionX + motionZ * motionZ) * 20.0; // blocks/sec
			MainRegistry.logger.info("========== B-29 SPAWN DEBUG ==========");
			MainRegistry.logger.info("Target: X=" + (int)x + ", Y=" + (int)y + ", Z=" + (int)z);
			MainRegistry.logger.info("Spawn Position: X=" + (int)spawnX + ", Y=" + (int)flightAltitude + ", Z=" + (int)spawnZ);
			MainRegistry.logger.info("Distance to Target: " + (int)distanceToTarget + " blocks");
			MainRegistry.logger.info("Approach Vector: X=" + String.format("%.4f", vector.x) + ", Z=" + String.format("%.4f", vector.z));
			MainRegistry.logger.info("Velocity: X=" + String.format("%.4f", motionX) + ", Z=" + String.format("%.4f", motionZ));
			MainRegistry.logger.info("Velocity Magnitude: " + String.format("%.2f", velocityMagnitude) + " blocks/sec (" + String.format("%.1f", velocityMagnitude * 3.6) + " km/h)");
			MainRegistry.logger.info("Flight Altitude: " + (int)flightAltitude + "m");
			MainRegistry.logger.info("======================================");
		}

    	int i = 1;

    	int rand = world.rand.nextInt(7);

    	switch(rand) {
    	case 0:
    	case 1: i = 1; break;
    	case 2:
    	case 3: i = 2; break;
    	case 4: i = 5; break;
    	case 5: i = 6; break;
    	case 6: i = 7; break;
    	}

    	if(world.rand.nextInt(100) == 0) {
        	rand = world.rand.nextInt(4);

        	switch(rand) {
        	case 0: i = 0; break;
        	case 1: i = 3; break;
        	case 2: i = 4; break;
        	case 3: i = 8; break;
        	}
    	}

    	this.getDataManager().set(STYLE, (byte)i);
    	this.setSize(8.0F, 4.0F);
    }

	/**
	 * Set formation data for this bomber
	 */
	public void setFormation(UUID formationId, int row, int column, boolean isLeader) {
		this.formationId = formationId;
		this.formationRow = row;
		this.formationColumn = column;
		this.isFormationLeader = isLeader;

		// Get formation bombing delay based on row
		if(formationId != null && !world.isRemote) {
			EntityBomberFormation formation = EntityBomberFormationManager.getInstance().getFormation(world, formationId);
			if(formation != null) {
				this.formationBombingDelay = formation.getBombingDelayForRow(row);
			}
		}
	}

	/**
	 * Apply formation offset to position
	 * DEPRECATED: Formation offset is now applied in fac() method at spawn time
	 * This method is kept for compatibility but does nothing
	 */
	public void applyFormationOffset() {
		// NO-OP: Formation offset is now applied during spawn in fac() method
		// The new system uses 10km longitudinal spacing instead of 50m lateral spacing
		// This prevents the old lateral offset from overwriting the new longitudinal offset
		return;
	}

	/**
	 * Synchronize velocity with formation leader
	 * DEPRECATED: New formation system uses independent bombers with longitudinal spacing
	 * Each bomber flies independently to target - no leader following required
	 */
	private void syncWithFormationLeader() {
		// NO-OP: Independent bomber system
		// Each bomber flies to target independently with 10km longitudinal spacing
		// No leader following or lateral formation maintenance needed
		return;
	}

	/**
	 * Find formation leader by searching all entities
	 */
	private EntityBomber findFormationLeader() {
		if(formationId == null) {
			return null;
		}

		// Get formation and its current leader UUID
		EntityBomberFormation formation = EntityBomberFormationManager.getInstance().getFormation(world, formationId);
		if(formation == null) {
			return null;
		}

		UUID leaderUUID = formation.getCurrentLeaderUUID();
		if(leaderUUID == null) {
			return null;
		}

		// Search all EntityBomber entities for the current leader
		for(Entity entity : world.loadedEntityList) {
			if(entity instanceof EntityBomber) {
				EntityBomber bomber = (EntityBomber) entity;
				if(bomber.getUniqueID().equals(leaderUUID)) {
					return bomber;
				}
			}
		}
		return null;
	}

    public static EntityBomber statFacCarpet(World world, double x, double y, double z) {

    	EntityBomber bomber = new EntityBomber(world);

    	bomber.timer = 10000; // Extended for long range flight
    	bomber.bombStart = 50;
    	bomber.bombStop = 100;

		// Randomize bomb loadout - 4 realistic WW2 B-29 configurations
		int loadoutChoice = world.rand.nextInt(4);

		switch(loadoutChoice) {
			case 0: // Heavy bombardment: 8x 2000lb bombs
				bomber.maxBombs = 8;
				bomber.bombRate = 5; // Slower drop rate for heavy bombs
				bomber.bombLoadout = com.hbm.explosion.ExplosionRealisticBomb.BombType.BOMB_2000LB;
				MainRegistry.logger.info("B-29 Loadout: 8x 2000lb bombs (Total: 16,000 lbs)");
				break;
			case 1: // Standard maximum: 40x 500lb bombs
				bomber.maxBombs = 40;
				bomber.bombRate = 1;
				bomber.bombLoadout = com.hbm.explosion.ExplosionRealisticBomb.BombType.BOMB_500LB;
				MainRegistry.logger.info("B-29 Loadout: 40x 500lb bombs (Total: 20,000 lbs)");
				break;
			case 2: // Light load: 20x 500lb bombs
				bomber.maxBombs = 20;
				bomber.bombRate = 2;
				bomber.bombLoadout = com.hbm.explosion.ExplosionRealisticBomb.BombType.BOMB_500LB;
				MainRegistry.logger.info("B-29 Loadout: 20x 500lb bombs (Total: 10,000 lbs)");
				break;
			case 3: // Mixed heavy: 18x 1000lb bombs
				bomber.maxBombs = 18;
				bomber.bombRate = 2;
				bomber.bombLoadout = com.hbm.explosion.ExplosionRealisticBomb.BombType.BOMB_1000LB;
				MainRegistry.logger.info("B-29 Loadout: 18x 1000lb bombs (Total: 18,000 lbs)");
				break;
		}

    	bomber.fac(world, x, y, z);

    	bomber.type = 0;

    	return bomber;
    }
    
    public static EntityBomber statFacNapalm(World world, double x, double y, double z) {

    	EntityBomber bomber = new EntityBomber(world);

    	bomber.timer = 10000;
    	bomber.bombStart = 50;
    	bomber.bombStop = 100;
    	bomber.bombRate = 2;
		bomber.maxBombs = 40;

    	bomber.fac(world, x, y, z);

    	bomber.type = 1;

    	return bomber;
    }

    public static EntityBomber statFacChlorine(World world, double x, double y, double z) {

    	EntityBomber bomber = new EntityBomber(world);

    	bomber.timer = 10000;
    	bomber.bombStart = 50;
    	bomber.bombStop = 100;
    	bomber.bombRate = 2;
		bomber.maxBombs = 40;
		bomber.flightAltitude = FLIGHT_ALTITUDE_LOW; // Low altitude for gas dispersal

    	bomber.fac(world, x, y, z);

    	bomber.type = 2;

    	return bomber;
    }

    public static EntityBomber statFacOrange(World world, double x, double y, double z) {

    	EntityBomber bomber = new EntityBomber(world);

    	bomber.timer = 10000;
    	bomber.bombStart = 75;
    	bomber.bombStop = 125;
    	bomber.bombRate = 1;
		bomber.maxBombs = 40;
		bomber.flightAltitude = FLIGHT_ALTITUDE_LOW; // Low altitude for agent orange dispersal

    	bomber.fac(world, x, y, z);

    	bomber.type = 3;

    	return bomber;
    }

    public static EntityBomber statFacABomb(World world, double x, double y, double z) {

    	EntityBomber bomber = new EntityBomber(world);

    	bomber.timer = 10000;
    	bomber.bombStart = 60;
    	bomber.bombStop = 70;
    	bomber.bombRate = 65;
		bomber.maxBombs = 10; // Atomic bombs - fewer drops

    	bomber.fac(world, x, y, z);

    	int i = 1;

    	int rand = world.rand.nextInt(3);

    	switch(rand) {
    	case 0: i = 5; break;
    	case 1: i = 6; break;
    	case 2: i = 7; break;
    	}

    	if(world.rand.nextInt(100) == 0) {
        	i = 8;
    	}

    	bomber.getDataManager().set(STYLE, (byte)i);

    	bomber.type = 4;

    	return bomber;
    }

    public static EntityBomber statFacStinger(World world, double x, double y, double z) {

    	EntityBomber bomber = new EntityBomber(world);

    	bomber.timer = 10000;
    	bomber.bombStart = 50;
    	bomber.bombStop = 150;
    	bomber.bombRate = 5;
		bomber.maxBombs = 30;

    	bomber.fac(world, x, y, z);

    	bomber.getDataManager().set(STYLE, (byte)4);

    	bomber.type = 5;

    	return bomber;
    }

    public static EntityBomber statFacBoxcar(World world, double x, double y, double z) {

    	EntityBomber bomber = new EntityBomber(world);

    	bomber.timer = 10000;
    	bomber.bombStart = 50;
    	bomber.bombStop = 150;
    	bomber.bombRate = 5;
		bomber.maxBombs = 30;

    	bomber.fac(world, x, y, z);

    	bomber.getDataManager().set(STYLE, (byte)6);

    	bomber.type = 6;

    	return bomber;
    }

    public static EntityBomber statFacPC(World world, double x, double y, double z) {

    	EntityBomber bomber = new EntityBomber(world);

    	bomber.timer = 10000;
    	bomber.bombStart = 75;
    	bomber.bombStop = 125;
    	bomber.bombRate = 1;
		bomber.maxBombs = 40;

    	bomber.fac(world, x, y, z);

    	bomber.getDataManager().set(STYLE, (byte)6);

    	bomber.type = 7;

    	return bomber;
    }

    private Ticket loaderTicket;
    List<ChunkPos> loadedChunks = new ArrayList<ChunkPos>();

	@Override
	public void init(Ticket ticket) {
		if(!world.isRemote) {

            if(ticket != null) {

                if(loaderTicket == null) {

                	loaderTicket = ticket;
                	loaderTicket.bindEntity(this);
                	loaderTicket.getModData();
					MainRegistry.logger.info("[CHUNK LOADER] Ticket initialized for bomber entity ID: " + this.getEntityId());
                } else {
					MainRegistry.logger.warn("[CHUNK LOADER] Ticket already initialized for entity ID: " + this.getEntityId());
				}


                ForgeChunkManager.forceChunk(loaderTicket, new ChunkPos(chunkCoordX, chunkCoordZ));
            } else {
				MainRegistry.logger.error("[CHUNK LOADER] Received NULL ticket for entity ID: " + this.getEntityId());
			}
        }
	}

	public void loadNeighboringChunks(int newChunkX, int newChunkZ)
    {
        if(!world.isRemote && loaderTicket != null)
        {
            for(ChunkPos chunk : loadedChunks)
            {
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

            for(ChunkPos chunk : loadedChunks)
            {
                ForgeChunkManager.forceChunk(loaderTicket, chunk);
            }
        }
    }

	// Enhanced chunk loading from EntityMissileBaseAdvanced
	public void loadMainChunk() {
		if(!world.isRemote && loaderTicket != null){
			ChunkPos currentChunk = new ChunkPos((int) Math.floor(this.posX / 16D), (int) Math.floor(this.posZ / 16D));
			if(mainChunk == null){
				ForgeChunkManager.forceChunk(loaderTicket, currentChunk);
				this.mainChunk = currentChunk;
			} else if(!mainChunk.equals(currentChunk)){
				ForgeChunkManager.forceChunk(loaderTicket, currentChunk);
				ForgeChunkManager.unforceChunk(loaderTicket, this.mainChunk);
				this.mainChunk = currentChunk;
			}
		}
	}

	public void unloadMainChunk() {
		if(!world.isRemote && loaderTicket != null && this.mainChunk != null) {
			ForgeChunkManager.unforceChunk(loaderTicket, this.mainChunk);
		}
	}

	public void clearLoadedChunks() {
		if(!world.isRemote && loaderTicket != null && loadedChunks != null) {
			for(ChunkPos chunk : loadedChunks) {
				ForgeChunkManager.unforceChunk(loaderTicket, chunk);
			}
		}
		// DO NOT unload bombing corridor chunks here!
		// Chunks must stay loaded until all bombs detonate (even if bomber is destroyed)
		// unloadAllBombGroupChunks() is called only when all bombs finish (see notifyBombDetonation)
	}

	@Override
	protected void entityInit() {
		Ticket requestedTicket = ForgeChunkManager.requestTicket(MainRegistry.instance, world, Type.ENTITY);
		if(requestedTicket == null) {
			MainRegistry.logger.error("[CHUNK LOADER] ForgeChunkManager.requestTicket() returned NULL! No chunk loading available.");
		} else {
			MainRegistry.logger.info("[CHUNK LOADER] Ticket requested successfully");
		}
		init(requestedTicket);
        this.getDataManager().register(STYLE, Byte.valueOf((byte)0));
        this.getDataManager().register(HEALTH, Integer.valueOf((int)50));

	}

	@Override
	protected void readEntityFromNBT(NBTTagCompound nbt) {
		ticksExisted = nbt.getInteger("ticksExisted");
		bombStart = nbt.getInteger("bombStart");
		bombStop = nbt.getInteger("bombStop");
		bombRate = nbt.getInteger("bombRate");
		type = nbt.getInteger("type");

		// Read new realistic B-29 fields
		targetX = nbt.getDouble("targetX");
		targetY = nbt.getDouble("targetY");
		targetZ = nbt.getDouble("targetZ");
		hasDroppedBombs = nbt.getBoolean("hasDroppedBombs");
		isEscaping = nbt.getBoolean("isEscaping");
		bombsDropped = nbt.getInteger("bombsDropped");
		maxBombs = nbt.getInteger("maxBombs");
		currentSpeed = nbt.getDouble("currentSpeed");
		hasNotifiedFirstBomb = nbt.getBoolean("hasNotifiedFirstBomb");
		lastCountdownSecond = nbt.getInteger("lastCountdownSecond");
		flightAltitude = nbt.getDouble("flightAltitude");

		// Read calling player UUID
		if(nbt.hasUniqueId("callingPlayerUUID")) {
			callingPlayerUUID = nbt.getUniqueId("callingPlayerUUID");
		}

		// Read formation data
		if(nbt.hasUniqueId("formationId")) {
			formationId = nbt.getUniqueId("formationId");
		}
		formationRow = nbt.getInteger("formationRow");
		formationColumn = nbt.getInteger("formationColumn");
		isFormationLeader = nbt.getBoolean("isFormationLeader");
		formationBombingDelay = nbt.getInteger("formationBombingDelay");
		hasNotifiedFormationAdd = nbt.getBoolean("hasNotifiedFormationAdd");
		dropZoneReachedTick = nbt.getInteger("dropZoneReachedTick");
		formationOffsetApplied = nbt.getBoolean("formationOffsetApplied");

    	this.getDataManager().set(STYLE, nbt.getByte("style"));
    	this.getDataManager().set(HEALTH, nbt.getInteger("health"));
    	this.setSize(8.0F, 4.0F);

	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt) {
		nbt.setInteger("ticksExisted", ticksExisted);
		nbt.setInteger("bombStart", bombStart);
		nbt.setInteger("bombStop", bombStop);
		nbt.setInteger("bombRate", bombRate);
		nbt.setInteger("type", type);

		// Write new realistic B-29 fields
		nbt.setDouble("targetX", targetX);
		nbt.setDouble("targetY", targetY);
		nbt.setDouble("targetZ", targetZ);
		nbt.setBoolean("hasDroppedBombs", hasDroppedBombs);
		nbt.setBoolean("isEscaping", isEscaping);
		nbt.setInteger("bombsDropped", bombsDropped);
		nbt.setInteger("maxBombs", maxBombs);
		nbt.setDouble("currentSpeed", currentSpeed);
		nbt.setBoolean("hasNotifiedFirstBomb", hasNotifiedFirstBomb);
		nbt.setInteger("lastCountdownSecond", lastCountdownSecond);
		nbt.setDouble("flightAltitude", flightAltitude);

		// Write calling player UUID
		if(callingPlayerUUID != null) {
			nbt.setUniqueId("callingPlayerUUID", callingPlayerUUID);
		}

		// Write formation data
		if(formationId != null) {
			nbt.setUniqueId("formationId", formationId);
		}
		nbt.setInteger("formationRow", formationRow);
		nbt.setInteger("formationColumn", formationColumn);
		nbt.setBoolean("isFormationLeader", isFormationLeader);
		nbt.setInteger("formationBombingDelay", formationBombingDelay);
		nbt.setBoolean("hasNotifiedFormationAdd", hasNotifiedFormationAdd);
		nbt.setInteger("dropZoneReachedTick", dropZoneReachedTick);
		nbt.setBoolean("formationOffsetApplied", formationOffsetApplied);

		nbt.setByte("style", this.getDataManager().get(STYLE));
		nbt.setInteger("health", this.getDataManager().get(HEALTH));

	}
	
	protected void rotation() {
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
	@SideOnly(Side.CLIENT)
	public boolean isInRangeToRenderDist(double distance) {
		// 50km render distance (50000 blocks, squared for distance check)
		return distance < 2500000000.0; // 50000^2
	}

	/**
	 * BALLISTIC TRAJECTORY Chunk Loading System - DISABLED
	 *
	 * NO-OP: Bombs now load their own chunks (3x3 radius) as they fall
	 * This reduces lag and simplifies chunk management by distributing chunk loading
	 * across the entire bombing run instead of pre-loading everything at once.
	 *
	 * Each bomb entity (EntityBombletZeta) now manages its own 3x3 chunk radius
	 * as it falls, which is more efficient and prevents the TPS spike that occurred
	 * when the bomber tried to pre-load hundreds of chunks for the entire trajectory.
	 */
	private void preloadBombingCorridor() {
		// NO-OP: Bombs now load their own chunks (3x3 radius) as they fall
		// No need for bomber to pre-load entire ballistic trajectory
		// This reduces lag and simplifies chunk management
		return;
	}

	// DEPRECATED: No longer used - bombs load their own chunks during fall
	// This method was used to calculate bomb trajectories for pre-loading chunks,
	// but is no longer needed since bombs now manage their own 3x3 chunk radius.
	//
	// private java.util.List<double[]> calculateBombTrajectory(
	// 	double startX, double startY, double startZ,
	// 	double velX, double velY, double velZ
	// ) {
	// 	java.util.List<double[]> trajectory = new java.util.ArrayList<>();
	//
	// 	// Initial conditions
	// 	double x = startX;
	// 	double y = startY;
	// 	double z = startZ;
	// 	double vx = velX;
	// 	double vy = velY;
	// 	double vz = velZ;
	//
	// 	// Physics constants (from EntityBombletZeta)
	// 	final double GRAVITY = 0.05; // blocks/tick^2
	// 	final double DRAG_COEFFICIENT = 0.99; // simplified drag model
	//
	// 	// Sample every 100m of vertical fall
	// 	int lastSampleAltitude = (int)(y / 100) * 100;
	// 	trajectory.add(new double[]{x, y, z}); // Add starting point
	//
	// 	// Simulate until ground (y <= 64) or max iterations
	// 	int maxIterations = 20000; // ~16 minutes of fall time
	// 	for(int tick = 0; tick < maxIterations && y > 64; tick++) {
	// 		// Apply gravity
	// 		vy -= GRAVITY;
	//
	// 		// Apply drag (simplified - exponential air density would be more accurate)
	// 		vx *= DRAG_COEFFICIENT;
	// 		vy *= DRAG_COEFFICIENT;
	// 		vz *= DRAG_COEFFICIENT;
	//
	// 		// Update position
	// 		x += vx;
	// 		y += vy;
	// 		z += vz;
	//
	// 		// Sample point every 100m vertical
	// 		int currentAltitude = (int)(y / 100) * 100;
	// 		if(currentAltitude < lastSampleAltitude) {
	// 			trajectory.add(new double[]{x, y, z});
	// 			lastSampleAltitude = currentAltitude;
	// 		}
	// 	}
	//
	// 	// Add final ground impact point
	// 	trajectory.add(new double[]{x, y, z});
	//
	// 	return trajectory;
	// }

	/**
	 * Register a bomb dropped by this bomber
	 */
	public void registerBomb(com.hbm.entity.projectile.EntityBombletZeta bomb) {
		activeBombs.put(bomb.getUniqueID(), bomb);
		hasBombingStarted = true;
	}

	/**
	 * Notify bomber that a bomb has detonated
	 * Called by EntityBombletZeta when it explodes
	 */
	public void notifyBombDetonation(java.util.UUID bombId) {
		if(!world.isRemote) {
			activeBombs.remove(bombId);
			bombsDetonated++;

			// Check if all bombs have detonated
			if(bombsDetonated >= maxBombs && activeBombs.isEmpty()) {
				// All bombs dropped and detonated - unload all chunks
				unloadAllBombGroupChunks();
			}
		}
	}

	/**
	 * Unload all chunks for the bombing corridor
	 */
	private void unloadAllBombGroupChunks() {
		if(world.isRemote || loaderTicket == null) return;

		if(!bombingCorridorChunks.isEmpty()) {
			MainRegistry.logger.info("[BOMBING CORRIDOR] Unloading " + bombingCorridorChunks.size() + " chunks (all bombs detonated)");
			for(ChunkPos chunkPos : bombingCorridorChunks) {
				ForgeChunkManager.unforceChunk(loaderTicket, chunkPos);
			}
			bombingCorridorChunks.clear();
		}
		hasBombingStarted = false;
		hasLoadedBombingCorridor = false;
	}

	/**
	 * Get bomber UUID for bomb group tracking
	 */
	public java.util.UUID getBomberUUID() {
		return this.getUniqueID();
	}

	// DEPRECATED: No longer used - bombs load their own chunks during fall
	// This method was used to trigger chunk pre-loading for all bombers in a formation,
	// but is no longer needed since bombs now manage their own 3x3 chunk radius.
	//
	// private void triggerFormationChunkPreload(EntityBomberFormation formation) {
	// 	// Get all entities in the world
	// 	for(net.minecraft.entity.Entity entity : world.loadedEntityList) {
	// 		// Check if it's a bomber in this formation
	// 		if(entity instanceof EntityBomber) {
	// 			EntityBomber bomber = (EntityBomber)entity;
	// 			if(bomber.formationId != null && bomber.formationId.equals(formation.getFormationId())) {
	// 				// Tell this bomber to pre-load its bombing corridor
	// 				bomber.preloadBombingCorridor();
	// 			}
	// 		}
	// 	}
	// }

}
