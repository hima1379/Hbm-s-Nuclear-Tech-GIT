package com.hbm.entity.missile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import api.hbm.data.DataDeviceType;
import api.hbm.data.DataNet;
import api.hbm.data.DataPacket;
import api.hbm.data.IDataConnector;
import com.hbm.physics.IRCSProvider;
import net.minecraft.util.EnumFacing;
import com.hbm.explosion.ExplosionBombRay;
import com.hbm.explosion.ExplosionLarge;
import com.hbm.items.ModItems;
import com.hbm.lib.ModDamageSource;
import com.hbm.main.MainRegistry;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * RIM-174 Standard ERAM (SM-6) — rebuilt on EntityMissileBaseRealistic.
 *
 * Physics, atmosphere, fuel, chunk-loading, and guidance dispatch are all
 * handled by the two base classes.  This file contains only the SM-6-specific
 * constants, seeker/datalink logic, warhead, and loot.
 */
public class EntityMissileSM6 extends EntityMissileBaseRealistic implements IDataConnector {

    // =========================================================================
    // SM-6 PHYSICAL CONSTANTS
    // =========================================================================

    // Masses (kg)
    // Dry mass = warhead (64 kg) + seeker/avionics (~50 kg) + airframe/fins/motor casings (~136 kg).
    // Previous value of 650 kg was the full launch mass (fuel included), causing ~3× over-estimate
    // of terminal mass and capping aerodynamic G at ~3G instead of ~19G at 10 km altitude.
    private static final double SM6_DRY_MASS            = 250.0;
    private static final double SM6_BOOSTER_FUEL        = 550.0;
    private static final double SM6_SUSTAINER_FUEL      = 372.0;

    // Booster – Mk 72 solid motor
    private static final double SM6_BOOSTER_THRUST_SL   = 244640.0; // N at sea level
    private static final double SM6_BOOSTER_THRUST_VAC  = 257500.0; // N in vacuum
    private static final double SM6_BOOSTER_ISP         = 250.0;    // s

    // Sustainer – Mk 111 dual-thrust solid motor
    private static final double SM6_SUSTAINER_HIGH      = 35000.0;  // N high-thrust
    private static final double SM6_SUSTAINER_LOW       = 12000.0;  // N low-thrust
    private static final double SM6_SUSTAINER_ISP       = 280.0;    // s

    // Aerodynamics
    private static final double SM6_DIAMETER            = 0.34;     // m
    private static final double SM6_REF_AREA =
            Math.PI * (SM6_DIAMETER / 2.0) * (SM6_DIAMETER / 2.0);
    private static final double SM6_CD_SUBSONIC         = 0.25;
    private static final double SM6_CD_SUPERSONIC       = 0.35;
    // CL_alpha per radian (lift-curve slope).  Value of 3.5 is a slender-body
    // approximation for the bare fuselage only.  The SM-6 has four large delta-canard
    // fins plus four tail fins giving significantly higher effective lift.  A value of
    // 10.0 yields:
    //   alt=10 km (rho=0.36, V=900 m/s): maxLiftG ≈ 19G   (useful terminal intercept)
    //   sea level (rho=1.225, V=900 m/s): maxLiftG ≈ 64G → structural 50G cap applies ✓
    private static final double SM6_CL_ALPHA            = 10.0;
    private static final double SM6_K_INDUCED_DRAG      = 0.08;
    private static final double SM6_MAX_AOA             = Math.toRadians(20.0);

    // Seeker and fuse
    private static final double ACTIVE_SEEKER_RANGE     = 22500.0;  // m
    private static final double SEEKER_FOV_COS          = Math.cos(Math.toRadians(30.0));  // ±30° half-angle (real SM-6 ARH)
    // Proximity fuse radius increased 20→35 m to improve kill probability on high-speed near-miss
    // geometries where the swept-path check may skip past the target between ticks.
    private static final double PROXIMITY_FUSE_RANGE    = 35.0;     // m
    private static final int    PROX_FUSE_ARM_TICKS     = 60;

    // Warhead – Mk 125 blast-fragmentation, 64 kg
    private static final double WARHEAD_KG              = 64.0;
    private static final int    WARHEAD_PEN_MAX         = 70;
    private static final int    WARHEAD_PEN_MIN         = 30;
    private static final double BLAST_RADIUS            = 300.0;    // m
    private static final double THERMAL_RADIUS          = 100.0;    // m
    private static final double FRAG_RADIUS             = 200.0;    // m

    // =========================================================================
    // SEEKER STATE MACHINE
    // =========================================================================

    public enum SeekerState { OFF, WARMING_UP, SEARCHING, LOCKED }
    private static final int SEEKER_WARMUP_TICKS = 100;

    private SeekerState seekerState           = SeekerState.OFF;
    private int         seekerWarmupStartTick = 0;

    // =========================================================================
    // TARGET / DATALINK STATE
    // =========================================================================

    Entity  activeTarget;               // live entity reference from seeker / FCS
    boolean hasActiveLock  = false;
    boolean isPitbull      = false;     // true = seeker is autonomous (no datalink needed)

    // Datalink backup coordinates (m, in world space)
    private double targetX, targetY, targetZ;
    private double targetVelX, targetVelY, targetVelZ;
    private double positionUncertainty   = 0.0;
    private int    lastDatalinkUpdateTick = 0;
    private int    datalinkLossCounter   = 0;

    // ── Pass-through proximity fuse tracker ─────────────────────────────────
    // Stores the endpoint distance² from the end of the PREVIOUS tick and the
    // last valid target world-position.  When the primary swept-path check is
    // somehow bypassed (e.g. target AABB stale, entity briefly null), these
    // fields let a 1-tick-delay fallback detonate the warhead at the target site.
    private double fuseLastEndDistSq   = Double.MAX_VALUE;
    private double fuseLastKnownTX, fuseLastKnownTY, fuseLastKnownTZ;

    // =========================================================================
    // IDATACONNECTOR STATE
    // =========================================================================

    private UUID    deviceId = UUID.randomUUID();
    private DataNet dataNet  = null;

    // =========================================================================
    // CONSTRUCTORS
    // =========================================================================

    public EntityMissileSM6(World world) {
        super(world);
    }

    public EntityMissileSM6(World world, float x, float y, float z, int tX, int tZ) {
        super(world, x, y, z, tX, tZ);
    }

    // =========================================================================
    // PHYSICAL CONSTANT OVERRIDES
    // =========================================================================

