package com.hbm.entity.missile;

import com.hbm.entity.logic.IChunkLoader;
import com.hbm.physics.IRCSProvider;
import com.hbm.explosion.ExplosionLarge;
import com.hbm.interfaces.IConstantRenderer;
import com.hbm.main.MainRegistry;

import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.ForgeChunkManager.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract physics-engine base for all realistic AA/SAM missiles.
 * Extends Entity directly — independent of the "unrealistic" ballistic arc system
 * in EntityMissileBaseAdvanced.
 *
 * Subclasses must implement:
 *   – Physical constants  (getDryMass, getThrust, getDrag… methods)
 *   – Guidance factories  (createLiftGuidance, createTerminalGuidance… methods)
 *   – Target info         (getTargetPosition, getTargetVelocity)
 *   – Lifecycle hooks     (checkTermination, onBoosterSeparation, onImpact)
 *   – Debris              (getDebris, getDebrisRareDrop)
 *   – Radar signature     (getRadarCrossSection via IRCSProvider)
 */
public abstract class EntityMissileBaseRealistic extends Entity
        implements IChunkLoader, IConstantRenderer, IRCSProvider {

    // -------------------------------------------------------------------------
    // Common constants (shared across all realistic missiles)
    // -------------------------------------------------------------------------
    protected static final double GRAVITY = 9.80665;   // m/s²
    protected static final double DT      = 0.05;      // seconds per tick

    // ISA atmosphere layers
    private static final double ISA_T0    = 288.15;  // K  sea level
    private static final double ISA_P0    = 101325.0;// Pa sea level
    private static final double ISA_RHO0  = 1.225;   // kg/m³ sea level

    // -------------------------------------------------------------------------
    // Flight-phase / mode enums
    // -------------------------------------------------------------------------
    public enum FlightPhase { LIFT, TURN, BOOST, CRUISE, COAST, TERMINAL }
    public enum FlightMode   { BOOST, SUSTAIN_HIGH, SUSTAIN_LOW, COAST }

    // -------------------------------------------------------------------------
    // Protected physics state
    // -------------------------------------------------------------------------
    protected double velX = 0.0, velY = 0.0, velZ = 0.0; // m/s
    protected double mAngPitch = 0.0, mAngYaw = 0.0;     // missile body angles (rad)
    protected double thAngPitch = 0.0, thAngYaw = 0.0;   // thrust vector angles (rad)
    protected int    age = 0;

    protected FlightPhase flightPhase = FlightPhase.LIFT;
    protected FlightMode  flightMode  = FlightMode.BOOST;

    // NOTE: NO field initializers here — entityInit() sets these via getInitialBoosterFuel()
    // / getInitialSustainerFuel(). Adding "= 0.0" would run AFTER entityInit() (Java
    // field-initializer execution order) and silently reset them to zero every launch.
    protected double boosterFuel;   // kg remaining — set by entityInit()
    protected double sustainerFuel; // kg remaining — set by entityInit()
    protected boolean boosterAttached = true;

    // -------------------------------------------------------------------------
    // Health (for damage system compatibility)
    // -------------------------------------------------------------------------
    public static final DataParameter<Integer> HEALTH =
            EntityDataManager.createKey(EntityMissileBaseRealistic.class, DataSerializers.VARINT);
    public int health = 50;

    // -------------------------------------------------------------------------
    // Chunk loading state (mirrors EntityMissileBaseAdvanced)
    // -------------------------------------------------------------------------
    private Ticket loaderTicket;
    protected int chunkX = 0, chunkZ = 0;
    private final List<ChunkPos> loadedChunks = new ArrayList<ChunkPos>();
    private ChunkPos mainChunk;

    // -------------------------------------------------------------------------
    // Guidance objects (created once by factory methods in subclass)
    // -------------------------------------------------------------------------
    private EntityMissileBaseGuidance liftGuidance;
    private EntityMissileBaseGuidance turnGuidance;
    private EntityMissileBaseGuidance midcourseGuidance;
    private EntityMissileBaseGuidance coastGuidance;
    private EntityMissileBaseGuidance terminalGuidanceFar;
    private EntityMissileBaseGuidance terminalGuidanceNear;
    private EntityMissileBaseGuidance terminalGuidanceEndgame;
    private boolean guidanceInitialized = false;

    // -------------------------------------------------------------------------
    // ARH seeker model  (Palumbo 2010, Figure 6/7, Eq.24 / Eq.27-28)
    // First-order track loop: θ̇(s)/λ̇(s) = 1/(τ_s·s + 1)
    // Discrete IIR:  x̂[k] = β·x̂[k-1] + (1-β)·x_true[k],  β = exp(-DT/τ_s)
    // Applied only during TERMINAL phase when the onboard ARH seeker is active.
    // During midcourse/coast the subclass provides datalink/inertial truth directly.
    // -------------------------------------------------------------------------
    /** Seeker track loop time constant (s). Reduced to 0.05 s to keep the
     *  IIR lag-induced crosstrack miss offset (= TAU_SEEKER × v_crosstrack)
     *  well below the 20 m proximity fuse radius at all approach geometries. */
    private static final double TAU_SEEKER   = 0.05;
    private double  seekerEstPosX, seekerEstPosY, seekerEstPosZ;
    private double  seekerEstVelX, seekerEstVelY, seekerEstVelZ;
    private boolean seekerEstValid = false;

    // -------------------------------------------------------------------------
    // Flight control lag state (Palumbo 2010 Eq.37: G_FC(s) = 1/(τ_FC·s+1))
    // Discrete IIR on command angle:  cmd[k] = β·cmd[k-1] + (1-β)·thAng
    // -------------------------------------------------------------------------
    private double cmdAngPitch = 0.0;
    private double cmdAngYaw   = 0.0;

    // -------------------------------------------------------------------------
    // Aerodynamic lift lag state (Palumbo 2010 Eq.38: G_A(s) = (τ_A·s+1)/v_m)
    // Discrete IIR on lift force:  laggedFl[k] = β·laggedFl[k-1] + (1-β)·fl
    // -------------------------------------------------------------------------
    private double laggedFlx = 0.0, laggedFly = 0.0, laggedFlz = 0.0;

    // -------------------------------------------------------------------------
    // Angle-domain seeker dish state (Palumbo 2010 Eq.24 in angle space)
    // Dish track loop: dishDot = (λ_true - dish) / τ_s
    // LOS Reconstruction Approach 1 (Eq.27): λ_m = ε_m + ∫θ̇dt → position space
    // -------------------------------------------------------------------------
    private double  seekerDishPitch = 0.0;
    private double  seekerDishYaw   = 0.0;
    private boolean seekerDishValid = false;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------
    public EntityMissileBaseRealistic(World worldIn) {
        super(worldIn);
        this.ignoreFrustumCheck = true;
        this.setSize(1.5F, 9F);
    }

    public EntityMissileBaseRealistic(World world, float x, float y, float z, int tX, int tZ) {
        super(world);
        this.ignoreFrustumCheck = true;
        this.setLocationAndAngles(x, y, z, 0, 0);
        // Start from rest — realistic physics engine drives all motion
        this.motionX = 0; this.motionY = 0; this.motionZ = 0;
    }

    // =========================================================================
    // ABSTRACT PHYSICAL CONSTANTS  (must be overridden by subclass)
    // =========================================================================

    protected abstract double getDryMass();           // kg (without any fuel)
    protected abstract double getInitialBoosterFuel();// kg
    protected abstract double getInitialSustainerFuel();// kg
    protected abstract double getReferenceArea();     // m²

    protected abstract double getBoosterThrustSL();   // N at sea level
    protected abstract double getBoosterThrustVac();  // N in vacuum
    protected abstract double getBoosterIsp();        // s

    protected abstract double getSustainerThrustHigh();// N
    protected abstract double getSustainerThrustLow(); // N
    protected abstract double getSustainerIsp();       // s

    protected abstract double getCDSubsonic();    // drag coefficient Mach<0.8
    protected abstract double getCDSupersonic();  // drag coefficient Mach>1.2
    protected abstract double getCLAlpha();       // lift-curve slope
    protected abstract double getKInducedDrag();  // induced drag factor
    protected abstract double getMaxAoA();        // radians
    protected abstract double getMaxG(FlightPhase phase); // structural G limit

    /**
     * Flight control system time constant τ_FC (s).
     * Palumbo 2010 Eq.37: G_FC(s) = 1/(τ_FC·s+1).
     * Models the finite response bandwidth of the autopilot.
     * Default 0.15 s (typical agile SAM); override in subclass for missile-specific value.
     */
    protected double getFlightControlTimeConstant() { return 0.15; }

    /**
     * Aerodynamic turning rate time constant τ_A (s).
     * Palumbo 2010 Eq.38: G_A(s) = (τ_A·s+1)/v_m.
     * Models the delay between control surface deflection and lift force buildup.
     * Default 0.08 s; override in subclass for missile-specific value.
     */
    protected double getAeroTimeConstant() { return 0.08; }

    // =========================================================================
    // ABSTRACT GUIDANCE FACTORIES  (override to supply missile-specific laws)
    // =========================================================================

    protected abstract EntityMissileBaseGuidance createLiftGuidance();
    protected abstract EntityMissileBaseGuidance createTurnGuidance();
    protected abstract EntityMissileBaseGuidance createMidcourseGuidance();
    protected abstract EntityMissileBaseGuidance createCoastGuidance();
    protected abstract EntityMissileBaseGuidance createTerminalGuidanceFar();
    protected abstract EntityMissileBaseGuidance createTerminalGuidanceNear();

    /**
     * Guidance law for the terminal endgame phase (range < 2000 m).
     *
     * PN/APN with N=5 becomes unstable when t_go < N·τ_total = 5·0.28 = 1.40 s,
     * corresponding to range ≈ 794 m (Vc ≈ 567 m/s).  Within this envelope the
     * ZEM accumulation oscillates and the missile can miss by hundreds of metres.
     *
     * Default: null — falls back to terminalGuidanceNear.  Override with a
     * simpler, unconditionally-stable law (e.g. GuidanceLOS) for missiles that
     * need high hit probability at close range.
     */
    protected EntityMissileBaseGuidance createTerminalGuidanceEndgame() { return null; }

    // =========================================================================
    // ABSTRACT TARGET INFO  (override to supply seeker/datalink data)
    // =========================================================================

    /** Target world position in metres. Returns null if no target. */
    protected abstract double[] getTargetPosition();
    /** Target velocity in m/s. Returns {0,0,0} if unknown. */
    protected abstract double[] getTargetVelocity();

    // =========================================================================
    // ABSTRACT LIFECYCLE HOOKS
    // =========================================================================

    /**
     * Called every server tick before guidance commands are applied.
     * Override to run seeker acquisition, datalink updates, etc.
     */
    protected void onPreGuidance() {}

    /**
     * Called every server tick after physics update.
     * @return true if the missile should detonate (proximity fuse, impact, etc.)
     */
    protected abstract boolean checkTermination();

    /** Called when booster fuel runs out and booster separates. */
    protected abstract void onBoosterSeparation();

    /** Called when the missile detonates. */
    public abstract void onImpact();

    /** Debris items dropped on kill. */
    public abstract List<ItemStack> getDebris();

    public abstract ItemStack getDebrisRareDrop();

    // =========================================================================
    // CHUNK LOADING  (IChunkLoader implementation, mirrors EntityMissileBaseAdvanced)
    // =========================================================================

    @Override
    public void init(Ticket ticket) {
        if (!world.isRemote) {
            if (ticket != null) {
                if (loaderTicket == null) {
                    loaderTicket = ticket;
                    loaderTicket.bindEntity(this);
                    loaderTicket.getModData();
                }
                ForgeChunkManager.forceChunk(loaderTicket,
                        new ChunkPos(chunkCoordX, chunkCoordZ));
            }
        }
    }

    @Override
    public void loadNeighboringChunks(int newChunkX, int newChunkZ) {
        if (!world.isRemote && loaderTicket != null) {
            for (ChunkPos chunk : loadedChunks) {
                ForgeChunkManager.unforceChunk(loaderTicket, chunk);
            }
            loadedChunks.clear();
            loadedChunks.add(new ChunkPos(newChunkX,     newChunkZ));
            loadedChunks.add(new ChunkPos(newChunkX + 1, newChunkZ + 1));
            loadedChunks.add(new ChunkPos(newChunkX - 1, newChunkZ - 1));
            loadedChunks.add(new ChunkPos(newChunkX + 1, newChunkZ - 1));
            loadedChunks.add(new ChunkPos(newChunkX - 1, newChunkZ + 1));
            loadedChunks.add(new ChunkPos(newChunkX + 1, newChunkZ));
            loadedChunks.add(new ChunkPos(newChunkX,     newChunkZ + 1));
            loadedChunks.add(new ChunkPos(newChunkX - 1, newChunkZ));
            loadedChunks.add(new ChunkPos(newChunkX,     newChunkZ - 1));
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

    public void loadMainChunk() {
        if (!world.isRemote && loaderTicket != null) {
            ChunkPos currentChunk = new ChunkPos(
                    (int) Math.floor(this.posX / 16D),
                    (int) Math.floor(this.posZ / 16D));
            if (mainChunk == null) {
                ForgeChunkManager.forceChunk(loaderTicket, currentChunk);
                this.mainChunk = currentChunk;
            } else if (!mainChunk.equals(currentChunk)) {
                ForgeChunkManager.forceChunk(loaderTicket, currentChunk);
                ForgeChunkManager.unforceChunk(loaderTicket, this.mainChunk);
                this.mainChunk = currentChunk;
            }
        }
    }

    public void unloadMainChunk() {
        if (!world.isRemote && loaderTicket != null && this.mainChunk != null) {
            ForgeChunkManager.unforceChunk(loaderTicket, this.mainChunk);
        }
    }

    // =========================================================================
    // INITIALISATION
    // =========================================================================

    @Override
    protected void entityInit() {
        // Obtain ForgeChunkManager ticket directly (no parent dependency)
        init(ForgeChunkManager.requestTicket(MainRegistry.instance, world, Type.ENTITY));
        this.getDataManager().register(HEALTH, Integer.valueOf(this.health));
        boosterFuel   = getInitialBoosterFuel();
        sustainerFuel = getInitialSustainerFuel();
        velX = 0; velY = 0; velZ = 0;
    }

    private void initGuidance() {
        liftGuidance              = createLiftGuidance();
        turnGuidance              = createTurnGuidance();
        midcourseGuidance         = createMidcourseGuidance();
        coastGuidance             = createCoastGuidance();
        terminalGuidanceFar       = createTerminalGuidanceFar();
        terminalGuidanceNear      = createTerminalGuidanceNear();
        terminalGuidanceEndgame   = createTerminalGuidanceEndgame(); // may be null
        guidanceInitialized       = true;

        // Prime body angles to the correct launch direction so that
        // thrust is effective from tick 1 (before velocity builds up).
        double[] tPos = getTargetPosition();
        if (tPos != null) {
            double dx = tPos[0] - posX;
            double dz = tPos[2] - posZ;
            mAngYaw   = Math.atan2(dx, dz);           // bearing to target
            mAngPitch = Math.toRadians(80.0);          // vertical launch (SM-6 style)
        } else {
            mAngPitch = Math.toRadians(80.0);          // default: vertical
        }
        // Seed τ_FC command angles from initial body angles so the first tick
        // does not apply a spurious lag-up transient (Palumbo 2010 Eq.37).
        cmdAngPitch = mAngPitch;
        cmdAngYaw   = mAngYaw;
        thAngPitch  = mAngPitch;
        thAngYaw    = mAngYaw;
    }

    // =========================================================================
    // MAIN UPDATE  (complete replacement of Entity.onUpdate() — no super call)
    // =========================================================================

    @Override
    public void onUpdate() {
        // Vanilla position tracking (needed by client interpolation)
        this.lastTickPosX = this.posX;
        this.lastTickPosY = this.posY;
        this.lastTickPosZ = this.posZ;
        this.ticksExisted++;
        loadMainChunk();

        if (!world.isRemote) {
            age++;
            if (!guidanceInitialized) initGuidance();

            // --- Guidance ---
            updateFlightPhase();
            onPreGuidance();   // seeker acquisition, datalink, etc.
            double[] tPos = getTargetPosition();
            double[] tVel = getTargetVelocity();
            if (tPos != null) {
                updateGuidanceCommands(tPos, tVel);
            }

            // --- Autopilot: drive body angles toward command ---
            updateAutopilot();

            // --- Physics forces → acceleration → velocity ---
            double[] forces = calculatePhysicsForces();
            double curMass  = getCurrentMass();
            velX += (forces[0] / curMass) * DT;
            velY += (forces[1] / curMass) * DT;
            velZ += (forces[2] / curMass) * DT;

            // --- Diagnostic log every tick ---
            // Tag [RDBG] allows grepping from latest.log without reading entire 70 MB file.
            // Logs: aerodynamics (rho, dyn pressure, max achievable G), guidance angles,
            //       τ_FC filtered command, and target geometry.
            if (true) {
                double _V   = Math.sqrt(velX*velX + velY*velY + velZ*velZ);
                double[] _atm = getAtmosphericProperties(posY);
                double _rho = _atm[0];
                double _mach = _atm[1];
                double _q   = 0.5 * _rho * _V * _V * getReferenceArea(); // dynamic pressure × area (N)
                // Max achievable lateral acceleration from aerodynamics at this altitude/speed
                double _maxLiftN = _q * getCLAlpha() * getMaxAoA();
                double _maxAeroG = _maxLiftN / (curMass * GRAVITY);
                // Net vertical force (thrust+lift component − gravity) as G-equivalent
                double _netVertG = forces[1] / (curMass * GRAVITY);
                double[] _tPos = getTargetPosition();
                double _range = -1, _Vc = 0, _tPitch = 0, _tYaw = 0;
                if (_tPos != null) {
                    double _dx = _tPos[0]-posX, _dy = _tPos[1]-posY, _dz = _tPos[2]-posZ;
                    _range = Math.sqrt(_dx*_dx+_dy*_dy+_dz*_dz);
                    double[] _tVelD = getTargetVelocity();
                    if (_tVelD != null) {
                        double _rvx=_tVelD[0]-velX, _rvy=_tVelD[1]-velY, _rvz=_tVelD[2]-velZ;
                        _Vc = -(_dx*_rvx+_dy*_rvy+_dz*_rvz)/_range;
                    }
                    _tPitch = Math.toDegrees(Math.atan2(_dy, Math.max(Math.sqrt(_dx*_dx+_dz*_dz),1)));
                    _tYaw   = Math.toDegrees(Math.atan2(_dx, _dz));
                }
                System.out.println(String.format(
                    "[RDBG age=%d] ph=%s pos=(%.0f,%.0f,%.0f) V=%.0f M=%.2f | rho=%.4f q=%.0fN maxLiftG=%.2fG netVertG=%.2fG | mP=%.1f cmdP=%.1f thP=%.1f mY=%.1f thY=%.1f losY=%.1f (deg) | range=%.0fm Vc=%.1fm/s losPitch=%.1fdeg",
                    age, flightPhase, posX, posY, posZ, _V, _mach,
                    _rho, _q, _maxAeroG, _netVertG,
                    Math.toDegrees(mAngPitch), Math.toDegrees(cmdAngPitch), Math.toDegrees(thAngPitch),
                    Math.toDegrees(mAngYaw),   Math.toDegrees(thAngYaw),   _tYaw,
                    _range, _Vc, _tPitch));
            }

            // --- Position update ---
            this.motionX = velX * DT;
            this.motionY = velY * DT;
            this.motionZ = velZ * DT;
            updateAnglesFromVelocity();
            this.posX += this.motionX;
            this.posY += this.motionY;
            this.posZ += this.motionZ;
            // Forge 1.12.2: Entity.getEntityBoundingBox() returns a CACHED AxisAlignedBB
            // that is only updated when setPosition() is called.  Direct assignment of
            // posX/Y/Z (above) does NOT sync the AABB.  Without this call the bounding
            // box remains at the entity's spawn position for its entire lifetime, so
            // World.getEntitiesWithinAABBExcludingEntity() in checkTermination() always
            // searches near spawn — the proximity fuse can never fire at range.
            this.setPosition(this.posX, this.posY, this.posZ);

            // --- Termination check (proximity fuse, ground, etc.) ---
            if (checkTermination()) {
                onImpact();
                clearLoadedChunks();
                unloadMainChunk();
                this.setDead();
                return;
            }

            // Ground / block collision
            // Use age (server-authoritative tick counter) to match the proximity-fuse
            // arm time — avoids detonating on the launch structure during the first second.
            if (checkBlockCollision()) {
                if (age >= 60) onImpact();
                clearLoadedChunks();
                unloadMainChunk();
                this.setDead();
                return;
            }

            // Below void: remove silently
            if (this.posY < 0) {
                clearLoadedChunks();
                unloadMainChunk();
                this.setDead();
                return;
            }

            // --- Fuel consumption ---
            updateFuelConsumption();
            if (boosterAttached && boosterFuel <= 0.0) separateBooster();
            updateFlightMode();

            // --- Chunk loading ---
            int cx = (int)(posX / 16), cz = (int)(posZ / 16);
            if (cx != chunkX || cz != chunkZ) {
                chunkX = cx; chunkZ = cz;
                loadNeighboringChunks(cx, cz);
            }
        }

        // --- Client-side particles ---
        if (world.isRemote) {
            spawnExhaustParticles();
        }
    }

    // =========================================================================
    // FLIGHT PHASE TUNING HOOKS
    // =========================================================================

    /**
     * Tick at which the TURN phase ends and BOOST (APG midcourse) begins.
     * Default = 160 (SM-6 VLS: LIFT 0-60, TURN 60-160).
     * Subclasses may override to extend the powered pitch-over phase and reduce
     * the flight-path angle at APG entry (fewer altitude overshoot problems).
     */
    protected int getTurnEndTick() { return 160; }

    // =========================================================================
    // FLIGHT PHASE STATE MACHINE
    // =========================================================================

    protected void updateFlightPhase() {
        switch (flightPhase) {
            case LIFT:
                if (age >= 60) {
                    flightPhase = FlightPhase.TURN;
                    if (turnGuidance != null) turnGuidance.reset();
                    double V60 = Math.sqrt(velX*velX+velY*velY+velZ*velZ);
                    System.out.println(String.format(
                        "[RDBG age=%d] LIFT→TURN pos=(%.0f,%.0f,%.0f) V=%.0f angP=%.1f angY=%.1f",
                        age, posX, posY, posZ, V60,
                        Math.toDegrees(mAngPitch), Math.toDegrees(mAngYaw)));
                }
                break;
            case TURN:
                // Primary condition: body pitch AND yaw aligned to target LOS.
                // Both axes must be within threshold before handing off to PN guidance,
                // otherwise the missile exits TURN pointing the wrong azimuth and flies
                // away from the target (Palumbo §3.1).
                // Fallback: age-based cap (getTurnEndTick()) prevents infinite TURN
                // if the target is directly overhead or the seeker has no data.
                boolean turnAligned = false;
                double losPitchDbg = 0, losYawDbg = 0, angErrPitchDbg = 0, angErrYawDbg = 0;
                double[] tPosTurn = getTargetPosition();
                if (tPosTurn != null) {
                    double tdx = tPosTurn[0] - posX;
                    double tdy = tPosTurn[1] - posY;
                    double tdz = tPosTurn[2] - posZ;
                    double rXZ = Math.sqrt(tdx*tdx + tdz*tdz);
                    losPitchDbg    = Math.atan2(tdy, Math.max(rXZ, 1.0));
                    // mAngYaw convention: atan2(velX, velZ) — East=+90°, North=0°
                    losYawDbg      = Math.atan2(tdx, tdz);
                    angErrPitchDbg = Math.abs(normalizeAngle(mAngPitch - losPitchDbg));
                    angErrYawDbg   = Math.abs(normalizeAngle(mAngYaw   - losYawDbg));
                    if (angErrPitchDbg < Math.toRadians(15.0) && angErrYawDbg < Math.toRadians(20.0))
                        turnAligned = true;
                }
                if (turnAligned || age >= getTurnEndTick()) {
                    double VT = Math.sqrt(velX*velX+velY*velY+velZ*velZ);
                    System.out.println(String.format(
                        "[RDBG age=%d] TURN→BOOST aligned=%b pitchErr=%.1fdeg yawErr=%.1fdeg losPitch=%.1fdeg losYaw=%.1fdeg pos=(%.0f,%.0f,%.0f) V=%.0f mAngP=%.1f mAngY=%.1f",
                        age, turnAligned,
                        Math.toDegrees(angErrPitchDbg), Math.toDegrees(angErrYawDbg),
                        Math.toDegrees(losPitchDbg), Math.toDegrees(losYawDbg),
                        posX, posY, posZ, VT, Math.toDegrees(mAngPitch), Math.toDegrees(mAngYaw)));
                    flightPhase = FlightPhase.BOOST;
                    if (midcourseGuidance != null) midcourseGuidance.reset();
                }
                break;
            case BOOST:
                if (boosterFuel <= 0.0) {
                    double VB = Math.sqrt(velX*velX+velY*velY+velZ*velZ);
                    System.out.println(String.format(
                        "[RDBG age=%d] BOOST→CRUISE pos=(%.0f,%.0f,%.0f) V=%.0f angP=%.1f",
                        age, posX, posY, posZ, VB, Math.toDegrees(mAngPitch)));
                    flightPhase = FlightPhase.CRUISE;
                }
                break;
            case CRUISE:
                if (sustainerFuel <= 0.0) {
                    double VC = Math.sqrt(velX*velX+velY*velY+velZ*velZ);
                    System.out.println(String.format(
                        "[RDBG age=%d] CRUISE→COAST pos=(%.0f,%.0f,%.0f) V=%.0f angP=%.1f",
                        age, posX, posY, posZ, VC, Math.toDegrees(mAngPitch)));
                    flightPhase = FlightPhase.COAST;
                    if (coastGuidance != null) coastGuidance.reset();
                } else {
                    double[] tPosCruise = getTargetPosition();
                    if (tPosCruise != null) {
                        double dx = tPosCruise[0] - posX, dy = tPosCruise[1] - posY, dz = tPosCruise[2] - posZ;
                        double range = Math.sqrt(dx*dx + dy*dy + dz*dz);
                        if (range < 20000.0) {
                            double VTerm = Math.sqrt(velX*velX+velY*velY+velZ*velZ);
                            System.out.println(String.format(
                                "[RDBG age=%d] CRUISE→TERMINAL range=%.0fm pos=(%.0f,%.0f,%.0f) V=%.0f angP=%.1f",
                                age, range, posX, posY, posZ, VTerm, Math.toDegrees(mAngPitch)));
                            flightPhase = FlightPhase.TERMINAL;
                            if (terminalGuidanceFar     != null) terminalGuidanceFar.resetGuidanceFiltersOnly();
                            if (terminalGuidanceNear    != null) terminalGuidanceNear.resetGuidanceFiltersOnly();
                            if (terminalGuidanceEndgame != null) terminalGuidanceEndgame.resetGuidanceFiltersOnly();
                        }
                    }
                }
                break;
            case COAST:
                if (getTargetPosition() != null) {
                    double[] tPos = getTargetPosition();
                    double dx = tPos[0] - posX, dy = tPos[1] - posY, dz = tPos[2] - posZ;
                    double range = Math.sqrt(dx*dx + dy*dy + dz*dz);
                    if (range < 20000.0) {
                        double VTerm = Math.sqrt(velX*velX+velY*velY+velZ*velZ);
                        System.out.println(String.format(
                            "[RDBG age=%d] COAST→TERMINAL range=%.0fm pos=(%.0f,%.0f,%.0f) V=%.0f angP=%.1f",
                            age, range, posX, posY, posZ, VTerm, Math.toDegrees(mAngPitch)));
                        flightPhase = FlightPhase.TERMINAL;
                        // Preserve velocity-estimator state (pre-warmed during CRUISE/COAST);
                        // only reset the guidance-command filter so stale CRUISE-phase filter
                        // values don't carry into the first TERMINAL tick.
                        if (terminalGuidanceFar     != null) terminalGuidanceFar.resetGuidanceFiltersOnly();
                        if (terminalGuidanceNear    != null) terminalGuidanceNear.resetGuidanceFiltersOnly();
                        if (terminalGuidanceEndgame != null) terminalGuidanceEndgame.resetGuidanceFiltersOnly();
                    }
                }
                break;
            case TERMINAL:
                break;
        }
    }

    // =========================================================================
    // GUIDANCE DISPATCH
    // =========================================================================

    protected void updateGuidanceCommands(double[] tPos, double[] tVel) {
        if (tPos == null) return;

        // In TERMINAL phase apply ARH seeker track loop (Palumbo 2010, Figure 6/7, Eq.24).
        // θ̇(s)/λ̇(s) = 1/(τ_s·s+1): seeker measures LOS with first-order lag.
        // During midcourse/coast the subclass provides datalink/inertial truth directly.
        double[] guidPos = tPos;
        double[] guidVel = tVel;
        if (flightPhase == FlightPhase.TERMINAL) {
            updateSeekerEstimate(tPos, tVel);
            if (seekerEstValid) {
                guidPos = new double[]{seekerEstPosX, seekerEstPosY, seekerEstPosZ};
                guidVel = new double[]{seekerEstVelX, seekerEstVelY, seekerEstVelZ};
            }
        } else {
            // Reset on phase change so seeker and dish re-acquire cleanly when TERMINAL begins
            seekerEstValid  = false;
            seekerDishValid = false;
        }

        double tVelX = (guidVel != null) ? guidVel[0] : 0.0;
        double tVelY = (guidVel != null) ? guidVel[1] : 0.0;
        double tVelZ = (guidVel != null) ? guidVel[2] : 0.0;

        EntityMissileBaseGuidance law = selectGuidanceLaw();
        if (law == null) return;

        double[] dir = law.computeDirection(
                posX, posY, posZ,
                velX, velY, velZ,
                guidPos[0], guidPos[1], guidPos[2],
                tVelX,      tVelY,      tVelZ,
                age);
        if (dir == null) return;

        // Normalise and convert to pitch/yaw command angles
        double len = Math.sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2]);
        if (len < 1e-6) return;
        double dx = dir[0] / len, dy = dir[1] / len, dz = dir[2] / len;

        thAngPitch = Math.atan2(dy, Math.sqrt(dx*dx + dz*dz));
        thAngYaw   = Math.atan2(dx, dz);

        // Warm up non-selected terminal guidance laws on every TERMINAL-phase tick.
        // Without this, each law's internal LOS-rate filter is cold (reset state) until the
        // moment it becomes selected (e.g. endgame at range=2000m). The cold-start causes
        // a large spurious LOS-rate on the first call, which — amplified by N — produces a
        // physically impossible direction command and an instant miss.
        // By calling computeDirection() on every non-selected law (and discarding the result),
        // all filters stay converged, eliminating the cold-start spike at every law boundary.
        if (flightPhase == FlightPhase.TERMINAL) {
            if (terminalGuidanceFar     != null && law != terminalGuidanceFar)
                terminalGuidanceFar    .computeDirection(posX, posY, posZ, velX, velY, velZ,
                        guidPos[0], guidPos[1], guidPos[2], tVelX, tVelY, tVelZ, age);
            if (terminalGuidanceNear    != null && law != terminalGuidanceNear)
                terminalGuidanceNear   .computeDirection(posX, posY, posZ, velX, velY, velZ,
                        guidPos[0], guidPos[1], guidPos[2], tVelX, tVelY, tVelZ, age);
            if (terminalGuidanceEndgame != null && law != terminalGuidanceEndgame)
                terminalGuidanceEndgame.computeDirection(posX, posY, posZ, velX, velY, velZ,
                        guidPos[0], guidPos[1], guidPos[2], tVelX, tVelY, tVelZ, age);
        }

        // Pre-warm all terminal guidance laws during CRUISE and COAST so the
        // position-based velocity estimator (estTVelX/Y/Z) is fully converged
        // before TERMINAL phase begins.  At TERMINAL entry only the APN filter
        // (filtAPitch/filtAYaw) is reset; the velocity-estimator state carries
        // over, giving correct ZEM prediction from the very first TERMINAL tick.
        //
        // warmupMode = true suppresses APNDBG diagnostic during these background
        // calls to avoid log spam across the hundreds of CRUISE ticks.
        //
        // Uses raw getTargetPosition() (datalink/inertial) rather than the seeker
        // estimate (not active yet).  The position-derived velocity estimate from
        // raw positions is correct; only the tVelIn parameter (DL velocity, which
        // may be stale) is unused by the GuidanceAPN velocity estimator.
        if (flightPhase == FlightPhase.CRUISE || flightPhase == FlightPhase.COAST) {
            double[] wPos = getTargetPosition();
            double[] wVel = getTargetVelocity();
            if (wPos != null) {
                double wVelX = (wVel != null) ? wVel[0] : 0.0;
                double wVelY = (wVel != null) ? wVel[1] : 0.0;
                double wVelZ = (wVel != null) ? wVel[2] : 0.0;
                if (terminalGuidanceFar != null) {
                    terminalGuidanceFar.warmupMode = true;
                    terminalGuidanceFar.computeDirection(posX, posY, posZ, velX, velY, velZ,
                            wPos[0], wPos[1], wPos[2], wVelX, wVelY, wVelZ, age);
                    terminalGuidanceFar.warmupMode = false;
                }
                if (terminalGuidanceNear != null) {
                    terminalGuidanceNear.warmupMode = true;
                    terminalGuidanceNear.computeDirection(posX, posY, posZ, velX, velY, velZ,
                            wPos[0], wPos[1], wPos[2], wVelX, wVelY, wVelZ, age);
                    terminalGuidanceNear.warmupMode = false;
                }
                if (terminalGuidanceEndgame != null) {
                    terminalGuidanceEndgame.warmupMode = true;
                    terminalGuidanceEndgame.computeDirection(posX, posY, posZ, velX, velY, velZ,
                            wPos[0], wPos[1], wPos[2], wVelX, wVelY, wVelZ, age);
                    terminalGuidanceEndgame.warmupMode = false;
                }
            }
        }
    }

    /**
     * Updates the ARH seeker's estimated target position and velocity using the
     * angle-domain track loop model from Palumbo (2010).
     *
     * Seeker dish track loop (Palumbo Eq.24, angle space):
     *   θ̇(s)/λ̇(s) = 1/(τ_s·s+1)
     * Discrete Euler integration:
     *   dishDot = (λ_true - dish) / τ_s
     *   dish[k] = dish[k-1] + dishDot·DT
     *
     * LOS Reconstruction Approach 1 (Palumbo Eq.27):
     *   λ_m = ε_m + ∫θ̇dt
     * The dish angle IS the reconstructed LOS angle λ_m when the error angle ε_m
     * (boresight error) is folded into the integration — dish → position space via
     * spherical projection at the true range (range is assumed known from range gate).
     *
     * Velocity estimate: first-order IIR in position-domain (adequate given
     * the velocity estimate is secondary to the position estimate in PN).
     *
     * On first call (initial acquisition) the dish is seeded with the true LOS
     * (instantaneous lock-on model; Palumbo §3.2 notes lock-on transients are
     * typically handled by the datalink before seeker activation).
     */
    private void updateSeekerEstimate(double[] truePos, double[] trueVel) {
        double dx   = truePos[0] - posX;
        double dy   = truePos[1] - posY;
        double dz   = truePos[2] - posZ;
        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (dist < 1.0) { seekerEstValid = false; seekerDishValid = false; return; }

        // True LOS angles
        double rangeXZ     = Math.sqrt(dx*dx + dz*dz);
        double trueLOSPitch = Math.atan2(dy, Math.max(rangeXZ, 1.0));
        double trueLOSYaw   = Math.atan2(dx, dz);

        if (!seekerDishValid) {
            // Initial acquisition: seed dish to true LOS (no lock-on transient)
            seekerDishPitch = trueLOSPitch;
            seekerDishYaw   = trueLOSYaw;
            seekerDishValid = true;
            // Seed velocity estimate and position (first call returns true values)
            seekerEstVelX = (trueVel != null) ? trueVel[0] : 0.0;
            seekerEstVelY = (trueVel != null) ? trueVel[1] : 0.0;
            seekerEstVelZ = (trueVel != null) ? trueVel[2] : 0.0;
            seekerEstPosX = truePos[0];
            seekerEstPosY = truePos[1];
            seekerEstPosZ = truePos[2];
            seekerEstValid = true;
            return;
        }

        // Palumbo Eq.24 in angle domain: θ̇ = (λ_true - θ) / τ_s
        // Euler integration of dish angle (equivalent to 1st-order IIR but
        // operates on the physically meaningful angle rather than position).
        double dishPitchDot = (trueLOSPitch - seekerDishPitch) / TAU_SEEKER;
        double dishYawDot   = normalizeAngle(trueLOSYaw - seekerDishYaw) / TAU_SEEKER;
        seekerDishPitch += dishPitchDot * DT;
        seekerDishYaw   += dishYawDot   * DT;

        // Palumbo Approach 1 (Eq.27): reconstruct target position from dish angles + range.
        // The guidance law receives position; convert dish angles back to Cartesian.
        // Range is treated as true (radar range gate; negligible range lag for PN).
        double cosDishPitch = Math.cos(seekerDishPitch);
        seekerEstPosX = posX + dist * Math.sin(seekerDishYaw) * cosDishPitch;
        seekerEstPosY = posY + dist * Math.sin(seekerDishPitch);
        seekerEstPosZ = posZ + dist * Math.cos(seekerDishYaw) * cosDishPitch;

        // Velocity: IIR lag in position-domain (Palumbo §2.2, adequate for PN)
        if (trueVel != null) {
            double beta = Math.exp(-DT / TAU_SEEKER);
            seekerEstVelX = beta * seekerEstVelX + (1.0 - beta) * trueVel[0];
            seekerEstVelY = beta * seekerEstVelY + (1.0 - beta) * trueVel[1];
            seekerEstVelZ = beta * seekerEstVelZ + (1.0 - beta) * trueVel[2];
        }
        seekerEstValid = true;
    }

    private EntityMissileBaseGuidance selectGuidanceLaw() {
        switch (flightPhase) {
            case LIFT:     return liftGuidance;
            case TURN:     return turnGuidance;
            case BOOST:
            case CRUISE:   return midcourseGuidance;
            case COAST:    return coastGuidance;
            case TERMINAL:
                double[] tPos = getTargetPosition();
                if (tPos != null) {
                    double dx = tPos[0]-posX, dy = tPos[1]-posY, dz = tPos[2]-posZ;
                    double range = Math.sqrt(dx*dx+dy*dy+dz*dz);
                    // range < 2000 m: endgame phase.  PN/APN (N=5) with τ_total=0.28s
                    // becomes unstable below ~800 m (t_go < N·τ_total = 1.40 s).  A
                    // simpler, unconditionally-stable law (GuidanceLOS) avoids the
                    // ZEM oscillation that previously caused 200+ m miss distances.
                    if (range < 2000.0 && terminalGuidanceEndgame != null)
                        return terminalGuidanceEndgame;
                    return (range < 5000.0) ? terminalGuidanceNear : terminalGuidanceFar;
                }
                return terminalGuidanceFar;
        }
        return null;
    }

    // =========================================================================
    // AUTOPILOT  (drives body angles toward thrust-vector command)
    // =========================================================================

    protected void updateAutopilot() {
        double V = Math.sqrt(velX*velX + velY*velY + velZ*velZ);
        double maxG = getMaxG(flightPhase);
        double maxTurnRate = (maxG * GRAVITY / Math.max(V, 1.0)) * DT;

        // Palumbo 2010 Eq.37: G_FC(s) = 1/(τ_FC·s+1) — flight control response lag.
        // Discrete IIR: β = exp(-DT/τ_FC).
        // The guidance law writes the desired direction into thAngPitch/thAngYaw;
        // the autopilot first filters those through τ_FC before applying G-limited drive.
        // This correctly models the finite bandwidth of the flight control system so that
        // the effective navigation ratio experienced by the missile matches the PDF model.
        double tauFC  = getFlightControlTimeConstant();
        double betaFC = (tauFC > 0.0) ? Math.exp(-DT / tauFC) : 0.0;
        cmdAngPitch = betaFC * cmdAngPitch + (1.0 - betaFC) * thAngPitch;
        cmdAngYaw   = betaFC * cmdAngYaw   + (1.0 - betaFC) * thAngYaw;

        // G-limited rate drive toward the τ_FC-filtered command angle
        double dPitch = normalizeAngle(cmdAngPitch - mAngPitch);
        double dYaw   = normalizeAngle(cmdAngYaw   - mAngYaw);
        dPitch = clamp(dPitch, -maxTurnRate, maxTurnRate);
        dYaw   = clamp(dYaw,   -maxTurnRate, maxTurnRate);
        mAngPitch += dPitch;
        mAngYaw   += dYaw;
    }

    // =========================================================================
    // PHYSICS FORCES
    // =========================================================================

    /**
     * Returns net force vector [Fx, Fy, Fz] in Newtons.
     * Components: thrust, aerodynamic drag, lift, gravity.
     */
    protected double[] calculatePhysicsForces() {
        double V = Math.sqrt(velX*velX + velY*velY + velZ*velZ);
        double altitude = this.posY; // 1 block = 1 m
        double[] atm = getAtmosphericProperties(altitude);
        double rho   = atm[0]; // kg/m³
        double mach  = atm[1]; // Mach number

        // ---- Thrust ----
        double thrust = calculateThrust(altitude, rho);
        double thrustX = thrust * Math.sin(mAngYaw)   * Math.cos(mAngPitch);
        double thrustY = thrust * Math.sin(mAngPitch);
        double thrustZ = thrust * Math.cos(mAngYaw)   * Math.cos(mAngPitch);

        // ---- Aerodynamic forces ----
        if (V > 0.1) {
            // unit velocity
            double uvx = velX / V, uvy = velY / V, uvz = velZ / V;
            // unit thrust direction
            double cos_mP = Math.cos(mAngPitch), sin_mP = Math.sin(mAngPitch);
            double cos_mY = Math.cos(mAngYaw),   sin_mY = Math.sin(mAngYaw);
            double bodyX = sin_mY * cos_mP, bodyY = sin_mP, bodyZ = cos_mY * cos_mP;

            // angle of attack (rad)
            double dot = bodyX*uvx + bodyY*uvy + bodyZ*uvz;
            dot = clamp(dot, -1.0, 1.0);
            double aoa = Math.acos(dot);
            aoa = clamp(aoa, 0.0, getMaxAoA());

            // drag coefficient (transonic interpolation)
            double cd;
            if (mach < 0.8) cd = getCDSubsonic();
            else if (mach > 1.2) cd = getCDSupersonic();
            else { // linear blend in transonic region
                double t = (mach - 0.8) / 0.4;
                cd = getCDSubsonic() + t * (getCDSupersonic() - getCDSubsonic()) + 0.08 * Math.sin(Math.PI * t);
            }
            double cl    = getCLAlpha() * aoa;
            double cd_i  = getKInducedDrag() * cl * cl;
            cd += cd_i;

            double q = 0.5 * rho * V * V * getReferenceArea(); // dynamic pressure * area
            double dragMag = q * cd;
            double liftMag = q * cl;

            // drag opposes velocity
            double fdx = -dragMag * uvx;
            double fdy = -dragMag * uvy;
            double fdz = -dragMag * uvz;

            // lift: perpendicular to velocity, in the plane of body and velocity
            double crossX = bodyY*uvz - bodyZ*uvy;
            double crossY = bodyZ*uvx - bodyX*uvz;
            double crossZ = bodyX*uvy - bodyY*uvx;
            double crossMag = Math.sqrt(crossX*crossX + crossY*crossY + crossZ*crossZ);
            double flxInstant = 0, flyInstant = 0, flzInstant = 0;
            if (crossMag > 1e-6) {
                // lift direction = vel_unit × cross  (perpendicular to velocity, toward body axis).
                // Derivation: vel × (body × vel) = body - vel*(vel·body) by BAC-CAB,
                // which is exactly the component of body perpendicular to velocity —
                // pointing from vel toward body.  Magnitude = sin(aoa)*|vel|*|cross| /|cross|.
                // Note: cross × vel (the reversed order used previously) gives the opposite
                // sign and pushes velocity AWAY from the body — aerodynamic destabilisation.
                double liftDirX = (uvy*crossZ - uvz*crossY) / crossMag;
                double liftDirY = (uvz*crossX - uvx*crossZ) / crossMag;
                double liftDirZ = (uvx*crossY - uvy*crossX) / crossMag;
                flxInstant = liftMag * liftDirX;
                flyInstant = liftMag * liftDirY;
                flzInstant = liftMag * liftDirZ;
            }

            // Palumbo 2010 Eq.38: G_A(s) = (τ_A·s+1)/v_m — aerodynamic turning rate
            // time constant.  Models the delay between control surface deflection and
            // the resulting lift force buildup (fin + airframe flex).
            // Discrete IIR: β = exp(-DT/τ_A).
            // Applied only to lift (drag and thrust respond immediately to body angle).
            double tauA  = getAeroTimeConstant();
            double betaA = (tauA > 0.0) ? Math.exp(-DT / tauA) : 0.0;
            laggedFlx = betaA * laggedFlx + (1.0 - betaA) * flxInstant;
            laggedFly = betaA * laggedFly + (1.0 - betaA) * flyInstant;
            laggedFlz = betaA * laggedFlz + (1.0 - betaA) * flzInstant;

            // ---- Gravity ----
            double mass = getCurrentMass();
            return new double[]{
                    thrustX + fdx + laggedFlx,
                    thrustY + fdy + laggedFly - mass * GRAVITY,
                    thrustZ + fdz + laggedFlz
            };
        } else {
            double mass = getCurrentMass();
            return new double[]{thrustX, thrustY - mass * GRAVITY, thrustZ};
        }
    }

    /**
     * Current thrust output in Newtons, accounting for altitude and propellant mode.
     */
    protected double calculateThrust(double altitude, double rho) {
        if (flightMode == FlightMode.COAST) return 0.0;

        double seaLevelRho = ISA_RHO0;
        double altFrac = rho / seaLevelRho; // 0 (vacuum) to 1 (SL)

        switch (flightMode) {
            case BOOST:
                if (boosterFuel <= 0.0) return 0.0;
                return getBoosterThrustSL() * altFrac + getBoosterThrustVac() * (1.0 - altFrac);
            case SUSTAIN_HIGH:
                if (sustainerFuel <= 0.0) return 0.0;
                return getSustainerThrustHigh();
            case SUSTAIN_LOW:
                if (sustainerFuel <= 0.0) return 0.0;
                return getSustainerThrustLow();
            default:
                return 0.0;
        }
    }

    // =========================================================================
    // ATMOSPHERE – International Standard Atmosphere (ISA 1976)
    // =========================================================================

    /**
     * Returns [density (kg/m³), Mach number] at the given altitude (metres).
     */
    protected double[] getAtmosphericProperties(double altitude) {
        double V = Math.sqrt(velX*velX + velY*velY + velZ*velZ);
        double rho, T;
        if (altitude < 0) altitude = 0;
        if (altitude <= 11000) {          // Troposphere
            T   = ISA_T0 - 0.0065 * altitude;
            rho = ISA_RHO0 * Math.pow(T / ISA_T0, 4.2561);
        } else if (altitude <= 20000) {   // Lower stratosphere (isothermal)
            T   = 216.65;
            rho = 0.36392 * Math.exp(-0.0001577 * (altitude - 11000));
        } else if (altitude <= 32000) {   // Upper stratosphere
            T   = 216.65 + 0.001 * (altitude - 20000);
            rho = 0.08803 * Math.pow(T / 216.65, -17.0816);
        } else {                           // Mesosphere (simplified)
            T   = 228.65 + 0.0028 * (altitude - 32000);
            T   = Math.max(T, 186.87);
            rho = 0.01322 * Math.pow(T / 228.65, -12.2009);
        }
        double speedOfSound = Math.sqrt(1.4 * 287.05 * T);
        double mach = V / speedOfSound;
        return new double[]{rho, mach};
    }

    // =========================================================================
    // FUEL CONSUMPTION
    // =========================================================================

    protected void updateFuelConsumption() {
        switch (flightMode) {
            case BOOST:
                if (boosterFuel > 0.0) {
                    double[] atm = getAtmosphericProperties(posY);
                    double thrust = calculateThrust(posY, atm[0]);
                    double mdot   = thrust / (getBoosterIsp() * GRAVITY);
                    boosterFuel -= mdot * DT;
                    if (boosterFuel < 0.0) boosterFuel = 0.0;
                }
                break;
            case SUSTAIN_HIGH:
            case SUSTAIN_LOW:
                if (sustainerFuel > 0.0) {
                    double[] atm = getAtmosphericProperties(posY);
                    double thrust = calculateThrust(posY, atm[0]);
                    double mdot   = thrust / (getSustainerIsp() * GRAVITY);
                    sustainerFuel -= mdot * DT;
                    if (sustainerFuel < 0.0) sustainerFuel = 0.0;
                }
                break;
            default:
                break;
        }
    }

    protected void separateBooster() {
        boosterAttached = false;
        flightMode = FlightMode.SUSTAIN_HIGH;
        onBoosterSeparation();
    }

    protected void updateFlightMode() {
        if (flightMode == FlightMode.BOOST && boosterFuel <= 0.0) {
            flightMode = FlightMode.SUSTAIN_HIGH;
        }
        if ((flightMode == FlightMode.SUSTAIN_HIGH || flightMode == FlightMode.SUSTAIN_LOW)
                && sustainerFuel <= 0.0) {
            flightMode = FlightMode.COAST;
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    protected double getCurrentMass() {
        return getDryMass()
                + (boosterAttached ? boosterFuel : 0.0)
                + sustainerFuel;
    }

    protected void updateAnglesFromVelocity() {
        // mAngPitch and mAngYaw are controlled exclusively by the autopilot.
        // This method only updates the Minecraft rendering angles.
        // Using velocity direction for display so the model visually points where it travels.
        double V = Math.sqrt(velX*velX + velY*velY + velZ*velZ);
        double dispPitch, dispYaw;
        if (V > 0.1) {
            dispPitch = Math.atan2(velY, Math.sqrt(velX*velX + velZ*velZ));
            dispYaw   = Math.atan2(velX, velZ);
        } else {
            dispPitch = mAngPitch;
            dispYaw   = mAngYaw;
        }
        this.rotationYaw   = (float)(dispYaw   * 180.0 / Math.PI);
        this.rotationPitch = (float)(-dispPitch * 180.0 / Math.PI) + 90.0f;
    }

    protected boolean checkBlockCollision() {
        Block b = this.world.getBlockState(
                new BlockPos((int) posX, (int) posY, (int) posZ)).getBlock();
        return b != Blocks.AIR && b != Blocks.WATER && b != Blocks.FLOWING_WATER;
    }

    /** Called on the client side each tick to emit exhaust particles. */
    protected void spawnExhaustParticles() {
        if (flightMode == FlightMode.COAST) return;
        double V = Math.sqrt(velX*velX + velY*velY + velZ*velZ);
        double uvx = (V > 0.1) ? velX / V : 0;
        double uvy = (V > 0.1) ? velY / V : 1;
        double uvz = (V > 0.1) ? velZ / V : 0;
        int steps = Math.max(1, (int)(V * DT));
        for (int i = 0; i < steps; i++) {
            MainRegistry.proxy.spawnParticle(
                    posX - uvx * i, posY - uvy * i, posZ - uvz * i,
                    "exDark",
                    new float[]{(float)(-uvx * 1.75), (float)(-uvy * 1.75), (float)(-uvz * 1.75)});
        }
    }

    protected static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    protected static double normalizeAngle(double a) {
        while (a >  Math.PI) a -= 2.0 * Math.PI;
        while (a < -Math.PI) a += 2.0 * Math.PI;
        return a;
    }

    // =========================================================================
    // NBT  (Entity base class handles core position/motion; we add physics state)
    // =========================================================================

    @Override
    protected void writeEntityToNBT(NBTTagCompound nbt) {
        nbt.setDouble("rVelX",   velX);
        nbt.setDouble("rVelY",   velY);
        nbt.setDouble("rVelZ",   velZ);
        nbt.setDouble("rAngP",   mAngPitch);
        nbt.setDouble("rAngY",   mAngYaw);
        nbt.setDouble("rThP",    thAngPitch);
        nbt.setDouble("rThY",    thAngYaw);
        nbt.setInteger("rAge",   age);
        nbt.setInteger("rPhase", flightPhase.ordinal());
        nbt.setInteger("rMode",  flightMode.ordinal());
        nbt.setDouble("rBstFuel", boosterFuel);
        nbt.setDouble("rSstFuel", sustainerFuel);
        nbt.setBoolean("rBstAtt", boosterAttached);
        // τ_FC flight control IIR state (Palumbo 2010 Eq.37)
        nbt.setDouble("rCmdP",  cmdAngPitch);
        nbt.setDouble("rCmdY",  cmdAngYaw);
        // τ_A lift lag IIR state (Palumbo 2010 Eq.38)
        nbt.setDouble("rLFlx",  laggedFlx);
        nbt.setDouble("rLFly",  laggedFly);
        nbt.setDouble("rLFlz",  laggedFlz);
        // Angle-domain seeker dish state (Palumbo 2010 Eq.24)
        nbt.setDouble("rDishP", seekerDishPitch);
        nbt.setDouble("rDishY", seekerDishYaw);
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound nbt) {
        velX         = nbt.getDouble("rVelX");
        velY         = nbt.getDouble("rVelY");
        velZ         = nbt.getDouble("rVelZ");
        mAngPitch    = nbt.getDouble("rAngP");
        mAngYaw      = nbt.getDouble("rAngY");
        thAngPitch   = nbt.getDouble("rThP");
        thAngYaw     = nbt.getDouble("rThY");
        age          = nbt.getInteger("rAge");
        boosterFuel  = nbt.getDouble("rBstFuel");
        sustainerFuel = nbt.getDouble("rSstFuel");
        boosterAttached = nbt.getBoolean("rBstAtt");
        int pi = nbt.getInteger("rPhase");
        int mi = nbt.getInteger("rMode");
        flightPhase = (pi >= 0 && pi < FlightPhase.values().length)
                ? FlightPhase.values()[pi] : FlightPhase.LIFT;
        flightMode  = (mi >= 0 && mi < FlightMode.values().length)
                ? FlightMode.values()[mi]  : FlightMode.COAST;
        // τ_FC flight control IIR state
        cmdAngPitch  = nbt.hasKey("rCmdP") ? nbt.getDouble("rCmdP") : mAngPitch;
        cmdAngYaw    = nbt.hasKey("rCmdY") ? nbt.getDouble("rCmdY") : mAngYaw;
        // τ_A lift lag IIR state
        laggedFlx    = nbt.getDouble("rLFlx");
        laggedFly    = nbt.getDouble("rLFly");
        laggedFlz    = nbt.getDouble("rLFlz");
        // Angle-domain seeker dish state (valid flag left false on load — re-acquires)
        seekerDishPitch = nbt.getDouble("rDishP");
        seekerDishYaw   = nbt.getDouble("rDishY");
        seekerDishValid = false; // always re-acquire after reload
    }

    // =========================================================================
    // Rendering distance
    // =========================================================================

    @Override
    @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
    public boolean isInRangeToRenderDist(double distance) {
        return distance < 10000;
    }

}