    @Override protected double getDryMass()              { return SM6_DRY_MASS; }
    @Override protected double getInitialBoosterFuel()   { return SM6_BOOSTER_FUEL; }
    @Override protected double getInitialSustainerFuel() { return SM6_SUSTAINER_FUEL; }
    @Override protected double getReferenceArea()        { return SM6_REF_AREA; }

    @Override protected double getBoosterThrustSL()      { return SM6_BOOSTER_THRUST_SL; }
    @Override protected double getBoosterThrustVac()     { return SM6_BOOSTER_THRUST_VAC; }
    @Override protected double getBoosterIsp()           { return SM6_BOOSTER_ISP; }

    @Override protected double getSustainerThrustHigh()  { return SM6_SUSTAINER_HIGH; }
    @Override protected double getSustainerThrustLow()   { return SM6_SUSTAINER_LOW; }
    @Override protected double getSustainerIsp()         { return SM6_SUSTAINER_ISP; }

    @Override protected double getCDSubsonic()    { return SM6_CD_SUBSONIC; }
    @Override protected double getCDSupersonic()  { return SM6_CD_SUPERSONIC; }
    @Override protected double getCLAlpha()       { return SM6_CL_ALPHA; }
    @Override protected double getKInducedDrag()  { return SM6_K_INDUCED_DRAG; }
    @Override protected double getMaxAoA()        { return SM6_MAX_AOA; }

    @Override
    protected double getMaxG(FlightPhase phase) {
        // SM-6 structural / control-authority ceiling: 50 G.
        //
        // EntityMissileBaseRealistic.calculatePhysicsForces() already applies the
        // full ISA atmosphere model: q = 0.5 * rho(alt) * V² * refArea, so lift and
        // drag forces naturally fall with altitude.  The autopilot rate limit here
        // (maxTurnRate = maxG * g / V * DT) should therefore be the STRUCTURAL cap
        // only — not an additional altitude-dependent ceiling that would doubly
        // restrict turning at high altitude and prevent the missile from following
        // the APG polynomial at all.
        //
        // Phase notes:
        //   LIFT / TURN / BOOST — Mk 72 booster with TVC: full 50 G authority.
        //   CRUISE / COAST      — Mk 104 sustainer, aerodynamic fins only.
        //                         Physics engine already limits achievable G via rho;
        //                         the 50 G ceiling is never reached at cruise altitude.
        //   TERMINAL            — Same physics; 50 G gives the guidance law maximum
        //                         freedom to correct during terminal homing.
        switch (phase) {
            case LIFT:
            case TURN:
            case BOOST:
            case CRUISE:
            case COAST:
            case TERMINAL: return 50.0;
            default:       return 20.0;
        }
    }

    // =========================================================================
    // GUIDANCE TIME CONSTANTS  (Palumbo 2010 Eq.37/38)
    // =========================================================================

    /**
     * SM-6 flight control time constant τ_FC = 0.08 s.
     * Palumbo 2010 Eq.37: G_FC(s) = 1/(τ_FC·s+1).
     * Reduced 0.15→0.08 s to match τ_A (aero time constant), improving autopilot
     * bandwidth (closed-loop ~12.5 rad/s) so the commanded angle reaches the
     * structural limit before aerodynamics saturate — eliminates the
     * command-lag instability seen in high-closure-rate endgame intercepts.
     */
    @Override
    protected double getFlightControlTimeConstant() { return 0.08; }

    /**
     * SM-6 aerodynamic turning rate time constant τ_A = 0.08 s.
     * Palumbo 2010 Eq.38: G_A(s) = (τ_A·s+1)/v_m.
     * Derived from SM-6 fin actuator bandwidth and airframe structural response.
     */
    @Override
    protected double getAeroTimeConstant() { return 0.08; }

    // =========================================================================
    // FLIGHT PHASE TUNING
    // =========================================================================

    /**
     * Extend the TURN phase to tick 220 (vs default 160).
     * SM-6 VLS launches at 80°; by tick 160 the velocity pitch is ~47° — too
     * steep for the APG polynomial to bring the missile down to ≤13 km peak.
     * The extra 60 ticks (3 s) of powered pitch-over reduce gamma0 by ~7°,
     * lowering the initial polynomial slope and limiting altitude overshoot.
     */
    @Override
    protected int getTurnEndTick() { return 220; }

    // =========================================================================
    // GUIDANCE FACTORIES
    // =========================================================================

    /** Vertical launch at 80° toward target azimuth (ticks 0–60). */
    @Override
    protected EntityMissileBaseGuidance createLiftGuidance() {
        return new EntityMissileBaseGuidance.GuidanceLIFT();
    }

    /** Pitch-over from 80° down to target elevation (ticks 60–220).
     *  Extended from 160 → 220 ticks so the velocity pitch at APG entry is
     *  ~7° lower (~40° vs ~47°), reducing the initial flight-path error that
     *  the APG polynomial must correct and limiting altitude overshoot. */
    @Override
    protected EntityMissileBaseGuidance createTurnGuidance() {
        return new EntityMissileBaseGuidance.GuidanceTURN(60, 220);
    }

    /** ICAS-2024 4th-order polynomial trajectory shaping (BOOST + CRUISE). */
    @Override
    protected EntityMissileBaseGuidance createMidcourseGuidance() {
        return new EntityMissileBaseGuidance.GuidanceAPG();
    }

    /**
     * Augmented PN (APN), N=5, τ_f=0.15 s (COAST – includes target accel estimate).
     * Upgraded from GuidancePN(4.0, 0.20):
     *   – N raised 4→5: tighter zero-effort-miss correction, less residual bias
     *     at COAST→TERMINAL handoff, so the terminal seeker starts with a smaller
     *     LOS angular rate to kill.
     *   – GuidancePN→GuidanceAPN: finite-diff target accel estimate suppresses
     *     false-pursuit error against manoeuvring targets (SRBM boost, evasive UAV).
     *   – τ_f reduced 0.20→0.15 s: faster command response; still smooth enough to
     *     avoid structural-limit saturation during the long COAST phase.
     * Palumbo (2010) Eq.36: a_cmd = N·Vc·σ̇ + N/2·a_T.
     */
    @Override
    protected EntityMissileBaseGuidance createCoastGuidance() {
        return new EntityMissileBaseGuidance.GuidanceAPN(5.0, 0.15);
    }

    /**
     * ZEM-APN, N=5, τ_f=0.20 s – TERMINAL 5–20 km.
     * Guidance filter smooths APN command; target accel estimate via finite diff.
     */
    @Override
    protected EntityMissileBaseGuidance createTerminalGuidanceFar() {
        return new EntityMissileBaseGuidance.GuidanceAPN(5.0, 0.20);
    }

    /**
     * ZEM-APN, N=5, τ_f=0.08 s – near terminal 2–5 km.
     *
     * Changed from GuidancePNLosRate(6.0, 0.08) to GuidanceAPN(5.0, 0.08):
     *
     * Root cause of ~430 m miss (RDBG 2026-03, "proximity fuse didn't trigger"):
     *   The target was flying WESTWARD at ~700 m/s while SM-6 approached from the WEST
     *   (head-on geometry).  In this scenario the LOS YAW angle stays near 90° (east)
     *   until the instant the target passes overhead.  GuidancePNLosRate commands:
     *     a_yaw = N·Vc·σ̇_yaw/cos(η) ≈ 0   (σ̇_yaw ≈ 0 in head-on geometry)
     *   → thAngYaw stays at +90° (east) throughout, while the correct command is −90°
     *     (west) because the target's FUTURE intercept position is ~2 km to the west.
     *
     *   GuidanceAPN (ZEM) avoids this by predicting the future intercept:
     *     futTarget = tPos + tVel·t_go          (target moves west)
     *     futMissile = mPos + mVel·t_go − ½g    (missile continues on trajectory)
     *     ZEM = futTarget − futMissile            (points west → a_yaw = westward cmd)
     *   With the missile at near-vertical (γ≈78°), the "pitch-up" axis is approximately
     *   westward.  ZEM_pitch ≈ −2100 m (west) → a_pitch → thAngYaw ≈ −97° (west) ✓
     *
     *   PNLosRate yaw failure verified: at age=1040 thY=+90° vs required −90°;
     *   minimum range ≈ 430 m (too far for 35 m proximity fuse).
     *
     * Transition continuity: all three terminal laws are warmed up every tick by the
     * base-class warm-up block.  Near-APN state (filtAPitch, estAccelX/Y/Z …) is
     * current at activation → smooth handoff from far-APN(τ=0.20) to near-APN(τ=0.08).
     * τ_f 0.20→0.08 s gives faster response at shorter range as required.
     */
    @Override
    protected EntityMissileBaseGuidance createTerminalGuidanceNear() {
        return new EntityMissileBaseGuidance.GuidanceAPN(5.0, 0.08);
    }

    /**
     * ZEM-APN, N=5, τ_f=0.04 s – endgame &lt; 2 km.
     *
     * Changed from GuidancePNLosRate(5.0, 0.04) to GuidanceAPN(5.0, 0.04).
     * Same motivation as createTerminalGuidanceNear() above.
     *
     * At endgame t_go ≈ 0.5–2 s.  The natural ZEM gain schedule K = t_go/N
     * automatically moderates corrections: K ≤ 0.4 s → Δγ = a·K/V is small even
     * when a_pitch is large.  The t_go ≥ 0.28 s floor in computeZemRaw prevents the
     * N/t_go² gain from exploding as range → 0.
     *
     * τ_f = 0.04 s (filter pole 25 Hz, above autopilot BW ~12 Hz) maintained for
     * fastest possible response at point-blank range.
     */
    @Override
    protected EntityMissileBaseGuidance createTerminalGuidanceEndgame() {
        return new EntityMissileBaseGuidance.GuidanceAPN(5.0, 0.04);
    }

    // =========================================================================
    // TARGET INFO  (called by base-class guidance dispatch every tick)
    // =========================================================================

    @Override
    protected double[] getTargetPosition() {
        // Priority 1: live entity lock
        if (activeTarget != null && !activeTarget.isDead) {
            return new double[]{ activeTarget.posX, activeTarget.posY, activeTarget.posZ };
        }
        // Priority 2: datalink extrapolated position
        if (hasActiveLock) {
            int ticksSince = age - lastDatalinkUpdateTick;
            double dt = ticksSince * DT;
            return new double[]{
                    targetX + targetVelX * dt,
                    targetY + targetVelY * dt,
                    targetZ + targetVelZ * dt
            };
        }
        return null;
    }

    @Override
    protected double[] getTargetVelocity() {
        if (activeTarget != null && !activeTarget.isDead) {
            // Convert Minecraft per-tick motion to m/s
            return new double[]{
                    activeTarget.motionX / DT,
                    activeTarget.motionY / DT,
                    activeTarget.motionZ / DT
            };
        }
        return new double[]{ targetVelX, targetVelY, targetVelZ };
    }

    // =========================================================================
    // PRE-GUIDANCE HOOK  (seeker state machine + acquisition)
    // =========================================================================

    @Override
    protected void onPreGuidance() {
        tickSeekerState();
        if (flightPhase != FlightPhase.LIFT && flightPhase != FlightPhase.TURN) {
            acquireTarget();
        }

        // Degrade position certainty each tick we lack fresh datalink and seeker
        if (hasActiveLock && (activeTarget == null || activeTarget.isDead)) {
            int ticksSince = age - lastDatalinkUpdateTick;
            if (ticksSince > 20) {
                datalinkLossCounter++;
                positionUncertainty += 5.0; // m/tick degradation
            }
        }

        // SM6DBG: per-tick target-state diagnostic for root-cause analysis
        if (hasActiveLock) {
            boolean live = (activeTarget != null && !activeTarget.isDead);
            double tX, tY, tZ;
            if (live) {
                tX = activeTarget.posX; tY = activeTarget.posY; tZ = activeTarget.posZ;
            } else {
                int ticksSince = age - lastDatalinkUpdateTick;
                double dt = ticksSince * DT;
                tX = targetX + targetVelX * dt;
                tY = targetY + targetVelY * dt;
                tZ = targetZ + targetVelZ * dt;
            }
            System.out.println(String.format(
                "[SM6DBG age=%d] live=%b seekerState=%s pitbull=%b " +
                "lastDLtick=%d datalinkLoss=%d posUncert=%.0f " +
                "tgt=(%.0f,%.0f,%.0f) storedVel=(%.1f,%.1f,%.1f) " +
                "drPos=(%.0f,%.0f,%.0f)",
                age, live, seekerState, isPitbull,
                lastDatalinkUpdateTick, datalinkLossCounter, positionUncertainty,
                (live ? activeTarget.posX : targetX),
                (live ? activeTarget.posY : targetY),
                (live ? activeTarget.posZ : targetZ),
                targetVelX, targetVelY, targetVelZ,
                tX, tY, tZ));
        }
    }

    private void tickSeekerState() {
        switch (seekerState) {
            case OFF:
                if (flightPhase == FlightPhase.BOOST || flightPhase == FlightPhase.CRUISE
                        || flightPhase == FlightPhase.COAST || flightPhase == FlightPhase.TERMINAL) {
                    seekerState           = SeekerState.WARMING_UP;
                    seekerWarmupStartTick = age;
                }
                break;
            case WARMING_UP:
                if (age - seekerWarmupStartTick >= SEEKER_WARMUP_TICKS) {
                    seekerState = SeekerState.SEARCHING;
                }
                break;
            case SEARCHING:
                if (activeTarget != null && !activeTarget.isDead) {
                    seekerState = SeekerState.LOCKED;
                    isPitbull   = true;
                }
                break;
            case LOCKED:
                if (activeTarget == null || activeTarget.isDead) {
                    activeTarget = null;
                    isPitbull    = false;
                    seekerState  = SeekerState.SEARCHING;
                }
                break;
        }
    }

    private void acquireTarget() {
        // Keep existing live lock
        if (activeTarget != null && !activeTarget.isDead) return;
        // Wait until seeker is ready to scan
        if (seekerState != SeekerState.SEARCHING && seekerState != SeekerState.LOCKED) return;

        double V = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
        if (V < 10.0) return;

        // Gimbal seeker antenna toward estimated target position (dead-reckoned or live).
        // Real ARH seekers (SM-6's AN/DSQ-62) have a gimbaled antenna with ±60° off-boresight
        // that steers toward the estimated target independently of missile body attitude.
        // Using velocity as the FOV axis (original code) caused the seeker to look nearly
        // straight up during midcourse climb (~85° pitch), placing any target below the
        // missile outside the ±30° acceptance cone indefinitely — seeker never locked.
        double fwdX, fwdY, fwdZ;
        double[] estTarget = getTargetPosition();
        if (estTarget != null) {
            double toLookX = estTarget[0] - posX;
            double toLookY = estTarget[1] - posY;
            double toLookZ = estTarget[2] - posZ;
            double toLookLen = Math.sqrt(toLookX*toLookX + toLookY*toLookY + toLookZ*toLookZ);
            if (toLookLen > 1.0) {
                fwdX = toLookX / toLookLen;
                fwdY = toLookY / toLookLen;
                fwdZ = toLookZ / toLookLen;
            } else {
                // Target essentially at missile position — use velocity direction as fallback
                fwdX = velX / V; fwdY = velY / V; fwdZ = velZ / V;
            }
        } else {
            // No target estimate available — use velocity direction
            fwdX = velX / V; fwdY = velY / V; fwdZ = velZ / V;
        }

        List<Entity> candidates = world.getEntitiesWithinAABBExcludingEntity(
                this,
                new AxisAlignedBB(
                        posX - ACTIVE_SEEKER_RANGE, posY - ACTIVE_SEEKER_RANGE, posZ - ACTIVE_SEEKER_RANGE,
                        posX + ACTIVE_SEEKER_RANGE, posY + ACTIVE_SEEKER_RANGE, posZ + ACTIVE_SEEKER_RANGE));

        Entity best     = null;
        double bestDist = ACTIVE_SEEKER_RANGE;

        for (Entity e : candidates) {
            // Fratricide / non-threat filter
            if (e instanceof EntityMissileSM6)                                 continue;
            if (e instanceof EntityPlayer)                                     continue;
            if (e instanceof net.minecraft.entity.item.EntityItem)             continue;
            if (e instanceof net.minecraft.entity.item.EntityXPOrb)            continue;
            if (e instanceof net.minecraft.entity.projectile.EntityArrow)      continue;

            boolean valid = (e instanceof IRCSProvider)
                    || (e instanceof EntityLivingBase)
                    || (e instanceof EntityMissileBaseAdvanced)
                    || (e.posY > 10.0 && !e.onGround);
            if (!valid) continue;

            double dist = this.getDistance(e);
            if (dist >= bestDist) continue;

            // Seeker FOV cone check (±30°)
            double dx = (e.posX - posX) / dist;
            double dy = (e.posY - posY) / dist;
            double dz = (e.posZ - posZ) / dist;
            double dot = dx * fwdX + dy * fwdY + dz * fwdZ;
            if (dot < SEEKER_FOV_COS) continue;

            best     = e;
            bestDist = dist;
        }

        if (best != null) {
            activeTarget   = best;
            hasActiveLock  = true;
            seekerState    = SeekerState.LOCKED;
            isPitbull      = true;
        }
    }

    // =========================================================================
    // BOOSTER SEPARATION  (hook from base class)
    // =========================================================================

    @Override
    protected void onBoosterSeparation() {
        // Sustainer high-thrust mode is set automatically by base class.
        // Override for visual effects if needed.
    }

    // =========================================================================
    // PROXIMITY FUSE  (termination check, called by base class every tick)
    // =========================================================================

    @Override
    protected boolean checkTermination() {
        if (age < PROX_FUSE_ARM_TICKS) return false;

        final double FUSE_R2 = PROXIMITY_FUSE_RANGE * PROXIMITY_FUSE_RANGE;

        // ── Primary + pass-through fallback: actively-tracked target ───────────
        //
        // WHY direct reference instead of world search:
        //   World.getEntitiesWithinAABBExcludingEntity() checks each entity's
        //   *cached* AxisAlignedBB.  Older missile classes (EntityMissileBasic etc.)
        //   assign posX/Y/Z directly without calling setPosition(), so their AABB
        //   stays at the spawn position forever.  The world search below never finds
        //   them and the proximity fuse can never fire.  Using the live Entity
        //   reference bypasses the spatial index entirely.
        //
        // PASS-THROUGH FALLBACK (belt-and-suspenders for Mach-5 scenarios):
        //   Even if the swept-path check is somehow skipped, we record the
        //   endpoint distance at the end of every tick.  On the NEXT tick, if
        //   the previous endpoint distance was within PROXIMITY_FUSE_RANGE and
        //   the missile is now diverging (further away), the warhead detonates at
        //   the last recorded target position — guaranteeing a kill whenever the
        //   missile's path ever brought it within the fuse radius.
        if (activeTarget != null && !activeTarget.isDead) {
            double tx = activeTarget.posX;
            double ty = activeTarget.posY;
            double tz = activeTarget.posZ;

            // ① Pre-physics check: SM6's start-of-tick position vs. target's current
            //    position.  This equals the "range" value shown in RDBG (printed
            //    between lastTickPos assignment and the physics step).  When the
            //    target updates before SM6 ("Case A" entity ordering), target.posX is
            //    already the target's post-tick position while this.lastTickPosX is
            //    SM6's pre-tick position.  If their separation < FUSE_R2 the paths
            //    crossed within this tick.
            {
                double dxPre = this.lastTickPosX - tx;
                double dyPre = this.lastTickPosY - ty;
                double dzPre = this.lastTickPosZ - tz;
                double preDistSq = dxPre*dxPre + dyPre*dyPre + dzPre*dzPre;
                if (preDistSq < FUSE_R2) {
                    this.setPosition(this.lastTickPosX, this.lastTickPosY, this.lastTickPosZ);
                    System.out.println(String.format(
                        "[SM6-FUSE age=%d] pre-tick dist=%.1fm tgt=(%.0f,%.0f,%.0f) det=(%.0f,%.0f,%.0f)",
                        age, Math.sqrt(preDistSq), tx, ty, tz,
                        this.lastTickPosX, this.lastTickPosY, this.lastTickPosZ));
                    return true;
                }
            }

            // ② CPA swept-path: both SM6 AND target movement are factored in.
            //    Problem: the original sweptPathDistSq() used the target's snapshot
            //    position at check-time.  At Mach 4+ combined closing speeds the
            //    target moves >>35 m per tick; Minecraft's entity-update ordering
            //    means the target has already displaced by that amount before
            //    checkTermination() runs, causing sweptPathDistSq to return ~102 m
            //    even when the actual closest approach was 34 m.
            //
            //    Fix: treat both SM6 and target as linearly-moving points over the
            //    tick (parametric t ∈ [0,1]).  The minimum distance between the two
            //    moving points — the CPA — is the true closest approach distance.
            //
            //    r(t) = (SM6_start + t·SM6_vel) − (tgt_start + t·tgt_vel)
            //         = r0 + t·dv
            //    Minimised at  t* = −(r0·dv) / |dv|²  (clamped to [0,1])
            {
                double r0x = this.lastTickPosX - activeTarget.lastTickPosX;
                double r0y = this.lastTickPosY - activeTarget.lastTickPosY;
                double r0z = this.lastTickPosZ - activeTarget.lastTickPosZ;
                double dvx = (this.posX - this.lastTickPosX) - (activeTarget.posX - activeTarget.lastTickPosX);
                double dvy = (this.posY - this.lastTickPosY) - (activeTarget.posY - activeTarget.lastTickPosY);
                double dvz = (this.posZ - this.lastTickPosZ) - (activeTarget.posZ - activeTarget.lastTickPosZ);
                double dv2 = dvx*dvx + dvy*dvy + dvz*dvz;
                double tStar = (dv2 > 1e-6)
                        ? Math.max(0.0, Math.min(1.0, -(r0x*dvx + r0y*dvy + r0z*dvz) / dv2))
                        : 0.0;
                double cx = r0x + tStar * dvx;
                double cy = r0y + tStar * dvy;
                double cz = r0z + tStar * dvz;
                double cpaDistSq = cx*cx + cy*cy + cz*cz;
                // Debug: log all CPA inputs/output when target is within 200 m (pre-physics dist)
                {
                    double dxDbg = this.lastTickPosX - tx;
                    double dyDbg = this.lastTickPosY - ty;
                    double dzDbg = this.lastTickPosZ - tz;
                    if (dxDbg*dxDbg + dyDbg*dyDbg + dzDbg*dzDbg < 40000.0) { // 200 m radius
                        System.out.println(String.format(
                            "[SM6-CPA-DBG age=%d] r0=(%.1f,%.1f,%.1f) dv=(%.1f,%.1f,%.1f) t*=%.3f cpa=%.1fm | tLTP=(%.1f,%.1f,%.1f) tPos=(%.1f,%.1f,%.1f)",
                            age, r0x, r0y, r0z, dvx, dvy, dvz, tStar, Math.sqrt(cpaDistSq),
                            activeTarget.lastTickPosX, activeTarget.lastTickPosY, activeTarget.lastTickPosZ,
                            tx, ty, tz));
                    }
                }
                if (cpaDistSq < FUSE_R2) {
                    // Teleport SM6 to its position at the CPA time for accurate blast placement
                    double detX = this.lastTickPosX + tStar * (this.posX - this.lastTickPosX);
                    double detY = this.lastTickPosY + tStar * (this.posY - this.lastTickPosY);
                    double detZ = this.lastTickPosZ + tStar * (this.posZ - this.lastTickPosZ);
                    this.setPosition(detX, detY, detZ);
                    System.out.println(String.format(
                        "[SM6-FUSE age=%d] CPA-detonate dist=%.1fm tgt=(%.0f,%.0f,%.0f) det=(%.1f,%.1f,%.1f) t*=%.3f",
                        age, Math.sqrt(cpaDistSq), tx, ty, tz, detX, detY, detZ, tStar));
                    return true;
                }
            }

            // ③ Endpoint check: post-physics SM6 position directly within FUSE_R2.
            //    Catches the case where the missile ends the tick inside the kill sphere.
            double dx = this.posX - tx, dy = this.posY - ty, dz = this.posZ - tz;
            double curDistSq = dx*dx + dy*dy + dz*dz;

            if (curDistSq < FUSE_R2) {
                System.out.println(String.format(
                    "[SM6-FUSE age=%d] endpoint dist=%.1fm tgt=(%.0f,%.0f,%.0f)",
                    age, Math.sqrt(curDistSq), tx, ty, tz));
                return true;
            }

            // ④ Pass-through fallback: fires if LAST TICK's endpoint was within
            //    range and missile is now moving away (closest approach was missed).
            if (fuseLastEndDistSq < FUSE_R2 && curDistSq > fuseLastEndDistSq) {
                // Pass-through: missile was within fuse range last tick but is now diverging
                // (closest approach has passed).  Detonate at a random point within
                // PROXIMITY_FUSE_RANGE of the CURRENT target position — dynamic because
                // the target has moved since fuseLastKnown* was recorded last tick.
                double[] det = randomPointInSphere(tx, ty, tz, PROXIMITY_FUSE_RANGE);
                System.out.println(String.format(
                    "[SM6-FUSE-FALLBACK age=%d] pass-through! lastDist=%.1fm->%.1fm detonate near tgt=(%.0f,%.0f,%.0f) at=(%.1f,%.1f,%.1f)",
                    age, Math.sqrt(fuseLastEndDistSq), Math.sqrt(curDistSq),
                    tx, ty, tz, det[0], det[1], det[2]));
                this.setPosition(det[0], det[1], det[2]);
                return true;
            }

            // Update tracker for next tick
            fuseLastEndDistSq = curDistSq;
            fuseLastKnownTX   = tx;
            fuseLastKnownTY   = ty;
            fuseLastKnownTZ   = tz;

        } else if (fuseLastEndDistSq < FUSE_R2) {
            // ⑤ Target entity vanished (null / dead) while missile was within kill
            //    radius.  Detonate at the last known target position.
            System.out.println(String.format(
                "[SM6-FUSE-FALLBACK age=%d] target lost within %.1fm — snap detonate (%.0f,%.0f,%.0f)",
                age, Math.sqrt(fuseLastEndDistSq),
                fuseLastKnownTX, fuseLastKnownTY, fuseLastKnownTZ));
            this.setPosition(fuseLastKnownTX, fuseLastKnownTY, fuseLastKnownTZ);
            return true;
        }

        // ── Secondary: general area-defence sweep (aircraft, drones, etc.) ─────
        // Grow the search AABB by the full swept distance so that fast-moving
        // threats are not missed when the missile crosses their sphere in one tick.
        double motionDist = Math.sqrt(this.motionX*this.motionX
                + this.motionY*this.motionY + this.motionZ*this.motionZ);
        double fuseSearch = PROXIMITY_FUSE_RANGE + motionDist;

        List<Entity> nearby = world.getEntitiesWithinAABBExcludingEntity(
                this,
                this.getEntityBoundingBox().grow(fuseSearch, fuseSearch, fuseSearch));

        for (Entity e : nearby) {
            if (e == activeTarget)             continue; // already checked above
            if (e instanceof EntityMissileSM6) continue;
            if (e instanceof EntityPlayer)     continue;

            boolean trigger = (e instanceof EntityLivingBase)
                    || (e instanceof IRCSProvider)
                    || (e.posY > 100.0 && !e.onGround && !(e instanceof EntityMissileSM6));
            if (!trigger) continue;

            if (sweptPathDistSq(e.posX, e.posY, e.posZ) < FUSE_R2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Squared minimum distance from the missile's swept path
     * (lastTickPos → posXYZ) to a STATIC world-space point (tx, ty, tz).
     * Used for the secondary area-defense sweep against non-tracked entities
     * (which typically have negligible per-tick displacement).
     *
     * For the primary activeTarget check, use the CPA (Closest Point of
     * Approach) block in checkTermination() instead — it accounts for both
     * the missile's and the target's movement over the tick.
     */
    private double sweptPathDistSq(double tx, double ty, double tz) {
        // a = point relative to segment start
        double ax = tx - this.lastTickPosX;
        double ay = ty - this.lastTickPosY;
        double az = tz - this.lastTickPosZ;
        // b = segment vector (lastTickPos → posXYZ)
        double bx = this.posX - this.lastTickPosX;
        double by = this.posY - this.lastTickPosY;
        double bz = this.posZ - this.lastTickPosZ;
        double bLen2 = bx*bx + by*by + bz*bz;
        double t = (bLen2 > 1e-6) ? Math.max(0.0, Math.min(1.0,
                (ax*bx + ay*by + az*bz) / bLen2)) : 0.0;
        double cx = ax - t*bx, cy = ay - t*by, cz = az - t*bz;
        return cx*cx + cy*cy + cz*cz;
    }

    /**
     * Returns a uniformly-distributed random point inside a sphere of the given
     * radius centred at (cx, cy, cz).
     *
     * Uses the cbrt(U) trick for uniform radial distribution and spherical
     * coordinates for direction — guarantees every point in the sphere has
     * equal probability, avoiding the clustering-at-centre artefact of a
     * naive (rand * radius) approach.
     *
     * Called by the proximity-fuse pass-through fallback to place the warhead
     * detonation at a random position within PROXIMITY_FUSE_RANGE of the
     * CURRENT (live) target position, ensuring lethality regardless of which
     * direction the missile happened to pass through the fuse sphere.
     */
    private double[] randomPointInSphere(double cx, double cy, double cz, double radius) {
        double r        = radius * Math.cbrt(rand.nextDouble());      // uniform radial CDF
        double phi      = rand.nextDouble() * 2.0 * Math.PI;          // azimuth [0, 2π)
        double cosTheta = 1.0 - 2.0 * rand.nextDouble();              // elevation [-1, 1]
        double sinTheta = Math.sqrt(Math.max(0.0, 1.0 - cosTheta * cosTheta));
        return new double[]{
            cx + r * sinTheta * Math.cos(phi),
            cy + r * cosTheta,
            cz + r * sinTheta * Math.sin(phi)
        };
    }

    // =========================================================================
    // WARHEAD DETONATION
    // =========================================================================

    @Override
    public void onImpact() {
        if (world.isRemote || this.isDead) return;

        applyArmorPenetrationDamage();

        // Block destruction via ray-traced explosion
        ExplosionBombRay explosion = new ExplosionBombRay(
                world, (int) posX, (int) posY, (int) posZ,
                WARHEAD_KG, WARHEAD_PEN_MAX, WARHEAD_PEN_MIN, true);
        while (!explosion.isRayTracingComplete) explosion.collectRays(1000);
        while (!explosion.isComplete())         explosion.processBlocks(500);

        // Shrapnel shower
        ExplosionLarge.spawnShrapnelShower(
                world, posX, posY, posZ, motionX, motionY, motionZ, 30, 0.2f);

        this.setDead();
    }

    /**
     * Blast wave + thermal radiation + fragmentation entity damage.
     * Penetration 70 (centre) → 30 (edge), linear interpolation.
     */
    private void applyArmorPenetrationDamage() {
        List<Entity> affected = world.getEntitiesWithinAABBExcludingEntity(
                this,
                this.getEntityBoundingBox().grow(BLAST_RADIUS, BLAST_RADIUS, BLAST_RADIUS));

        for (Entity e : affected) {
            double dist = this.getDistance(e);
            float  dmg  = 0.0f;

            // Blast wave
            if (dist < BLAST_RADIUS) {
                double r = 1.0 - dist / BLAST_RADIUS;
                int pen = (int)(WARHEAD_PEN_MIN + (WARHEAD_PEN_MAX - WARHEAD_PEN_MIN) * r);
                dmg += (float)(200.0 * Math.pow(pen / 70.0, 2.0));
            }
            // Thermal radiation
            if (dist < THERMAL_RADIUS) {
                double r = 1.0 - dist / THERMAL_RADIUS;
                int pen = (int)(WARHEAD_PEN_MIN + (WARHEAD_PEN_MAX - WARHEAD_PEN_MIN) * r);
                dmg += (float)(150.0 * Math.pow(pen / 70.0, 2.5));
            }
            // Fragmentation (line-of-sight only)
            if (dist < FRAG_RADIUS && hasLineOfSight(e)) {
                double r = 1.0 - dist / FRAG_RADIUS;
                int pen = (int)(WARHEAD_PEN_MIN + (WARHEAD_PEN_MAX - WARHEAD_PEN_MIN) * r * 0.8);
                dmg += (float)(250.0 * Math.pow(pen / 70.0, 2.0));
            }

            if (dmg > 0.1f) {
                e.attackEntityFrom(ModDamageSource.blast, dmg);
                // Force kill on extreme overpressure/fragmentation damage
                if (e instanceof EntityLivingBase) {
                    EntityLivingBase living = (EntityLivingBase) e;
                    if (living.getHealth() > 0 && dmg >= 300.0f) {
                        living.setHealth(0.0f);
                        e.setDead();
                    }
                }
            }
        }

        // Direct damage to actively-tracked target using live entity reference,
        // bypassing the AABB spatial index.
        //
        // WHY this is necessary:
        //   Many custom entity types (bombers, drones built on EntityMissileBasic,
        //   etc.) update posX/Y/Z directly without calling setPosition(), so their
        //   AxisAlignedBB stays frozen at the spawn position forever.
        //   getEntitiesWithinAABBExcludingEntity() only searches by cached AABB —
        //   it never finds those entities no matter how close the warhead detonates.
        //   This explicit check guarantees damage reaches the tracked target even
        //   when the AABB index is broken.
        if (activeTarget != null && !activeTarget.isDead && !affected.contains(activeTarget)) {
            double dist = this.getDistance(activeTarget);
            float  dmg  = 0.0f;

            if (dist < BLAST_RADIUS) {
                double r = 1.0 - dist / BLAST_RADIUS;
                int pen = (int)(WARHEAD_PEN_MIN + (WARHEAD_PEN_MAX - WARHEAD_PEN_MIN) * r);
                dmg += (float)(200.0 * Math.pow(pen / 70.0, 2.0));
            }
            if (dist < THERMAL_RADIUS) {
                double r = 1.0 - dist / THERMAL_RADIUS;
                int pen = (int)(WARHEAD_PEN_MIN + (WARHEAD_PEN_MAX - WARHEAD_PEN_MIN) * r);
                dmg += (float)(150.0 * Math.pow(pen / 70.0, 2.5));
            }
            if (dist < FRAG_RADIUS && hasLineOfSight(activeTarget)) {
                double r = 1.0 - dist / FRAG_RADIUS;
                int pen = (int)(WARHEAD_PEN_MIN + (WARHEAD_PEN_MAX - WARHEAD_PEN_MIN) * r * 0.8);
                dmg += (float)(250.0 * Math.pow(pen / 70.0, 2.0));
            }

            if (dmg > 0.1f) {
                System.out.println(String.format(
                    "[SM6-DMG age=%d] direct hit on activeTarget (bypassed AABB) dist=%.1fm dmg=%.0f",
                    age, dist, dmg));
                activeTarget.attackEntityFrom(ModDamageSource.blast, dmg);
                if (activeTarget instanceof EntityLivingBase) {
                    EntityLivingBase living = (EntityLivingBase) activeTarget;
                    if (living.getHealth() > 0 && dmg >= 300.0f) {
                        living.setHealth(0.0f);
                        activeTarget.setDead();
                    }
                }
            }
        }
    }

    private boolean hasLineOfSight(Entity target) {
        net.minecraft.util.math.Vec3d from =
                new net.minecraft.util.math.Vec3d(posX, posY, posZ);
        net.minecraft.util.math.Vec3d to =
                new net.minecraft.util.math.Vec3d(
                        target.posX, target.posY + target.height * 0.5, target.posZ);
        return world.rayTraceBlocks(from, to, false, true, false) == null;
    }

    // =========================================================================
    // PARTICLE EFFECTS  (override base class default)
    // =========================================================================

    @Override
    protected void spawnExhaustParticles() {
        if (boosterAttached && boosterFuel > 0) {
            for (int i = 0; i < 3; i++) {
                MainRegistry.proxy.spawnParticle(
                        posX, posY - 3.0, posZ, "exHydrogen",
                        new float[]{ (float)(-motionX * 2f), (float)(-motionY * 2f), (float)(-motionZ * 2f) });
            }
        } else if (!boosterAttached && sustainerFuel > 0) {
            MainRegistry.proxy.spawnParticle(
                    posX, posY - 1.5, posZ, "exKerosene",
                    new float[]{ (float)(-motionX), (float)(-motionY), (float)(-motionZ) });
        }
    }

    // =========================================================================
    // FCS CONTROL INTERFACE
    // =========================================================================

    /**
     * Pre-launch orientation setup called by TileEntityLaunchPad immediately after
     * setting rotationYaw toward the target.  Primes the physics body and thrust-
     * vector angles to an 80° nose-up attitude so the booster drives the missile
     * skyward on the first tick.  rotationYaw is preserved.
     */
    public void initializeVerticalLaunch() {
        double launchPitch = Math.toRadians(80.0);          // near-vertical launch (realistic SM-6)
        double launchYaw   = Math.toRadians(this.rotationYaw); // preserve pad-set yaw
        this.mAngPitch  = launchPitch;
        this.mAngYaw    = launchYaw;
        this.thAngPitch = launchPitch;
        this.thAngYaw   = launchYaw;
        // Sync Minecraft render angles (matches updateAnglesFromVelocity formula)
        this.rotationPitch     = -(float)(80.0) + 90.0f;   // = 10° (nose-up display)
        this.prevRotationPitch = this.rotationPitch;
    }

    /**
     * Designate a target from the FCS console (pre-launch or post-launch).
     * Also stores backup datalink coordinates in case the entity becomes unavailable.
     */
    public void setTargetEntity(Entity target) {
        this.activeTarget = target;
        if (target != null) {
            this.hasActiveLock        = true;
            this.targetX              = target.posX;
            this.targetY              = target.posY;
            this.targetZ              = target.posZ;
            // Store initial velocity so datalink fallback can extrapolate position.
            // motionX is blocks/tick for standard MC entities; /DT converts to m/s.
            this.targetVelX           = target.motionX / DT;
            this.targetVelY           = target.motionY / DT;
            this.targetVelZ           = target.motionZ / DT;
            // Seed the dead-reckoning timestamp from the current tick so that if
            // the entity later goes null, extrapolation starts from NOW and not
            // from tick-0 (which would extrapolate for the entire flight duration).
            this.lastDatalinkUpdateTick = this.age;
        }
    }

    /**
     * Pre-launch target coordinate injection from the launch pad (datalink fallback).
     * These coordinates are used when no entity lock is available.
     */
    public void setTargetCoordinates(double x, double y, double z) {
        this.targetX      = x;
        this.targetY      = y;
        this.targetZ      = z;
        this.hasActiveLock = true;
        this.lastDatalinkUpdateTick = 0;
    }

    /**
     * Seed the dead-reckoning velocity used when no entity lock is available.
     * Called by the launch pad when the target entity is not loaded but the FCS
     * console forwarded the target's last-known velocity in the command packet.
     * Without this, dead-reckoning assumes a stationary target (vel = 0) which
     * causes the APG polynomial to predict the wrong intercept point.
     */
    public void setTargetVelocity(double vx, double vy, double vz) {
        this.targetVelX = vx;
        this.targetVelY = vy;
        this.targetVelZ = vz;
    }

    /**
     * Set guidance mode: false = ARH (active radar homing), true = SARH (semi-active).
     * In ARH mode the missile uses its own seeker; in SARH it stays datalink-dependent.
     */
    public void setGuidanceMode(boolean sarhMode) {
        // SARH = datalink-only mode (pitbull flag stays false until seeker locks)
        if (sarhMode) {
            isPitbull = false;
        }
    }

    /**
     * Receive a midcourse guidance update from SPY-1 radar (via DataNet).
     *
     * Expected NBT keys in packet.getData():
     *   targetX / targetY / targetZ   — target world position (m)
     *   targetVelX / targetVelY / targetVelZ — target velocity (m/s)  [optional]
     */
    public void receiveMidcourseGuidance(DataPacket packet) {
        // If autonomous seeker is locked on a live entity, ignore datalink
        if (isPitbull && activeTarget != null && !activeTarget.isDead) return;
        if (packet == null || packet.getData() == null) return;

        NBTTagCompound d = packet.getData();
        if (d.hasKey("targetX")) {
            targetX = d.getDouble("targetX");
            targetY = d.getDouble("targetY");
            targetZ = d.getDouble("targetZ");
        }
        if (d.hasKey("targetVelX")) {
            targetVelX = d.getDouble("targetVelX");
            targetVelY = d.getDouble("targetVelY");
            targetVelZ = d.getDouble("targetVelZ");
        }
        hasActiveLock          = true;
        lastDatalinkUpdateTick = age;
        datalinkLossCounter    = 0;
        positionUncertainty    = 0.0;
    }

    // =========================================================================
    // DEBRIS AND LOOT
    // =========================================================================

    @Override
    public List<ItemStack> getDebris() {
        List<ItemStack> list = new ArrayList<ItemStack>();
        list.add(new ItemStack(ModItems.plate_titanium, 6));
        list.add(new ItemStack(ModItems.circuit, 3));
        list.add(new ItemStack(ModItems.ingot_steel, 4));
        return list;
    }

    @Override
    public ItemStack getDebrisRareDrop() {
        return new ItemStack(ModItems.thruster_small);
    }

    /** Current speed in m/s (used by HUD / FCS status). */
    public double getSpeed() {
        return Math.sqrt(velX * velX + velY * velY + velZ * velZ);
    }

    // =========================================================================
    // IDATACONNECTOR IMPLEMENTATION
    // (Allows SPY-1 and FCS console to identify and command the missile)
    // =========================================================================

    @Override public UUID getDeviceId()   { return deviceId; }
    @Override public String getDeviceName() { return "SM-6 Missile"; }
    @Override public boolean isActive()   { return !this.isDead; }
    @Override public DataNet getDataNet() { return dataNet; }
    @Override public void setDataNet(DataNet net) { this.dataNet = net; }
    @Override public boolean canConnect(EnumFacing dir) { return false; }
    @Override public DataDeviceType getDeviceType() { return DataDeviceType.GENERIC; }

    @Override
    public void receiveData(DataPacket packet) {
        if (packet == null) return;
        if (packet.getType() == DataPacket.DataPacketType.MIDCOURSE_GUIDANCE) {
            receiveMidcourseGuidance(packet);
        }
    }

    // =========================================================================
    // NBT  (base handles physics state; extend with seeker + datalink data)
    // =========================================================================

    @Override
    protected void writeEntityToNBT(NBTTagCompound nbt) {
        super.writeEntityToNBT(nbt);
        nbt.setString("sm6DeviceId", deviceId.toString());
        nbt.setDouble("sm6TargetX",  targetX);
        nbt.setDouble("sm6TargetY",  targetY);
        nbt.setDouble("sm6TargetZ",  targetZ);
        nbt.setDouble("sm6TVelX",    targetVelX);
        nbt.setDouble("sm6TVelY",    targetVelY);
        nbt.setDouble("sm6TVelZ",    targetVelZ);
        nbt.setInteger("sm6SeekerState",          seekerState.ordinal());
        nbt.setInteger("sm6SeekerWarmupTick",     seekerWarmupStartTick);
        nbt.setBoolean("sm6Pitbull",              isPitbull);
        nbt.setBoolean("sm6HasLock",              hasActiveLock);
        nbt.setDouble("sm6PosUncertainty",        positionUncertainty);
        nbt.setInteger("sm6LastDatalinkTick",      lastDatalinkUpdateTick);
        nbt.setInteger("sm6DatalinkLoss",          datalinkLossCounter);
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);
        if (nbt.hasKey("sm6DeviceId")) {
            try { deviceId = UUID.fromString(nbt.getString("sm6DeviceId")); }
            catch (IllegalArgumentException ignored) {}
        }
        targetX    = nbt.getDouble("sm6TargetX");
        targetY    = nbt.getDouble("sm6TargetY");
        targetZ    = nbt.getDouble("sm6TargetZ");
        targetVelX = nbt.getDouble("sm6TVelX");
        targetVelY = nbt.getDouble("sm6TVelY");
        targetVelZ = nbt.getDouble("sm6TVelZ");
        if (nbt.hasKey("sm6SeekerState")) {
            int si = nbt.getInteger("sm6SeekerState");
            seekerState = (si >= 0 && si < SeekerState.values().length)
                    ? SeekerState.values()[si] : SeekerState.OFF;
            seekerWarmupStartTick  = nbt.getInteger("sm6SeekerWarmupTick");
            isPitbull              = nbt.getBoolean("sm6Pitbull");
            hasActiveLock          = nbt.getBoolean("sm6HasLock");
            positionUncertainty    = nbt.getDouble("sm6PosUncertainty");
            lastDatalinkUpdateTick = nbt.getInteger("sm6LastDatalinkTick");
            datalinkLossCounter    = nbt.getInteger("sm6DatalinkLoss");
        }
    }
}
