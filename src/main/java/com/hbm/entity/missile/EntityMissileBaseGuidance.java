package com.hbm.entity.missile;

/**
 * Guidance algorithm library for realistic AA/SAM missiles.
 *
 * Implements guidance laws according to:
 *   [1] Palumbo, Blauwkamp, Lloyd — "Basic Principles of Homing Guidance"
 *       Johns Hopkins APL Technical Digest, Vol.29 No.1, 2010.
 *   [2] Berglund — "Guidance and Control Technology"
 *       RTO Lecture Series RTO-EN-018, 2001.
 *
 * Each inner class implements one guidance law and returns a
 * direction vector {dx, dy, dz} via computeDirection().
 * The caller (EntityMissileBaseRealistic) converts that direction into
 * angular commands through the autopilot (which applies τ_FC and G-limit).
 *
 * Guidance laws implemented:
 *   GuidanceLIFT     – 80° pitch vertical launch (VLS-specific, practical)
 *   GuidanceTURN     – LOS pursuit pitch-over with lead angle (True PN breaks at large t_go)
 *   GuidanceLoftAPG  – ICAS-2024 4th-order polynomial altitude shaping (25 km peak)
 *   GuidanceAPG      – True PN midcourse, N=3, τ_f=0.30s (Palumbo/Berglund)
 *   GuidancePN       – True PN terminal, N configurable (Palumbo Eq.17-20, 25, 36)
 *   GuidanceAPN      – Augmented PN with target accel, N configurable (Berglund + Palumbo)
 *   GuidanceQPN      – Geometric quadratic intercept (lead angle)
 *   GuidancePIP      – Predicted Impact Point iterative simulation
 *   GuidanceLOS      – Line-of-sight proportional pursuit
 *   GuidancePNLosRate– True PN via instantaneous LOS angular rate (propnavpt.m)
 *   GuidanceDiffGeo  – Differential Geometry + target accel feedforward (diffgeo.m)
 *
 * ZEM (Zero-Effort-Miss) core:
 *   Palumbo Eq.17-18: V_c = -dR/dt = closing velocity (used for t_go)
 *   Palumbo Eq.19-20: a_Mc = N * V_c * phi_dot_omega  (True PN)
 *   Palumbo Eq.25,36: G_F(s) = 1/(τ_f·s+1)            (guidance filter)
 *   Berglund APN:     n_M = α*(v_c*phi_dot + ½*n_T)   (augmented)
 *
 * NOTE: Removed in this version (not from PDFs):
 *   – T_LOOKAHEAD arbitrary cap in zemAccelToDirection  → uses K = t_go/N
 *   – MAX_LAT_ACCEL 30G pre-guidance clamp              → G-limit in autopilot only
 *   – GuidanceTURN 1.5× oversteer multiplier            → LOS pursuit + lead angle
 *   – GuidanceAPG loft bias (4% range + 4000m cap)      → replaced by GuidanceLoftAPG
 */
public abstract class EntityMissileBaseGuidance {

    protected static final double GRAVITY = 9.80665;
    protected static final double DT      = 0.05;   // seconds per tick (20 Hz)

    // -------------------------------------------------------------------------
    // Abstract interface
    // -------------------------------------------------------------------------

    /**
     * Compute a desired direction vector for this tick.
     *
     * @param mPosX/Y/Z  missile position  (metres, 1 block = 1 m)
     * @param mVelX/Y/Z  missile velocity  (m/s)
     * @param tPosX/Y/Z  target  position  (metres)
     * @param tVelX/Y/Z  target  velocity  (m/s)
     * @param age        missile age in ticks
     * @return double[3] = {dx, dy, dz}, need not be normalised
     */
    public abstract double[] computeDirection(
            double mPosX, double mPosY, double mPosZ,
            double mVelX, double mVelY, double mVelZ,
            double tPosX, double tPosY, double tPosZ,
            double tVelX, double tVelY, double tVelZ,
            int age);

    /** Override for stateful algorithms that need to reset on phase transition. */
    public void reset() {}

    /**
     * Reset only guidance-command filter state (e.g. filtAPitch / filtAYaw).
     * Velocity-estimator state (prevTPosX/Y/Z, estTVelX/Y/Z, etc.) is NOT
     * cleared so that a pre-TERMINAL warm-up (run during CRUISE/COAST) carries
     * its converged estimate into the first TERMINAL tick.
     *
     * Base class falls back to a full reset() for safety.  GuidanceAPN
     * overrides this to preserve the position-derived velocity estimator.
     */
    public void resetGuidanceFiltersOnly() { reset(); }

    /**
     * When true this instance is being called as a background warm-up
     * (pre-TERMINAL phase) and should suppress verbose diagnostic output.
     * Set by EntityMissileBaseRealistic around warm-up calls.
     */
    boolean warmupMode = false;

    // =========================================================================
    // INNER GUIDANCE CLASSES
    // =========================================================================

    // -------------------------------------------------------------------------
    // GuidanceLIFT – 80° pitch vertical launch
    //
    // VLS-specific practical phase: orients the missile skyward with azimuth
    // toward the predicted target position.  No PDF derivation; this models
    // the physical VLS launch rail constraint (missile must exit vertically
    // before the aerodynamics can steer it).
    // -------------------------------------------------------------------------
    public static class GuidanceLIFT extends EntityMissileBaseGuidance {
        private static final double LIFT_PITCH = Math.toRadians(80.0);

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {
            double dx = tPosX - mPosX;
            double dz = tPosZ - mPosZ;
            double rangeFull = Math.sqrt(dx*dx + (tPosY-mPosY)*(tPosY-mPosY) + dz*dz);
            // Lead angle: predict where target will be when missile arrives.
            // Use 750 m/s as estimated average boost/cruise speed for azimuth-only lead.
            double estTOF  = (rangeFull > 0.0) ? rangeFull / 750.0 : 0.0;
            double predTX  = tPosX + tVelX * estTOF;
            double predTZ  = tPosZ + tVelZ * estTOF;
            double chi     = Math.atan2(predTX - mPosX, predTZ - mPosZ);
            double cosP    = Math.cos(LIFT_PITCH);
            double sinP    = Math.sin(LIFT_PITCH);
            return new double[]{ Math.sin(chi) * cosP, sinP, Math.cos(chi) * cosP };
        }
    }

    // -------------------------------------------------------------------------
    // GuidanceTURN – LOS pursuit pitch-over with lead angle
    //
    // True PN (ZEM-based) is NOT appropriate during the TURN phase.
    // Root cause of the "missile flies in wrong direction" bug:
    //
    //   At TURN entry (age ≈ 60), the missile is at 80° pitch (near-vertical).
    //   Horizontal closing speed Vc ≈ 72 m/s → t_go = 50 km / 72 m/s ≈ 690 s.
    //   Gravity correction in ZEM: futMY = posY + velY·t_go − ½·g·t_go²
    //                                     = 500 + 431·690 − ½·9.8·690²
    //                                     = −2,034,820 m  (underground!)
    //   zemY = targetY − futMY = 100 − (−2,034,820) = +2,034,920 m  (huge positive)
    //   a_pitch = N·zemY_proj / t_go² = +2.23 m/s²  (positive = nose UP)
    //   γ_cmd  = 80° + 2.23·(t_go/N)/V = 80° + 67° = 147°  ← PAST VERTICAL
    //
    //   Result: missile is commanded past 90°, flies backward.
    //
    // Fix: LOS pursuit with lead angle.
    //   – During pitch-over the missile has a large heading error; the autopilot
    //     G-limit and τ_FC naturally rate-limit the turn without needing PN.
    //   – Lead angle (predicting target position at dist/V seconds) handles
    //     moving targets correctly.
    //   – The condition-based TURN→BOOST transition (±15° body-LOS alignment in
    //     updateFlightPhase) exits cleanly and hands off to True PN (GuidanceAPG)
    //     once the missile is on a near-collision-course geometry where t_go is
    //     small enough for ZEM to be well-behaved.
    //
    // The startTick/endTick parameters are retained for interface compatibility.
    // -------------------------------------------------------------------------
    public static class GuidanceTURN extends EntityMissileBaseGuidance {

        private final int startTick;
        private final int endTick;

        public GuidanceTURN(int startTick, int endTick) {
            this.startTick = startTick;
            this.endTick   = endTick;
        }

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {
            // LOS pursuit + lead angle.
            // True PN via ZEM breaks during TURN: t_go ~690 s causes the gravity
            // term (−½·g·t_go²) to dominate, putting the predicted missile position
            // ~2 Mm underground and generating a +67° spurious pitch-up command.
            double V    = Math.sqrt(mVelX*mVelX + mVelY*mVelY + mVelZ*mVelZ);
            double dx   = tPosX - mPosX;
            double dy   = tPosY - mPosY;
            double dz   = tPosZ - mPosZ;
            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
            // Predict target position at TOF so the missile leads moving targets.
            double estTOF = (V > 1.0 && dist > 1.0) ? dist / V : 1.0;
            return new double[]{
                tPosX + tVelX * estTOF - mPosX,
                tPosY + tVelY * estTOF - mPosY,
                tPosZ + tVelZ * estTOF - mPosZ
            };
        }
    }

    // -------------------------------------------------------------------------
    // GuidanceAPG – True PN midcourse guidance (Palumbo 2010, Berglund 2001)
    //
    // Replaces the previous empirical loft-bias approach (loftFactor × 4% range
    // + 4000 m cap) with formally-derived True PN per Palumbo Eq.19-20.
    //
    // Berglund (2001): "PN is appropriate for all phases including midcourse."
    // The PN law naturally shapes the trajectory toward the collision course
    // without requiring ad-hoc altitude bias or polynomial fitting.
    //
    // N=3: matches Berglund's recommendation for midcourse (lower gain than
    // terminal to reduce guidance noise sensitivity during long flight).
    // τ_f=0.30s: guidance filter (Palumbo Eq.25/36) smooths datalink quantisation
    // noise during midcourse phase (longer time constant appropriate for slow
    // midcourse geometry changes; terminal uses 0.10–0.20s for faster response).
    // -------------------------------------------------------------------------
    public static class GuidanceAPG extends EntityMissileBaseGuidance {
        // Navigation gain: N=3 per Berglund midcourse recommendation
        private static final double N_MID  = 3.0;
        // Guidance filter: τ_f=0.30s (Palumbo Eq.25/36), longer than terminal
        private static final double TAU_F  = 0.30;

        private double filtAPitch = 0.0;
        private double filtAYaw   = 0.0;

        @Override
        public void reset() {
            filtAPitch = 0.0;
            filtAYaw   = 0.0;
        }

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {
            // Closing velocity check: Vc = -d(R)/dt = -(r_rel · v_rel) / R
            // ZEM is only valid when the missile is closing on the target (Vc > 0).
            // When diverging (Vc ≤ 0), the quadratic t_go computation in computeZemRaw
            // becomes ill-conditioned and produces unstable/flipped commands (as seen
            // in log: thP flipped from -83.6° to +26.8° in one tick at TERMINAL entry).
            // Fallback: point directly at target (LOS guidance) until closing again.
            double dRx = tPosX - mPosX;
            double dRy = tPosY - mPosY;
            double dRz = tPosZ - mPosZ;
            double R   = Math.sqrt(dRx*dRx + dRy*dRy + dRz*dRz);
            if (R > 0.0) {
                double dvx = mVelX - tVelX;
                double dvy = mVelY - tVelY;
                double dvz = mVelZ - tVelZ;
                double Vc  = -(dRx*dvx + dRy*dvy + dRz*dvz) / R;
                if (Vc <= 0.0) {
                    // Diverging: reset filter and point at target
                    filtAPitch = 0.0;
                    filtAYaw   = 0.0;
                    return new double[]{ dRx, dRy, dRz };
                }
            }
            // True PN ZEM core (Palumbo Eq.17-20)
            double[] raw = computeZemRaw(
                    mPosX, mPosY, mPosZ, mVelX, mVelY, mVelZ,
                    tPosX, tPosY, tPosZ, tVelX, tVelY, tVelZ,
                    N_MID, null, 40.0);
            if (raw == null) {
                return new double[]{ tPosX - mPosX, tPosY - mPosY, tPosZ - mPosZ };
            }
            // Palumbo Eq.25, 36: G_F(s) = 1/(τ_f·s+1)  →  α = exp(-DT/τ_f)
            double alpha = Math.exp(-DT / TAU_F);
            filtAPitch = (1.0 - alpha) * raw[0] + alpha * filtAPitch;
            filtAYaw   = (1.0 - alpha) * raw[1] + alpha * filtAYaw;
            return zemAccelToDirection(filtAPitch, filtAYaw,
                    raw[2], raw[3], raw[4], raw[5], raw[6], N_MID);
        }
    }

    // -------------------------------------------------------------------------
    // GuidanceLoftAPG – ICAS-2024 4th-order polynomial altitude shaping + APG
    //
    // Implements the trajectory-shaping midcourse law from:
    //   ICAS-2024 paper: "Long-Range Air-to-Air Missile Trajectory Optimization"
    //   Trajectory 3 (25 km peak): terminal speed ~918 m/s, most robust to PIP changes
    //
    // Altitude profile (4th-order polynomial, normalized flight progress s ∈ [0,1]):
    //   h(s) = h_launch + c·s² + d·s³ + e·s⁴
    //   Constraints: h(0)=h_launch, h'(0)=0, h(0.5)=h_peak, h(1)=h_target, h'(1)=0
    //   Solution:
    //     A = h_peak - h_launch,  B = h_target - h_launch
    //     c = 16A − 5B,  d = 14B − 32A,  e = 16A − 8B
    //
    // Guidance law:
    //   slope   = (dh/ds) / R_total       (altitude slope at current progress)
    //   λ_com   = atan(slope)             (ICAS lead-angle command)
    //   λ_com  += atan(h_err / R_to_tgt)  (altitude error geometric correction)
    //   pitch cmd = direction at (pitch=λ_com, yaw=bearing_to_target)
    //
    // Why this works:
    //   - At s≈0: slope≈0 → fly horizontal (transition from TURN pitch-over)
    //   - At s=0.5: slope=0 → level flight at peak altitude
    //   - At s→1: slope<0 → descend to target
    //   - Altitude error correction prevents drift from profile over long flight
    //   - Autopilot τ_FC + G-limit provides smooth tracking (no extra filter needed)
    //
    // Physical motivation:
    //   At 8 km (True PN with maxTgo=40s equilibrium): ρ≈0.525 kg/m³
    //   At 25 km (this law): ρ≈0.040 kg/m³  →  drag 13× lower
    //   Sustainer (12,000–35,000 N) can maintain Mach 3–4 at 25 km;
    //   cannot maintain speed against supersonic drag at 8 km.
    // -------------------------------------------------------------------------
    public static class GuidanceLoftAPG extends EntityMissileBaseGuidance {
        /**
         * Sentinel value: pass ≤ 0 (or use the no-arg constructor) to compute
         * peak altitude dynamically from engagement geometry at midcourse entry
         * (ICAS 2024 method).
         */
        private static final double PEAK_ALT_AUTO = -1.0;

        /** Requested peak altitude spec (≤ 0 = dynamic auto-compute). */
        private final double peakAltitudeSpec;
        /** Effective peak altitude used by the polynomial (resolved on first call). */
        private double peakAltitude;

        // State captured at first computeDirection call (= midcourse entry point)
        private double launchX, launchY, launchZ;
        private boolean initialized;

        /**
         * Auto-compute peak altitude from engagement geometry (ICAS 2024).
         * Uses h_peak = tgt_alt + min(R_horiz·0.12, 12000), capped at 22 km.
         */
        public GuidanceLoftAPG() {
            this(PEAK_ALT_AUTO);
        }

        /**
         * @param peakAltitude  desired peak altitude in world Y (metres, absolute).
         *                      ICAS Trajectory 2: 30000 m;  Trajectory 3: 25000 m.
         *                      Pass ≤ 0 for dynamic auto-compute (ICAS 2024 Eq.6).
         */
        public GuidanceLoftAPG(double peakAltitude) {
            this.peakAltitudeSpec = peakAltitude;
            this.peakAltitude     = peakAltitude;   // overwritten on first call if ≤ 0
        }

        @Override
        public void reset() {
            initialized  = false;
            peakAltitude = peakAltitudeSpec;   // allow recompute on next initialisation
        }

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {

            // Capture midcourse entry position on first call
            if (!initialized) {
                launchX     = mPosX;
                launchY     = mPosY;
                launchZ     = mPosZ;
                initialized = true;
                // ICAS 2024 dynamic peak altitude (when peakAltitudeSpec ≤ 0):
                //
                // From ICAS 2024 Fig.3/4 trajectory data:
                //   Trajectory 2 (robust, 30km peak): 100km range, 10km launch → (30-10)/100 = 0.20
                //   Trajectory 3 (conservative, 25km): (25-10)/100 = 0.15
                // We use 0.12 (slightly below Trajectory 3) to ensure rho(h_peak) gives ≥ 20G.
                //   At 20km: rho≈0.089 kg/m³ → ~20G;  22km: rho≈0.065 → ~15G
                //
                // Caps:
                //   h_above_max  = 12000m  (beyond this the G-limit becomes marginal)
                //   h_peak_max   = 22000m  (hard altitude ceiling for terminal maneuvrability)
                //   h_above_min  = R_horiz×0.05, capped at 5000m  (scales with range;
                //                  fixed 5000m caused 5800m lofting at 10km range → APN divergence)
                //                  Values: 10km→500m, 50km→2500m, 100km→5000m
                //   h_peak_min   = launchY + 500m  (must clear launch altitude)
                if (peakAltitudeSpec <= 0.0) {
                    double dxInit    = tPosX - mPosX;
                    double dzInit    = tPosZ - mPosZ;
                    double R_horiz   = Math.sqrt(dxInit*dxInit + dzInit*dzInit);
                    double hAbove    = Math.min(R_horiz * 0.12, 12000.0);
                    double hAboveMin = Math.min(R_horiz * 0.05, 5000.0); // range-scaled minimum loft
                    peakAltitude     = Math.max(tPosY + Math.max(hAbove, hAboveMin), mPosY + 500.0);
                    peakAltitude     = Math.min(peakAltitude, 22000.0);
                }
            }

            double V = Math.sqrt(mVelX*mVelX + mVelY*mVelY + mVelZ*mVelZ);
            if (V < 1.0) return new double[]{ tPosX-mPosX, tPosY-mPosY, tPosZ-mPosZ };

            // ── Polynomial altitude profile ───────────────────────────────────
            double h_launch = launchY;
            double h_target = tPosY;
            double h_peak   = peakAltitude;

            double A = h_peak   - h_launch;
            double B = h_target - h_launch;
            double c = 16.0*A - 5.0*B;
            double d = 14.0*B - 32.0*A;
            double e = 16.0*A - 8.0*B;

            // Total ground range (XZ plane): midcourse entry → target
            double dxTot   = tPosX - launchX;
            double dzTot   = tPosZ - launchZ;
            double R_total = Math.sqrt(dxTot*dxTot + dzTot*dzTot);

            if (R_total < 1.0) {
                // Very short range or target directly overhead: pure PN fallback
                double[] fb = computeZemPN(
                        mPosX, mPosY, mPosZ, mVelX, mVelY, mVelZ,
                        tPosX, tPosY, tPosZ, tVelX, tVelY, tVelZ, 3.0, null);
                return (fb != null) ? fb : new double[]{ tPosX-mPosX, tPosY-mPosY, tPosZ-mPosZ };
            }

            // Normalized flight progress s ∈ [0,1]
            double dxL       = mPosX - launchX;
            double dzL       = mPosZ - launchZ;
            double rLaunched = Math.sqrt(dxL*dxL + dzL*dzL);
            double s         = Math.max(0.0, Math.min(1.0, rLaunched / R_total));

            // Commanded altitude and slope at progress s
            double s2      = s * s;
            double s3      = s2 * s;
            double s4      = s3 * s;
            double h_cmd   = h_launch + c*s2 + d*s3 + e*s4;
            double dh_ds   = 2.0*c*s + 3.0*d*s2 + 4.0*e*s3;
            double slope   = dh_ds / R_total;   // dh/dR (rise over run in world units)

            // Lead-angle command: atan(slope) (ICAS Eq., zero at peak and endpoints)
            double lambda_com = Math.atan(slope);

            // Altitude error PD correction
            // -----------------------------------------------------------------------
            // Problem: at BOOST entry the missile is pitched ~75° upward from TURN
            // but the polynomial commands 0° (horizontal) at s=0.  The original
            // atan(h_err / rangeToTarget) correction is < 0.5° for rangeToTarget
            // > 100 km, which is far too weak to arrest the overshoot.
            //
            // Fix: cap the reference distance at 3000 m so the P-term gives
            //   ~15° correction per 800 m of altitude error.
            //   At close range (rangeToTarget < 3000 m) the exact geometric angle
            //   is used (rangeToTarget), preserving accuracy for the terminal dive.
            //
            // D-term: penalise altitude rate error (desired rate vs actual mVelY).
            //   desired_hdot  = slope * V_horiz  (from polynomial tangent)
            //   hdot_err      = desired - actual
            //   This provides pitch-down authority even when h_err is still small
            //   (i.e., the missile is on-profile but climbing too fast).
            double dxT           = tPosX - mPosX;
            double dzT           = tPosZ - mPosZ;
            double rangeToTarget = Math.max(Math.sqrt(dxT*dxT + dzT*dzT), 100.0);
            double h_err         = h_cmd - mPosY;

            // P-term: proportional altitude error (strong at long range)
            double errRef  = Math.min(rangeToTarget, 3000.0);
            double p_corr  = Math.atan(h_err / errRef);

            // D-term: altitude rate error
            double vHoriz   = Math.sqrt(mVelX*mVelX + mVelZ*mVelZ);
            double hdot_des = slope * vHoriz;          // desired vertical speed (m/s)
            double hdot_err = hdot_des - mVelY;        // +ve = need to climb faster
            // Convert rate error to angle correction (0.1 s look-ahead).
            // Ramp D-term authority from ±10° at loft entry (s=0) up to ±30° once
            // s ≥ 0.08 (about 8 km / 6-8 s into flight).  This prevents the immediate
            // -30° clamp snap at BOOST entry when the missile still has large residual
            // climb velocity from the TURN phase (hdot_err ≈ -700 m/s → atan ≈ -90°).
            // After the transient settles the full ±30° authority is restored.
            double dMaxRad  = Math.toRadians(10.0 + 20.0 * Math.min(s / 0.08, 1.0));
            double d_corr   = Math.max(-dMaxRad,
                              Math.min( dMaxRad,
                              Math.atan2(hdot_err * 0.1, Math.max(vHoriz * 0.1, 1.0))));

            lambda_com += p_corr + d_corr;

            // Clamp commanded pitch to avoid impossible climb/dive angles
            lambda_com = Math.max(Math.toRadians(-80.0), Math.min(Math.toRadians(80.0), lambda_com));

            // Azimuth command: always point toward target (lateral PN, natural)
            double targetBearing = Math.atan2(tPosX - mPosX, tPosZ - mPosZ);

            // Build and return direction vector (autopilot applies τ_FC + G-limit)
            double cosP = Math.cos(lambda_com);
            if (!warmupMode) {
                System.out.println(String.format(
                    "[LOFT age=%d] s=%.3f h_peak=%.0f h_cmd=%.0f h_cur=%.0f h_err=%.0f slope=%.4f lam_com=%.1fdeg bearing=%.1fdeg",
                    age, s, peakAltitude, h_cmd, mPosY, h_err, slope,
                    Math.toDegrees(lambda_com), Math.toDegrees(targetBearing)));
            }
            return new double[]{
                    Math.sin(targetBearing) * cosP,
                    Math.sin(lambda_com),
                    Math.cos(targetBearing) * cosP
            };
        }
    }

    // -------------------------------------------------------------------------
    // GuidancePN – Palumbo (2010) True PN
    //
    // Implements True Proportional Navigation per Palumbo Eq.17-20:
    //   a_Mc = N * V_c * phi_dot_omega  (Eq.19-20, True PN vector form)
    //
    // Key implementation via ZEM (Zero-Effort-Miss):
    //   V_c = -dR/dt = -(r̄·v̄_rel)/R          (Palumbo Eq.17-18, closing velocity)
    //   t_go = R / V_c  (or R/V if not closing)
    //   ZEM = (futureTarget - futureMissile) projected onto missile-frame axes
    //   a_cmd = N * ZEM / t_go²               (equivalent to Eq.19-20)
    //
    // Guidance filter G_F(s) = 1/(τ_f·s+1)   (Palumbo Eq.25, 36):
    //   Discrete IIR:  α = exp(-DT/τ_f)
    //   filtA[k] = (1-α)*raw[k] + α*filtA[k-1]
    // -------------------------------------------------------------------------
    public static class GuidancePN extends EntityMissileBaseGuidance {
        private final double N;
        private final double tauF;
        private double filtAPitch = 0.0;
        private double filtAYaw   = 0.0;

        /** No-filter constructor. */
        public GuidancePN(double N) { this(N, 0.0); }

        /**
         * @param N    navigation gain (Berglund: 3–5; > 2 required for stability)
         * @param tauF guidance filter time constant (s) per Palumbo Eq.25/36;
         *             0 = no filter
         */
        public GuidancePN(double N, double tauF) {
            this.N    = N;
            this.tauF = tauF;
        }

        @Override
        public void reset() { filtAPitch = 0.0; filtAYaw = 0.0; }

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {
            double[] raw = computeZemRaw(
                    mPosX, mPosY, mPosZ, mVelX, mVelY, mVelZ,
                    tPosX, tPosY, tPosZ, tVelX, tVelY, tVelZ,
                    N, null, 0.0);
            if (raw == null) {
                return new double[]{ tPosX - mPosX, tPosY - mPosY, tPosZ - mPosZ };
            }
            // Palumbo Eq.25, 36: G_F(s) = 1/(τ_f·s+1)
            double alpha = (tauF > 0.0) ? Math.exp(-DT / tauF) : 0.0;
            filtAPitch = (1.0 - alpha) * raw[0] + alpha * filtAPitch;
            filtAYaw   = (1.0 - alpha) * raw[1] + alpha * filtAYaw;
            return zemAccelToDirection(filtAPitch, filtAYaw,
                    raw[2], raw[3], raw[4], raw[5], raw[6], N);
        }
    }

    // -------------------------------------------------------------------------
    // GuidanceAPN – Augmented PN (Berglund 2001 + Palumbo ZEM form)
    //
    // Berglund Eq.: n_M = α * (v_c * phi_dot + ½ * n_T)
    //   – Adds target acceleration correction term ½ * n_T to PN law
    //   – Reduces required missile acceleration for maneuvering targets:
    //     PN needs 3× target G; APN needs only 2× target G (Berglund Table)
    //   – Maximum acceleration for APN occurs at manoeuvre initiation,
    //     not at endgame — earlier demand, more time to respond
    //
    // Caution (Berglund Figure 5 3D surface plot):
    //   APN superiority is sensitive to target acceleration estimation error.
    //   n̂_T/n_T ≈ 1.0 required; overestimating is especially bad.
    //   Smoothing factor α=0.3 and 5G cap applied to mitigate sensitivity.
    //
    // Implementation via ZEM: ZEM_APN = ZEM_PN + ½·a_T·t_go²
    //   (Palumbo general APN form, produces N/2·a_T term automatically)
    // -------------------------------------------------------------------------
    public static class GuidanceAPN extends EntityMissileBaseGuidance {
        private final double N;
        private final double tauF;
        private double filtAPitch = 0.0;
        private double filtAYaw   = 0.0;

        // ── Fix G: Position-based target velocity estimator ──────────────────
        // Root cause of head-on miss (2026-03): getTargetVelocity() returns
        // activeTarget.motionX / DT.  For some entity types (EntityMissileBaseAdvanced
        // etc.) motionX is not stored as blocks/tick, so the returned velocity is
        // ~1/10 of actual.  The seeker model (EntityMissileBaseRealistic
        // updateSeekerEstimate) then propagates the wrong velocity into seekerEstVelX,
        // which becomes guidVel[0] and is passed here as tVelX.
        //
        // Solution: estimate target velocity entirely from the seeker-reconstructed
        // POSITION (tPosX = seekerEstPosX), which IS accurate because the dish model
        // correctly tracks the true LOS angle + range, independent of motionX.
        // Finite-difference + EMA smoothing:
        //   rawVX[k] = (tPosX[k] - tPosX[k-1]) / DT
        //   estTVelX[k] = TVEL_SMOOTH * rawVX[k] + (1 - TVEL_SMOOTH) * estTVelX[k-1]
        // TVEL_SMOOTH = 0.5: converges to true velocity within ~10 ticks from cold start.
        // With the warm-up system running all terminal laws since TERMINAL entry, the
        // estimate is fully converged long before the law becomes active.
        private double prevTPosX = Double.NaN;
        private double prevTPosY = Double.NaN;
        private double prevTPosZ = Double.NaN;
        private double estTVelX  = 0.0;
        private double estTVelY  = 0.0;
        private double estTVelZ  = 0.0;
        private static final double TVEL_SMOOTH = 0.5;

        // Target acceleration estimator state (operates on estTVel, not raw tVel)
        private double prevTVelX = Double.NaN;
        private double prevTVelY = Double.NaN;
        private double prevTVelZ = Double.NaN;
        private double estAccelX = 0.0;
        private double estAccelY = 0.0;
        private double estAccelZ = 0.0;

        // Berglund Figure 5: cap estimate conservatively to limit sensitivity
        // 5G cap (vs 10G prev): APN degrades badly when estimate exceeds true value
        private static final double MAX_EST_ACCEL = 5.0 * 9.80665;
        // Exponential smoothing factor for acceleration estimate (Berglund sensitivity caution)
        private static final double ACCEL_SMOOTH  = 0.3;

        public GuidanceAPN(double N) { this(N, 0.0); }

        /**
         * @param N    navigation gain
         * @param tauF guidance filter time constant (s) per Palumbo Eq.25/36
         */
        public GuidanceAPN(double N, double tauF) {
            this.N    = N;
            this.tauF = tauF;
        }

        @Override
        public void reset() {
            filtAPitch = 0.0;
            filtAYaw   = 0.0;
            prevTPosX  = Double.NaN;
            prevTPosY  = Double.NaN;
            prevTPosZ  = Double.NaN;
            estTVelX   = estTVelY = estTVelZ = 0.0;
            prevTVelX  = Double.NaN;
            prevTVelY  = Double.NaN;
            prevTVelZ  = Double.NaN;
            estAccelX  = estAccelY = estAccelZ = 0.0;
        }

        /**
         * Reset only the guidance-command filter (filtAPitch, filtAYaw).
         * The position-based velocity estimator (prevTPosX/Y/Z, estTVelX/Y/Z,
         * prevTVelX/Y/Z, estAccelX/Y/Z) is intentionally preserved so that the
         * estimate already converged during CRUISE/COAST warm-up remains valid
         * at TERMINAL phase entry, eliminating the cold-start miss bias.
         */
        @Override
        public void resetGuidanceFiltersOnly() {
            filtAPitch = 0.0;
            filtAYaw   = 0.0;
            // Velocity estimator state deliberately NOT reset here.
        }

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {

            // ── Fix G: Estimate target velocity from seeker position tracking ─
            // tPosX/Y/Z is the seeker-reconstructed position (accurate regardless
            // of motionX corruption).  On the first tick prevTPosX is NaN — seed
            // the EMA and return point-at-target (tVelX may be corrupted, so
            // computeZemRaw must not run).  Position estimator takes over tick 2+.
            double useVelX, useVelY, useVelZ;
            if (!Double.isNaN(prevTPosX)) {
                double rawVX = (tPosX - prevTPosX) / DT;
                double rawVY = (tPosY - prevTPosY) / DT;
                double rawVZ = (tPosZ - prevTPosZ) / DT;
                // Rate-limit: clamp EMA output change to ≤ 50G × DT per tick.
                // Minecraft entity types that batch-update posX every N ticks cause
                // single-tick rawVX spikes (e.g. 2× normal speed in one tick).  With
                // TVEL_SMOOTH=0.5 that produces a ~300 m/s step in estTVelX, which
                // flips the ZEM sign and drives APN to oscillate dive/climb for the
                // next 17-18 ticks.  Capping the per-tick EMA change at 50G×DT
                // (≈24.5 m/s) limits the transient to < 8% of the pre-spike error
                // while still tracking genuine target manoeuvres (max ~10G for aircraft).
                final double maxVelChange = 50.0 * 9.80665 * DT;
                double candidateX = TVEL_SMOOTH * rawVX + (1.0 - TVEL_SMOOTH) * estTVelX;
                double candidateY = TVEL_SMOOTH * rawVY + (1.0 - TVEL_SMOOTH) * estTVelY;
                double candidateZ = TVEL_SMOOTH * rawVZ + (1.0 - TVEL_SMOOTH) * estTVelZ;
                estTVelX += Math.max(-maxVelChange, Math.min(maxVelChange, candidateX - estTVelX));
                estTVelY += Math.max(-maxVelChange, Math.min(maxVelChange, candidateY - estTVelY));
                estTVelZ += Math.max(-maxVelChange, Math.min(maxVelChange, candidateZ - estTVelZ));
                useVelX  = estTVelX;
                useVelY  = estTVelY;
                useVelZ  = estTVelZ;
            } else {
                // First tick: seed the position-based EMA and bail out early.
                // tVelX/Y/Z may be motionX-corrupted on tick 1, so feeding it
                // into computeZemRaw produces a garbage ZEM command (often zero).
                // Return a safe point-at-target direction instead; the ZEM law
                // takes over from tick 2 once the position estimator has one
                // valid finite-difference step.
                estTVelX = tVelX; estTVelY = tVelY; estTVelZ = tVelZ;
                prevTPosX = tPosX; prevTPosY = tPosY; prevTPosZ = tPosZ;
                return new double[]{ tPosX - mPosX, tPosY - mPosY, tPosZ - mPosZ };
            }
            prevTPosX = tPosX; prevTPosY = tPosY; prevTPosZ = tPosZ;

            // --- Target acceleration estimate (finite difference of estTVel) ---
            // Berglund: n_M = α*(v_c*phi_dot + ½*n_T) requires n_T estimate.
            // Use position-derived velocity for the diff so corruption in tVel
            // does not pollute the acceleration estimate.
            if (!Double.isNaN(prevTVelX)) {
                double ax = (useVelX - prevTVelX) / DT;
                double ay = (useVelY - prevTVelY) / DT;
                double az = (useVelZ - prevTVelZ) / DT;
                // Exponential smoothing (Berglund sensitivity caution: keep α low)
                estAccelX = ACCEL_SMOOTH * ax + (1.0 - ACCEL_SMOOTH) * estAccelX;
                estAccelY = ACCEL_SMOOTH * ay + (1.0 - ACCEL_SMOOTH) * estAccelY;
                estAccelZ = ACCEL_SMOOTH * az + (1.0 - ACCEL_SMOOTH) * estAccelZ;
                // Conservative cap: overestimating target accel degrades APN severely
                double aMag = Math.sqrt(estAccelX*estAccelX + estAccelY*estAccelY + estAccelZ*estAccelZ);
                if (aMag > MAX_EST_ACCEL) {
                    double s = MAX_EST_ACCEL / aMag;
                    estAccelX *= s; estAccelY *= s; estAccelZ *= s;
                }
            }
            prevTVelX = useVelX; prevTVelY = useVelY; prevTVelZ = useVelZ;

            // --- ZEM-PN core (pure Palumbo ZEM, no acceleration augmentation) ---
            // Fix 2: Pass null for tAccel so computeZemRaw omits the ½·a_T·t_go²
            // term.  Berglund Figure 5 shows APN is highly sensitive to estimation
            // error — overestimating target acceleration degrades intercept badly.
            // The position-derived acceleration estimate (estAccelX/Y/Z, updated
            // above) is retained in state for diagnostic purposes but not used here.
            // useVelX/Y/Z (position-derived) replaces the corrupted tVelX/Y/Z.
            double[] raw = computeZemRaw(
                    mPosX, mPosY, mPosZ, mVelX, mVelY, mVelZ,
                    tPosX, tPosY, tPosZ, useVelX, useVelY, useVelZ,
                    N, null, 0.0);
            if (raw == null) {
                return new double[]{ tPosX - mPosX, tPosY - mPosY, tPosZ - mPosZ };
            }
            // Palumbo Eq.25, 36: G_F(s) = 1/(τ_f·s+1)
            double alpha = (tauF > 0.0) ? Math.exp(-DT / tauF) : 0.0;
            filtAPitch = (1.0 - alpha) * raw[0] + alpha * filtAPitch;
            filtAYaw   = (1.0 - alpha) * raw[1] + alpha * filtAYaw;
            // ── Fix G diagnostic: dump every tick (suppressed during background warm-up) ──
            if (!warmupMode) {
                double t_g  = raw[6];
                double zemP = raw[0] * t_g * t_g / N;
                double zemYw = raw[1] * t_g * t_g / N;
                double dgP  = Math.toDegrees(filtAPitch * (t_g / N) / raw[4]);
                System.out.println(String.format(
                    "[APNDBG tau=%.2f age=%d] " +
                    "mPos=(%.0f,%.0f,%.0f) mVel=(%.1f,%.1f,%.1f) " +
                    "tPos=(%.0f,%.0f,%.0f) useVel=(%.1f,%.1f,%.1f) tVelIn=(%.1f,%.1f,%.1f) " +
                    "tgo=%.3f zemP=%.1f zemYaw=%.1f " +
                    "aP_raw=%.3f filtAP=%.3f dgamma=%.4f",
                    tauF, age,
                    mPosX, mPosY, mPosZ,
                    mVelX, mVelY, mVelZ,
                    tPosX, tPosY, tPosZ,
                    useVelX, useVelY, useVelZ,
                    tVelX,  tVelY,  tVelZ,
                    t_g, zemP, zemYw,
                    raw[0], filtAPitch,
                    dgP));
            }
            // ──────────────────────────────────────────────────────────────────────────
            return zemAccelToDirection(filtAPitch, filtAYaw,
                    raw[2], raw[3], raw[4], raw[5], raw[6], N);
        }
    }

    // -------------------------------------------------------------------------
    // GuidanceIACG – Optimal Impact-Angle Constrained Guidance (Ryoo 2005)
    //
    // Reference: C.-K. Ryoo, H. Cho, M.-J. Tahk,
    //   "Optimal Guidance Laws with Terminal Impact Angle Constraint",
    //   J. Guidance, Control & Dynamics, 28(4), pp.724-732, 2005.
    //
    // Law (Case A – lag-free autopilot, Eq.26):
    //   u*(t) = (V/tgo) · [-6·θ(t) + 4·θ_m(t) + 2·θ_mf]
    //
    //   θ(t)   = current LOS pitch angle (atan2(dy, horizR)) [rad]
    //   θ_m(t) = missile flight-path angle (pitch) [rad]
    //   θ_mf   = desired terminal impact angle (pitch) [rad]
    //   tgo    = time-to-go via Ryoo Method 2 (Table 1, sub-1% error):
    //              θ̄_m  = θ_m  − θ
    //              θ̄_mf = θ_mf − θ
    //              V̄    = V·(1 − (θ̄_m²+θ̄_mf²)/15 + θ̄_m·θ̄_mf/30)
    //              tgo  = R / V̄
    //
    // Pitch plane : IACG (impact-angle constrained, Eq.26)
    // Yaw   plane : ZEM-PN N=6 (no yaw impact-angle constraint)
    //
    // Scale convention for zemAccelToDirection (K = tgo/N):
    //   a_pitch_scaled = u·DT·N/tgo
    //   ⟹ dgamma = a_pitch_scaled·K/V = u·DT/V  (1-tick forward integration)
    //
    // Impact angle:
    //   Pass impactAnglePitchDeg = Double.NaN to auto-capture the LOS pitch
    //   at the first active call (= current LOS at TERMINAL entry).
    //   This commands the missile to hit along whatever line it first sees the
    //   target on — optimal for anti-air where approach angle varies per shot.
    //
    // Target velocity: same EMA estimator as GuidanceAPN (fix G).
    // -------------------------------------------------------------------------
    public static class GuidanceIACG extends EntityMissileBaseGuidance {
        private final double N;
        private final double tauF;
        /**
         * Desired terminal flight-path angle in pitch (rad).
         * Double.NaN = auto-capture from current LOS pitch at first active call.
         */
        private final double impactAnglePitchRad;

        // Guidance command filter
        private double filtAPitch = 0.0;
        private double filtAYaw   = 0.0;

        // Captured impact angle (set once from impactAnglePitchRad or LOS)
        private double capturedThetaMf = Double.NaN;

        // Position-based target velocity estimator (same as GuidanceAPN, fix G)
        private double prevTPosX = Double.NaN;
        private double prevTPosY = Double.NaN;
        private double prevTPosZ = Double.NaN;
        private double estTVelX  = 0.0;
        private double estTVelY  = 0.0;
        private double estTVelZ  = 0.0;
        private static final double TVEL_SMOOTH_IACG = 0.5;

        /**
         * @param N                   navigation gain (6.0 = optimal for IACG)
         * @param tauF                guidance-command filter time constant (s)
         * @param impactAnglePitchDeg desired terminal flight-path angle (deg);
         *                            pass Double.NaN for auto-capture from LOS
         */
        public GuidanceIACG(double N, double tauF, double impactAnglePitchDeg) {
            this.N    = N;
            this.tauF = tauF;
            this.impactAnglePitchRad = Double.isNaN(impactAnglePitchDeg)
                    ? Double.NaN
                    : Math.toRadians(impactAnglePitchDeg);
        }

        @Override
        public void reset() {
            filtAPitch      = 0.0;
            filtAYaw        = 0.0;
            capturedThetaMf = Double.NaN;
            prevTPosX = prevTPosY = prevTPosZ = Double.NaN;
            estTVelX  = estTVelY = estTVelZ = 0.0;
        }

        /**
         * Reset guidance-command filters and the captured impact angle.
         * capturedThetaMf is reset to NaN so it is re-captured from the actual LOS
         * at the first TERMINAL tick, not from the stale warm-up call during CRUISE/COAST.
         * This prevents the IACG from demanding a huge pull-up correction when the missile
         * enters TERMINAL from a steep lofted-trajectory dive (theta_m >> theta_LOS).
         */
        @Override
        public void resetGuidanceFiltersOnly() {
            filtAPitch      = 0.0;
            filtAYaw        = 0.0;
            capturedThetaMf = Double.NaN;
        }

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {

            // ── Target velocity estimation (fix G, same as GuidanceAPN) ──────
            double useVelX, useVelY, useVelZ;
            if (!Double.isNaN(prevTPosX)) {
                double rawVX = (tPosX - prevTPosX) / DT;
                double rawVY = (tPosY - prevTPosY) / DT;
                double rawVZ = (tPosZ - prevTPosZ) / DT;
                // Same 50G rate-limit as GuidanceAPN — reject single-tick position spikes.
                final double maxVelChangeI = 50.0 * 9.80665 * DT;
                double candidateX = TVEL_SMOOTH_IACG * rawVX + (1.0 - TVEL_SMOOTH_IACG) * estTVelX;
                double candidateY = TVEL_SMOOTH_IACG * rawVY + (1.0 - TVEL_SMOOTH_IACG) * estTVelY;
                double candidateZ = TVEL_SMOOTH_IACG * rawVZ + (1.0 - TVEL_SMOOTH_IACG) * estTVelZ;
                estTVelX += Math.max(-maxVelChangeI, Math.min(maxVelChangeI, candidateX - estTVelX));
                estTVelY += Math.max(-maxVelChangeI, Math.min(maxVelChangeI, candidateY - estTVelY));
                estTVelZ += Math.max(-maxVelChangeI, Math.min(maxVelChangeI, candidateZ - estTVelZ));
                useVelX = estTVelX;
                useVelY = estTVelY;
                useVelZ = estTVelZ;
            } else {
                // First call: seed estimator, return direct pursuit
                estTVelX  = tVelX; estTVelY = tVelY; estTVelZ = tVelZ;
                prevTPosX = tPosX; prevTPosY = tPosY; prevTPosZ = tPosZ;
                return new double[]{ tPosX - mPosX, tPosY - mPosY, tPosZ - mPosZ };
            }
            prevTPosX = tPosX; prevTPosY = tPosY; prevTPosZ = tPosZ;

            // ── Geometry ──────────────────────────────────────────────────────
            double dx = tPosX - mPosX, dy = tPosY - mPosY, dz = tPosZ - mPosZ;
            double R  = Math.sqrt(dx*dx + dy*dy + dz*dz);
            double V  = Math.sqrt(mVelX*mVelX + mVelY*mVelY + mVelZ*mVelZ);
            if (R < 1.0 || V < 1.0) return new double[]{ dx, dy, dz };

            // Missile flight-path angle (pitch, inertial)
            double horizV  = Math.sqrt(mVelX*mVelX + mVelZ*mVelZ);
            double theta_m = Math.atan2(mVelY, horizV);
            double chiM    = Math.atan2(mVelX, mVelZ);
            double cosG    = Math.cos(theta_m);

            // LOS pitch angle
            double horizR    = Math.sqrt(dx*dx + dz*dz);
            double theta_LOS = Math.atan2(dy, horizR);

            // Auto-capture impact angle at first active call
            if (Double.isNaN(capturedThetaMf)) {
                capturedThetaMf = Double.isNaN(impactAnglePitchRad)
                        ? theta_LOS
                        : impactAnglePitchRad;
            }
            double theta_mf = capturedThetaMf;

            // ── tgo — Ryoo 2005 Method 2 (Table 1) ────────────────────────────
            // V̄ = V·(1 − (θ̄_m²+θ̄_mf²)/15 + θ̄_m·θ̄_mf/30)   where θ̄ = θ − θ_LOS
            // Error < 1% for all finite angles (paper Table 1); far better than R/V.
            double tm      = theta_m  - theta_LOS;
            double tmf     = theta_mf - theta_LOS;
            double V_bar   = V * (1.0 - (tm*tm + tmf*tmf) / 15.0 + tm*tmf / 30.0);
            if (V_bar < 1.0) V_bar = 1.0;   // guard extreme angles
            double tgo = Math.max(R / V_bar, 0.28);   // floor = total guidance loop lag

            // ── IACG pitch command — Ryoo 2005 Eq.26 ─────────────────────────
            // u*(t) = (V/tgo)·[-6·θ + 4·θ_m + 2·θ_mf]
            //
            // Scaled for zemAccelToDirection (which applies K = tgo/N):
            //   a_scaled = u·DT·N/tgo  →  dgamma = a_scaled·K/V = u·DT/V ✓
            double f_iacg  = -6.0 * theta_LOS + 4.0 * theta_m + 2.0 * theta_mf;
            double u_iacg  = (V / tgo) * f_iacg;
            double aPitch  = u_iacg * DT * N / tgo;

            // ── ZEM-PN yaw command ────────────────────────────────────────────
            // Palumbo left-axis (yaw, perpendicular to velocity in horizontal plane)
            double sinC = Math.sin(chiM), cosC = Math.cos(chiM);
            double ltX  = cosC, ltY = 0.0, ltZ = -sinC;

            // Future positions (estimated target velocity; missile under gravity)
            double futMX = mPosX + mVelX * tgo;
            double futMY = mPosY + mVelY * tgo - 0.5 * GRAVITY * tgo * tgo;
            double futMZ = mPosZ + mVelZ * tgo;
            double futTX = tPosX + useVelX * tgo;
            double futTY = tPosY + useVelY * tgo;
            double futTZ = tPosZ + useVelZ * tgo;

            double zemX   = futTX - futMX, zemY = futTY - futMY, zemZ = futTZ - futMZ;
            double zemYaw = zemX * ltX + zemY * ltY + zemZ * ltZ;
            double aYaw   = N * zemYaw / (tgo * tgo);

            // ── Guidance filter (Palumbo Eq.25/36) ───────────────────────────
            double alpha   = (tauF > 0.0) ? Math.exp(-DT / tauF) : 0.0;
            filtAPitch = (1.0 - alpha) * aPitch + alpha * filtAPitch;
            filtAYaw   = (1.0 - alpha) * aYaw   + alpha * filtAYaw;

            // ── Debug log ─────────────────────────────────────────────────────
            if (!warmupMode) {
                System.out.println(String.format(
                        "[IACG age=%d] R=%.0fm tgo=%.2fs " +
                        "theta=%.1f tm=%.1f tmf=%.1f " +
                        "f=%.2f u=%.1f aPitch=%.1f aYaw=%.1f",
                        age, R, tgo,
                        Math.toDegrees(theta_LOS),
                        Math.toDegrees(theta_m),
                        Math.toDegrees(theta_mf),
                        Math.toDegrees(f_iacg),
                        u_iacg, filtAPitch, filtAYaw));
            }

            return zemAccelToDirection(filtAPitch, filtAYaw, theta_m, chiM, V, cosG, tgo, N);
        }
    }

    // -------------------------------------------------------------------------
    // GuidanceQPN – Geometric quadratic intercept (lead-angle ProNav-style)
    //
    // Computes the earliest intercept time t_i satisfying:
    //   |target_pos(t_i) - missile_pos| = V_missile * t_i
    // Assuming constant target velocity and constant missile speed V.
    //
    // Expanding |r + tVel·t|² = V²·t²  (r = tPos − mPos):
    //   V²·t² = dist² + 2·(r·tVel)·t + tV²·t²
    //   (V²−tV²)·t² − 2·(r·tVel)·t − dist² = 0
    //
    // Fix (2026-03): previous version had dimensional bug in the b coefficient:
    //   OLD (wrong): a_q = 1−vRatio², b_q = 2·vRatio·dist·cosAlpha  [b in metres, not dimensionless]
    //   NEW (correct): a_q = V²−tV²,  b_q = −2·(r·tVel)              [all in SI units: a=m²/s², b=m²/s, c=m²]
    // The wrong b_q was off by a factor of V (~900), giving t_i ~1237 s instead of ~2 s
    // and commanding the missile toward the bomber's flight direction rather than its lead point.
    // -------------------------------------------------------------------------
    public static class GuidanceQPN extends EntityMissileBaseGuidance {

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {
            double rx = tPosX - mPosX;
            double ry = tPosY - mPosY;
            double rz = tPosZ - mPosZ;
            double dist2 = rx*rx + ry*ry + rz*rz;
            if (dist2 < 1.0) return new double[]{ rx, ry, rz };

            double V  = Math.sqrt(mVelX*mVelX + mVelY*mVelY + mVelZ*mVelZ);
            double tV2 = tVelX*tVelX + tVelY*tVelY + tVelZ*tVelZ;
            if (V < 1.0) return new double[]{ rx, ry, rz };

            // (V²−tV²)·t² − 2·(r·tVel)·t − dist² = 0
            double a_q = V * V - tV2;
            double rdottv = rx*tVelX + ry*tVelY + rz*tVelZ;
            double b_q = -2.0 * rdottv;
            double c_q = -dist2;

            double t_intercept;
            if (Math.abs(a_q) < 1.0) {
                // V ≈ tV: linear equation  b_q·t + c_q = 0  →  t = dist²/(2·(r·tVel))
                // Falls back to dist/V if target is stationary or converging coefficient near zero
                t_intercept = (Math.abs(b_q) > 1e-6) ? -c_q / b_q : Math.sqrt(dist2) / V;
            } else {
                double disc = b_q * b_q - 4.0 * a_q * c_q;
                if (disc < 0.0) return new double[]{ rx, ry, rz };
                t_intercept = (-b_q + Math.sqrt(disc)) / (2.0 * a_q);
                if (t_intercept < 0.0) t_intercept = (-b_q - Math.sqrt(disc)) / (2.0 * a_q);
                if (t_intercept < 0.0) return new double[]{ rx, ry, rz };
            }

            double ipx = tPosX + tVelX * t_intercept;
            double ipy = tPosY + tVelY * t_intercept;
            double ipz = tPosZ + tVelZ * t_intercept;
            return new double[]{ ipx - mPosX, ipy - mPosY, ipz - mPosZ };
        }
    }

    // -------------------------------------------------------------------------
    // GuidancePIP – Predicted Impact Point (iterative forward simulation)
    // -------------------------------------------------------------------------
    public static class GuidancePIP extends EntityMissileBaseGuidance {
        private static final int    SIM_STEPS    = 200;
        private static final int    SMOOTHER_SIZE = 5;
        private static final double SIM_DT        = 0.05;

        private final double[] smoothX = new double[SMOOTHER_SIZE];
        private final double[] smoothY = new double[SMOOTHER_SIZE];
        private final double[] smoothZ = new double[SMOOTHER_SIZE];
        private int smoothIdx  = 0;
        private boolean smoothFull = false;

        @Override
        public void reset() { smoothIdx = 0; smoothFull = false; }

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {
            double V = Math.sqrt(mVelX*mVelX + mVelY*mVelY + mVelZ*mVelZ);
            double px = tPosX, py = tPosY, pz = tPosZ;
            double ipx = tPosX, ipy = tPosY, ipz = tPosZ;
            for (int i = 0; i < SIM_STEPS; i++) {
                px += tVelX * SIM_DT;
                py += tVelY * SIM_DT;
                pz += tVelZ * SIM_DT;
                double dx = px - mPosX, dy = py - mPosY, dz = pz - mPosZ;
                double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                double tFly = dist / Math.max(V, 1.0);
                double tSim = (i + 1) * SIM_DT;
                if (Math.abs(tFly - tSim) < SIM_DT * 1.5 || i == SIM_STEPS - 1) {
                    ipx = px; ipy = py; ipz = pz;
                    break;
                }
            }
            smoothX[smoothIdx] = ipx;
            smoothY[smoothIdx] = ipy;
            smoothZ[smoothIdx] = ipz;
            smoothIdx = (smoothIdx + 1) % SMOOTHER_SIZE;
            if (smoothIdx == 0) smoothFull = true;
            int count = smoothFull ? SMOOTHER_SIZE : smoothIdx;
            double sx = 0, sy = 0, sz = 0;
            for (int i = 0; i < count; i++) { sx += smoothX[i]; sy += smoothY[i]; sz += smoothZ[i]; }
            sx /= count; sy /= count; sz /= count;
            return new double[]{ sx - mPosX, sy - mPosY, sz - mPosZ };
        }
    }

    // -------------------------------------------------------------------------
    // GuidanceLOS – Line-of-sight proportional pursuit
    // -------------------------------------------------------------------------
    public static class GuidanceLOS extends EntityMissileBaseGuidance {
        private final double pValue;

        public GuidanceLOS(double pValue) { this.pValue = pValue; }

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {
            double dx = tPosX - mPosX;
            double dy = tPosY - mPosY;
            double dz = tPosZ - mPosZ;
            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (dist < 1.0) return new double[]{ dx, dy, dz };
            double lx = dx / dist, ly = dy / dist, lz = dz / dist;
            double V = Math.sqrt(mVelX*mVelX + mVelY*mVelY + mVelZ*mVelZ);
            if (V < 1.0) return new double[]{ dx, dy, dz };
            double ux = mVelX / V, uy = mVelY / V, uz = mVelZ / V;
            double bx = (1.0 - pValue) * ux + pValue * lx;
            double by = (1.0 - pValue) * uy + pValue * ly;
            double bz = (1.0 - pValue) * uz + pValue * lz;
            return new double[]{ bx, by, bz };
        }
    }

    // -------------------------------------------------------------------------
    // GuidancePNLosRate – True PN via instantaneous LOS angular rate
    //
    // Implements the True PN formulation from propnavpt.m
    // (companion code to ADA556639 technical report):
    //
    //   n_y = N' * V_c * σ̇_yaw   / cos(ψ_m − σ_yaw)    (horizontal plane)
    //   n_z = N' * V_c * σ̇_pitch / cos(γ_m − σ_pitch)   (vertical plane) + g
    //
    // Where:
    //   σ̇     = instantaneous LOS angular rate (rad/s), estimated via
    //            finite difference and smoothed by a first-order IIR filter
    //   ψ_m, γ_m = missile azimuth and elevation heading angles
    //   V_c   = closing velocity  r̂ · (mVel − tVel), positive when closing
    //   +g/V  = gravity compensation in pitch channel
    //
    // Advantages over ZEM-APN (terminal far) and QPN (endgame) for close-range:
    //   • No t_go estimation    – avoids instability below t_go < N·τ_total ≈ 1.4 s
    //   • No ZEM computation    – no phase-transition discontinuity at 2 km / 5 km
    //   • Continuous law        – same class for near (2–5 km) and endgame (<2 km),
    //                             only the filter time constant differs
    //   • Natural gain roll-off – gain ∝ N·V_c (not N/t_go²), decreases smoothly
    //                             as V_c → 0 instead of diverging
    //   • No reversal commands  – when target is overshot (V_c < 0), gain becomes
    //                             negative and command smoothly saturates
    //
    // LOS rate is filtered with a first-order IIR (τ = tauFilter) to suppress
    // quantisation noise from the seeker/RDBG.  Shorter τ → faster response;
    // longer τ → less noise.  SM-6: τ=0.10 s for near, τ=0.05 s for endgame.
    //
    // Reference: propnavpt.m from ADA556639 companion code (Ratnoo, Ghose 2009).
    // -------------------------------------------------------------------------
    public static class GuidancePNLosRate extends EntityMissileBaseGuidance {
        private final double N;
        private final double tauFilter;

        private double prevLosYaw   = Double.NaN;
        private double prevLosPitch = Double.NaN;
        private double filtLosRateYaw   = 0.0;
        private double filtLosRatePitch = 0.0;

        /**
         * @param N          navigation gain (5 per PDF optimal analysis for terminal)
         * @param tauFilter  LOS-rate IIR filter time constant (s);
         *                   0.10 for near terminal (2–5 km), 0.05 for endgame (<2 km)
         */
        public GuidancePNLosRate(double N, double tauFilter) {
            this.N         = N;
            this.tauFilter = tauFilter;
        }

        @Override
        public void reset() {
            prevLosYaw       = Double.NaN;
            prevLosPitch     = Double.NaN;
            filtLosRateYaw   = 0.0;
            filtLosRatePitch = 0.0;
        }

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {

            double rx = tPosX - mPosX;
            double ry = tPosY - mPosY;
            double rz = tPosZ - mPosZ;
            double dist = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (dist < 1.0) return new double[]{ rx, ry, rz };

            double V = Math.sqrt(mVelX * mVelX + mVelY * mVelY + mVelZ * mVelZ);
            if (V < 1.0) return new double[]{ rx, ry, rz };

            // --- LOS angles ---
            double rh       = Math.sqrt(rx * rx + rz * rz);
            double losYaw   = Math.atan2(rx, rz);
            double losPitch = Math.atan2(ry, rh);

            // --- LOS angular rate (rad/s) ---
            double losRateYaw, losRatePitch;
            if (Double.isNaN(prevLosYaw)) {
                // First tick: kinematic estimate from relative velocity
                // σ̇_yaw   = d(atan2(rx,rz))/dt = (rz*dRx − rx*dRz) / rh²
                // σ̇_pitch = d(atan2(ry,rh))/dt = (rh*dRy − ry*(rx*dRx+rz*dRz)/rh) / dist²
                // where dRx = d(rx)/dt = tVelX − mVelX
                double dRx = tVelX - mVelX;
                double dRy = tVelY - mVelY;
                double dRz = tVelZ - mVelZ;
                losRateYaw   = (rz * dRx - rx * dRz) / Math.max(rh * rh, 1.0);
                double rhDotdR = (rh > 0.01) ? (rx * dRx + rz * dRz) / rh : 0.0;
                losRatePitch = (rh * dRy - ry * rhDotdR) / Math.max(dist * dist, 1.0);
            } else {
                losRateYaw   = normalizeAngle(losYaw   - prevLosYaw)   / DT;
                losRatePitch = (losPitch - prevLosPitch) / DT;
            }
            prevLosYaw   = losYaw;
            prevLosPitch = losPitch;

            // --- Fix A: cap raw LOS rate before entering the filter ---
            // Physically, a 500 m/s target at 1 km gives σ̇ = 0.5 rad/s maximum.
            // Values beyond this arise from quantisation or warm-up transients; clipping
            // them before the IIR prevents a single-tick spike from biasing the estimate.
            final double MAX_RAW_LOS_RATE = 0.5; // rad/s ≈ 28.6°/s
            losRateYaw   = clamp(losRateYaw,   -MAX_RAW_LOS_RATE, MAX_RAW_LOS_RATE);
            losRatePitch = clamp(losRatePitch, -MAX_RAW_LOS_RATE, MAX_RAW_LOS_RATE);

            // --- IIR filter for LOS rate noise suppression ---
            // Clamp tauFilter to at least half a tick so alpha is well-defined
            double alpha = 1.0 - Math.exp(-DT / Math.max(tauFilter, DT * 0.5));
            filtLosRateYaw   = alpha * losRateYaw   + (1.0 - alpha) * filtLosRateYaw;
            filtLosRatePitch = alpha * losRatePitch + (1.0 - alpha) * filtLosRatePitch;

            // --- Closing velocity V_c = r̂ · (mVel − tVel), positive when closing ---
            double Vc = (rx * (mVelX - tVelX) + ry * (mVelY - tVelY) + rz * (mVelZ - tVelZ)) / dist;

            // --- Fix B: clamp V_c to ≥ 0 for the PN gain term ---
            // When Vc < 0 the missile has overshot; a negative Vc would invert the PN
            // correction and command away from the target.  At overshoot the gain simply
            // goes to zero (no further correction), letting the autopilot hold heading.
            double VcPN = Math.max(Vc, 0.0);

            // --- Missile flight-path angles ---
            double mHoriz = Math.sqrt(mVelX * mVelX + mVelZ * mVelZ);
            double mYaw   = Math.atan2(mVelX, mVelZ);
            double mPitch = Math.atan2(mVelY, mHoriz);

            // --- η_m: missile heading angle w.r.t. LOS (propnavpt.m: psi−los, theta−philos) ---
            double etaMYaw   = normalizeAngle(mYaw   - losYaw);
            double etaMPitch = mPitch - losPitch;
            // cos(η_m) — floor at 0.1 to avoid singularity when missile points away from LOS
            double cosEtaYaw   = Math.max(Math.cos(etaMYaw),   0.1);
            double cosEtaPitch = Math.max(Math.cos(etaMPitch), 0.1);

            // --- True PN command (propnavpt.m):
            //   cmdYaw   = ψ_m + N*V_c*σ̇_yaw   / (V * cos(ψ_m − σ))
            //   cmdPitch = γ_m + N*V_c*σ̇_pitch / (V * cos(γ_m − σ)) + g/V
            // The +g/V term counteracts gravity (equivalent to ZEM futMY correction). ---
            // Fix C: clamp each angular correction to ±90° to prevent guidance reversal.
            // A correction > 90° means the guidance is commanding the missile to turn
            // past perpendicular — physically unreachable in the remaining flight time
            // and indicative of a noisy/spurious filtered LOS rate.  Clamping ensures
            // the missile at least holds near its current heading rather than reversing.
            double yawCorr   = clamp(N * VcPN * filtLosRateYaw   / (V * cosEtaYaw),   -Math.PI / 2, Math.PI / 2);
            double pitchCorr = clamp(N * VcPN * filtLosRatePitch / (V * cosEtaPitch), -Math.PI / 2, Math.PI / 2);
            double cmdYaw   = mYaw   + yawCorr;
            // Fix D: Clamp cmdPitch to [-π/2, +π/2] to prevent past-vertical commands.
            // cmdPitch > π/2 causes cos(cmdPitch) < 0, which inverts the dx/dz components
            // of the direction vector: dx = sin(cmdYaw)*cos(cmdPitch) flips sign.
            // Result: thAngYaw = atan2(dx,dz) reverses ~180° — observed as thY=-89° when
            // losY=+90°.  Fix C (pitchCorr clamp to ±90°) was insufficient because:
            //   mPitch(63.5°) + pitchCorr(clamped 90°) = 153.5° — still exceeds π/2.
            // Clamping the TOTAL cmdPitch here guarantees cos(cmdPitch) ≥ 0 always.
            double cmdPitch = clamp(mPitch + pitchCorr + GRAVITY / V, -Math.PI / 2, Math.PI / 2);

            // --- Direction vector ---
            double cosCmdP = Math.cos(cmdPitch);
            double dx = Math.sin(cmdYaw) * cosCmdP;
            double dy = Math.sin(cmdPitch);
            double dz = Math.cos(cmdYaw) * cosCmdP;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-9) return new double[]{ rx, ry, rz };
            return new double[]{ dx / len, dy / len, dz / len };
        }
    }

    // -------------------------------------------------------------------------
    // GuidanceDiffGeo – Differential Geometry (DG) guidance law
    //
    // Based on diffgeo.m from ADA556639 companion code (Chaoyong Li, 2009).
    //
    // Extends GuidancePNLosRate with target-acceleration feedforward, giving
    // exact null miss distance against non-maneuvering targets and near-optimal
    // performance against maneuvering targets (SRBM boost, evasive UAV):
    //
    //   n_y = |a_T|·cos(η_T)  / cos(η_m)           + N·V_c·σ̇_yaw   / cos(η_m)
    //   n_z = a_Tz ·cos(η_Tz) / cos(γ_m − σ_pitch) + N·V_c·σ̇_pitch / cos(γ_m − σ) − g
    //
    // Where:
    //   η_T    = atan2(atX, atZ) − σ_yaw      (horizontal target accel vs LOS)
    //   η_Tz   = atan2(atY, |a_T_horiz|)       (vertical target accel elevation angle)
    //   η_m    = chiM − σ_yaw                  (missile look angle, horizontal)
    //   a_T    = IIR-filtered finite-difference of target velocity (τ = 0.5 s)
    //
    // Target acceleration is estimated by differentiating target velocity each tick
    // and smoothing with a first-order IIR filter (τ_acc = 0.5 s ≈ 10 ticks).
    // This achieves the same effect as the α-β-γ tracker in abgfilter.m but is
    // stable at DT = 0.05 s without re-tuning the filter coefficients.
    //
    // Cos singularity guard: max(|cos(η)|, 0.05) prevents division by near-zero
    // when the missile look angle approaches 90° (guidance barrier in diffgeo.m).
    //
    // Gravity compensation: +g/V in pitch channel (propnavpt.m convention:
    // nz −= 9.8045 maps to +GRAVITY/V in heading-angle space).
    //
    // Reference: diffgeo.m, abgfilter.m from ADA556639 companion code.
    // -------------------------------------------------------------------------
    public static class GuidanceDiffGeo extends EntityMissileBaseGuidance {
        private final double N;
        private final double tauFilter;

        // LOS rate state (same initialisation pattern as GuidancePNLosRate)
        private double prevLosYaw   = Double.NaN;
        private double prevLosPitch = Double.NaN;
        private double filtLosRateYaw   = 0.0;
        private double filtLosRatePitch = 0.0;

        // Target acceleration estimate: IIR-smoothed finite difference of tVel.
        // Seeded to NaN so the first tick skips the finite-diff step (no prevTVel).
        private double prevTVelX = Double.NaN;
        private double prevTVelY, prevTVelZ;
        private double filtAccX = 0.0, filtAccY = 0.0, filtAccZ = 0.0;

        // Smoothing time constant for target acceleration estimate.
        // 0.5 s = 10 ticks: fast enough to track rapid manoeuvres (1–2 s pull-up),
        // smooth enough to reject quantisation noise in Minecraft entity velocity.
        private static final double TAU_ACC = 0.50;

        /**
         * @param N          navigation gain (5 per ADA556639 optimal analysis)
         * @param tauFilter  LOS-rate IIR filter time constant (s);
         *                   0.10 for near terminal, 0.05 for endgame
         */
        public GuidanceDiffGeo(double N, double tauFilter) {
            this.N         = N;
            this.tauFilter = tauFilter;
        }

        @Override
        public void reset() {
            prevLosYaw       = Double.NaN;
            prevLosPitch     = Double.NaN;
            filtLosRateYaw   = 0.0;
            filtLosRatePitch = 0.0;
            prevTVelX        = Double.NaN;
            filtAccX = filtAccY = filtAccZ = 0.0;
        }

        @Override
        public double[] computeDirection(
                double mPosX, double mPosY, double mPosZ,
                double mVelX, double mVelY, double mVelZ,
                double tPosX, double tPosY, double tPosZ,
                double tVelX, double tVelY, double tVelZ,
                int age) {

            double rx = tPosX - mPosX;
            double ry = tPosY - mPosY;
            double rz = tPosZ - mPosZ;
            double dist = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (dist < 1.0) return new double[]{ rx, ry, rz };

            double V = Math.sqrt(mVelX * mVelX + mVelY * mVelY + mVelZ * mVelZ);
            if (V < 1.0) return new double[]{ rx, ry, rz };

            // ── Target acceleration estimate (abgfilter.m equivalent) ──────────
            // Finite-difference of target velocity smoothed by IIR (τ = TAU_ACC).
            // Raw accel capped at 15 G (≈ 147 m/s²) to reject quantisation spikes
            // that appear when a Minecraft entity updates its motionX/Y/Z abruptly.
            if (!Double.isNaN(prevTVelX)) {
                final double MAX_TACC = 147.0; // 15 G
                double rawAX = clamp((tVelX - prevTVelX) / DT, -MAX_TACC, MAX_TACC);
                double rawAY = clamp((tVelY - prevTVelY) / DT, -MAX_TACC, MAX_TACC);
                double rawAZ = clamp((tVelZ - prevTVelZ) / DT, -MAX_TACC, MAX_TACC);
                double aAlpha = 1.0 - Math.exp(-DT / TAU_ACC);
                filtAccX = aAlpha * rawAX + (1.0 - aAlpha) * filtAccX;
                filtAccY = aAlpha * rawAY + (1.0 - aAlpha) * filtAccY;
                filtAccZ = aAlpha * rawAZ + (1.0 - aAlpha) * filtAccZ;
            }
            prevTVelX = tVelX;  prevTVelY = tVelY;  prevTVelZ = tVelZ;

            // ── LOS angles ────────────────────────────────────────────────────
            double rh       = Math.sqrt(rx * rx + rz * rz);
            double losYaw   = Math.atan2(rx, rz);
            double losPitch = Math.atan2(ry, rh);

            // ── LOS angular rate (identical to GuidancePNLosRate) ─────────────
            double losRateYaw, losRatePitch;
            if (Double.isNaN(prevLosYaw)) {
                double dRx = tVelX - mVelX, dRy = tVelY - mVelY, dRz = tVelZ - mVelZ;
                losRateYaw   = (rz * dRx - rx * dRz) / Math.max(rh * rh, 1.0);
                double rhDotdR = (rh > 0.01) ? (rx * dRx + rz * dRz) / rh : 0.0;
                losRatePitch = (rh * dRy - ry * rhDotdR) / Math.max(dist * dist, 1.0);
            } else {
                losRateYaw   = normalizeAngle(losYaw   - prevLosYaw)   / DT;
                losRatePitch = (losPitch - prevLosPitch) / DT;
            }
            prevLosYaw   = losYaw;
            prevLosPitch = losPitch;

            // Cap raw LOS rate at 0.5 rad/s (≈ 28.6°/s) to reject noise spikes
            final double MAX_RAW_LOS_RATE = 0.5;
            losRateYaw   = clamp(losRateYaw,   -MAX_RAW_LOS_RATE, MAX_RAW_LOS_RATE);
            losRatePitch = clamp(losRatePitch, -MAX_RAW_LOS_RATE, MAX_RAW_LOS_RATE);

            double alpha = 1.0 - Math.exp(-DT / Math.max(tauFilter, DT * 0.5));
            filtLosRateYaw   = alpha * losRateYaw   + (1.0 - alpha) * filtLosRateYaw;
            filtLosRatePitch = alpha * losRatePitch + (1.0 - alpha) * filtLosRatePitch;

            // ── Closing velocity V_c (clamped to ≥ 0 for PN gain term) ───────
            // When Vc < 0 (missile overshot), gain goes to zero → natural saturation,
            // removing the "Rdot > 0 barrier" identified in thebigstop.m.
            double Vc   = (rx*(mVelX-tVelX) + ry*(mVelY-tVelY) + rz*(mVelZ-tVelZ)) / dist;
            double VcPN = Math.max(Vc, 0.0);

            // ── Missile flight-path angles ─────────────────────────────────────
            double mHoriz = Math.sqrt(mVelX * mVelX + mVelZ * mVelZ);
            double mYaw   = Math.atan2(mVelX, mVelZ);
            double mPitch = Math.atan2(mVelY, mHoriz);

            // ── DG look angles with cos singularity guard ─────────────────────
            // η_m (horizontal and vertical separately)
            double etaMYaw   = normalizeAngle(mYaw - losYaw);
            double etaMPitch = mPitch - losPitch;
            // Floor at 0.05 removes the cos → 0 "guidance barrier" from diffgeo.m
            double cosEtaYaw   = Math.max(Math.abs(Math.cos(etaMYaw)),   0.05);
            double cosEtaPitch = Math.max(Math.abs(Math.cos(etaMPitch)), 0.05);

            // ── DG feedforward: estimated target acceleration projected onto axes
            // Horizontal (diffgeo.m: norm(tgt_accel)*cos(eta_t)/cos(eta_m))
            double atHorizMag = Math.sqrt(filtAccX * filtAccX + filtAccZ * filtAccZ);
            double etaT       = normalizeAngle(Math.atan2(filtAccX, filtAccZ) - losYaw);
            double ffYaw      = atHorizMag * Math.cos(etaT) / cosEtaYaw;

            // Vertical (diffgeo.m: u(21)*cos(eta_tz)/cos(theta−philos))
            // eta_tz = atan2(atY, atHorizMag): angle of total accel above horizontal
            double etaTz  = Math.atan2(filtAccY, Math.max(atHorizMag, 0.01));
            double ffPitch = filtAccY * Math.cos(etaTz) / cosEtaPitch;

            // ── Full DG command (diffgeo.m Eq. ny, nz) ───────────────────────
            //   n_y = ffYaw   + N·V_c·σ̇_yaw   / cos(η_m)
            //   n_z = ffPitch + N·V_c·σ̇_pitch / cos(γ_m − σ)
            // (gravity compensation applied separately below as +g/V in angle space)
            double accelYaw   = ffYaw   + N * VcPN * filtLosRateYaw   / cosEtaYaw;
            double accelPitch = ffPitch + N * VcPN * filtLosRatePitch / cosEtaPitch;

            // ── Convert lateral acceleration to commanded heading angles ──────
            double yawCorr   = clamp(accelYaw   / V, -Math.PI / 2, Math.PI / 2);
            double pitchCorr = clamp(accelPitch / V, -Math.PI / 2, Math.PI / 2);
            double cmdYaw    = mYaw + yawCorr;
            // Gravity compensation: +g/V ≡ propnavpt.m's "nz −= 9.8045" in angle space.
            // Clamp total cmdPitch to ±π/2 to prevent past-vertical guidance reversal.
            double cmdPitch  = clamp(mPitch + pitchCorr + GRAVITY / V, -Math.PI / 2, Math.PI / 2);

            double cosCmdP = Math.cos(cmdPitch);
            double dx = Math.sin(cmdYaw) * cosCmdP;
            double dy = Math.sin(cmdPitch);
            double dz = Math.cos(cmdYaw) * cosCmdP;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-9) return new double[]{ rx, ry, rz };
            return new double[]{ dx / len, dy / len, dz / len };
        }
    }

    // =========================================================================
    // SHARED HELPERS: Palumbo (2010) ZEM-PN / ZEM-APN core
    // =========================================================================

    /**
     * Palumbo (2010) ZEM core — computes raw acceleration commands.
     *
     * Implements Palumbo Eq.17-20 via Zero-Effort-Miss:
     *
     *   V_c = -dR/dt = -(r̄·v̄_rel)/R            (Eq.17-18: closing velocity)
     *   t_go = R / V_c  (fallback: R/V if not closing)
     *
     *   Missile-frame axes (Palumbo guidance frame, Eq.12-13):
     *     1̂_pu (pitch-up): (-sinC·sinG,  cosG, -cosC·sinG)
     *     1̂_lt (left):     ( cosC,        0,   -sinC     )
     *
     *   Future missile position (gravity-corrected):
     *     futM = mPos + mVel·t_go - ½·g·t_go²  (Y axis)
     *
     *   Future target position (constant velocity model; APN adds ½·a_T·t_go²):
     *     futT = tPos + tVel·t_go [+ ½·tAccel·t_go²]
     *
     *   ZEM = (futT - futM) projected onto missile-frame axes
     *   a_cmd = N · ZEM / t_go²                  (equivalent to Eq.19-20)
     *
     * NOTE: The 30G pre-guidance clamp present in the previous version has been
     * removed.  The G structural limit is enforced exclusively by the autopilot
     * (EntityMissileBaseRealistic.updateAutopilot) which has access to the
     * missile's phase-specific getMaxG() value.  Clamping in the guidance
     * computer would mask large ZEM corrections at endgame.
     *
     * @param tAccel  target acceleration for APN (null for pure PN).
     *                ZEM_APN = ZEM_PN + ½·a_T·t_go² per Palumbo APN form.
     * @return double[7] = {a_pitch, a_yaw, gamma, chiM, V, cosG, t_go},
     *         or null if degenerate (V < 1 m/s or dist < 1 m)
     */
    /**
     * @param maxTgo  maximum t_go to use in ZEM computation (seconds).
     *                Pass 0.0 for no cap (terminal phases where t_go is already short).
     *                Midcourse (GuidanceAPG) uses 40.0 s to prevent the gravity term
     *                −½·g·t_go² from dominating: without cap t_go ≈ 90-700 s causes
     *                the missile to loft to 100+ km altitude instead of ~8-10 km.
     *                Equilibrium cruise altitude with cap T: Y_eq = Y_target + ½·g·T²
     *                → T=40s gives Y_eq ≈ 8 km above target (within SM-6 flight envelope).
     */
    private static double[] computeZemRaw(
            double mPosX, double mPosY, double mPosZ,
            double mVelX, double mVelY, double mVelZ,
            double tPosX, double tPosY, double tPosZ,
            double tVelX, double tVelY, double tVelZ,
            double N, double[] tAccel, double maxTgo) {

        double dx   = tPosX - mPosX;
        double dy   = tPosY - mPosY;
        double dz   = tPosZ - mPosZ;
        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        double V    = Math.sqrt(mVelX*mVelX + mVelY*mVelY + mVelZ*mVelZ);
        if (V < 1.0 || dist < 1.0) return null;

        // Closing velocity — for reference only (not used in t_go computation).
        // The quadratic intercept formula below accounts for target velocity correctly.
        double relVx = tVelX - mVelX, relVy = tVelY - mVelY, relVz = tVelZ - mVelZ;
        @SuppressWarnings("unused")
        double Vc   = -(dx * relVx + dy * relVy + dz * relVz) / dist;

        // ── Fix G: Quadratic intercept t_go — accounts for target velocity ─────────────
        //
        // Previous Jiang Eq.29 formula used  t_go = (dist/V_M) × PN-path-corrections,
        // which models a STATIONARY target.  It completely ignores the target's own
        // approach velocity.  In head-on geometry (missile ~1520 m/s east, target
        // ~748 m/s west, Vc_total ~2200 m/s):
        //
        //   Jiang: t_go = dist / V_M ≈ 6481/1521 = 4.27 s   ← ignores target motion
        //   True:  dist / Vc_total   ≈ 6481/2200  = 2.95 s   ← correct
        //   ZEM gain error: (4.27/2.95)² ≈ 2.1×  →  guidance commands only ~47% of
        //   the required acceleration → ZEM grows +4 m/tick → 4350 m miss at closest approach
        //
        // Quadratic intercept formula: solve |r + tVel·t|² = V²·t² for first positive t
        //   (V²−tV²)·t² − 2·(r·tVel)·t − dist² = 0
        //
        // This correctly handles all encounter geometries:
        //   head-on  (V_T closing):  shorter t_go  ✓
        //   pursuit  (V_T receding): longer  t_go  ✓
        //   crossing (V_T ⊥ LOS):   quadratic root ✓
        //   σ_M = 90° (no Vc):      quadratic root ✓  (the old dist/Vc singularity is gone)
        double tV2    = tVelX*tVelX + tVelY*tVelY + tVelZ*tVelZ;
        double rdottv = dx*tVelX + dy*tVelY + dz*tVelZ;
        double a_tgo  = V*V - tV2;
        double b_tgo  = -2.0 * rdottv;
        double c_tgo  = -(dist * dist);
        double t_go;
        if (Math.abs(a_tgo) < 1.0) {
            // V ≈ tV: collapses to linear  b·t + c = 0
            t_go = (Math.abs(b_tgo) > 1.0e-9) ? -c_tgo / b_tgo : dist / V;
        } else {
            double disc_tgo = b_tgo * b_tgo - 4.0 * a_tgo * c_tgo;
            if (disc_tgo < 0.0) {
                // No real intercept (target faster than missile and flying away)
                t_go = dist / V;
            } else {
                t_go = (-b_tgo + Math.sqrt(disc_tgo)) / (2.0 * a_tgo);
                if (t_go < 0.0) t_go = (-b_tgo - Math.sqrt(disc_tgo)) / (2.0 * a_tgo);
                if (t_go < 0.0) t_go = dist / V;   // Both roots negative: diverging
            }
        }
        // Floor at 2×DT (2 simulation ticks = 0.10 s).
        // The original 0.28 s floor (SM-6 real-world τ_seeker+τ_FC+τ_A) was too large:
        // in head-on geometry the quadratic formula gives t_go < 0.28 s from ~560 m
        // range onward, so the floor froze the gain and guidance commands shrank as
        // ZEM decreased — the missile under-corrected in the final frames and missed.
        // The ±π/2 dgamma clamp in zemAccelToDirection already prevents unrealistically
        // large angle commands; this floor only guards against division-by-zero (t_go→0).
        // 2 ticks is the minimum physically meaningful look-ahead in this simulation.
        if (t_go < 2.0 * DT) t_go = 2.0 * DT;
        // Optional cap: prevents -½·g·t_go² gravity term from overwhelming the ZEM
        // at long range during midcourse. At t_go=90s: gravity term = -39.7 km;
        // capped at 40s: -7.8 km → equilibrium cruise altitude ~8 km above target.
        if (maxTgo > 0.0 && t_go > maxTgo) t_go = maxTgo;

        // Missile flight-path angles
        double gamma = Math.atan2(mVelY, Math.sqrt(mVelX*mVelX + mVelZ*mVelZ));
        double chiM  = Math.atan2(mVelX, mVelZ);
        double cosG  = Math.cos(gamma), sinG = Math.sin(gamma);
        double sinC  = Math.sin(chiM),  cosC = Math.cos(chiM);

        // Palumbo guidance frame axes (Eq.12-13, LOS coordinate system):
        //   1̂_pu (pitch-up axis):  perpendicular to velocity in vertical plane
        //   1̂_lt (left axis):      perpendicular to velocity in horizontal plane
        double puX = -sinC * sinG, puY = cosG, puZ = -cosC * sinG;
        double ltX =  cosC,        ltY = 0.0,  ltZ = -sinC;

        // Future missile position under gravity (Palumbo: gravity correction in ZEM)
        double futMX = mPosX + mVelX * t_go;
        double futMY = mPosY + mVelY * t_go - 0.5 * GRAVITY * t_go * t_go;
        double futMZ = mPosZ + mVelZ * t_go;

        // Future target position (constant velocity; APN adds ½·a_T·t_go²)
        double futTX = tPosX + tVelX * t_go;
        double futTY = tPosY + tVelY * t_go;
        double futTZ = tPosZ + tVelZ * t_go;

        // APN correction: ZEM_APN = ZEM_PN + ½·a_T·t_go²  (Palumbo general APN form)
        if (tAccel != null) {
            double half_tgo2 = 0.5 * t_go * t_go;
            futTX += tAccel[0] * half_tgo2;
            futTY += tAccel[1] * half_tgo2;
            futTZ += tAccel[2] * half_tgo2;
        }

        // ZEM vector projected onto missile-frame axes
        double zemX     = futTX - futMX, zemY = futTY - futMY, zemZ = futTZ - futMZ;
        double zem_pitch = zemX * puX + zemY * puY + zemZ * puZ;
        double zem_yaw   = zemX * ltX + zemY * ltY + zemZ * ltZ;

        // Raw acceleration commands (no pre-guidance clamp — G-limit in autopilot)
        double a_pitch = N * zem_pitch / (t_go * t_go);
        double a_yaw   = N * zem_yaw   / (t_go * t_go);

        return new double[]{ a_pitch, a_yaw, gamma, chiM, V, cosG, t_go };
    }

    /**
     * Converts ZEM acceleration commands to a guidance direction vector.
     *
     * Uses the natural PN gain schedule K = t_go / N (Palumbo optimal gain).
     * This maps the lateral acceleration command to a commanded flight-path
     * angle change over the look-ahead interval K:
     *
     *   Δγ_cmd = a_pitch · K / V     (pitch angle change in t_go/N seconds)
     *   Δχ_cmd = a_yaw   · K / (V · cosγ)
     *
     * The autopilot (with τ_FC lag and G-limit) drives the body toward this
     * direction.  No arbitrary T_LOOKAHEAD cap is applied: the natural K = t_go/N
     * gives large corrections at early flight (when t_go is large, so is K, so
     * small acceleration commands produce large direction changes), and small
     * corrections at endgame (small t_go, small K, prevents overcorrection).
     * The G-limit in the autopilot prevents structural overstress throughout.
     *
     * @param a_pitch pitch-axis acceleration command (m/s²) — after guidance filter
     * @param a_yaw   yaw-axis  acceleration command (m/s²) — after guidance filter
     * @param gamma   missile elevation angle (rad)
     * @param chiM    missile azimuth angle   (rad)
     * @param V       missile speed (m/s)
     * @param cosG    cos(gamma)
     * @param t_go    time-to-go (s)
     * @param N       navigation gain
     */
    protected static double[] zemAccelToDirection(
            double a_pitch, double a_yaw,
            double gamma,   double chiM,
            double V,       double cosG,
            double t_go,    double N) {
        // K = t_go / N: natural PN optimal look-ahead gain (no arbitrary cap)
        double K = t_go / N;

        // Fix E-2: Clamp each angular increment to ±π/2 (±90°).
        // At endgame t_go can be as small as 2×DT (floor) and ZEM can be large
        // (hundreds of metres), so a_pitch·K/V can exceed 2π without this clamp.
        // An unclamped Δγ wraps gamma_cmd past ±180°, making dy = sin(gamma_cmd)
        // negative (missile commanded to dive) — physically catastrophic.
        //
        // Δγ = ±90° allows the missile to turn from horizontal (γ=0) all the way to
        // vertical (γ=90°) in a single guidance step.  For a near-vertical missile
        // (γ≈78°) a Δγ of +26° is typical and well inside the ±90° limit; when the
        // limit triggers it simply prevents the worst-case extreme-range ZEM blowup.
        //
        // NOTE: gamma_cmd > 90° is intentional and correct for head-on geometry —
        // e.g. at γ=78°, gamma_cmd=104° gives dx = sin(chi)·cos(104°) < 0 (westward)
        // when the target's future intercept position is to the west.  The ±90° Δγ
        // clamp preserves this behaviour while blocking absolute wrap-around.
        double dgamma    = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, a_pitch * K / V));
        double gamma_cmd = gamma + dgamma;
        double chi_cmd   = chiM  + a_yaw   * K / (V * Math.max(cosG, 0.05));
        double cosP      = Math.cos(gamma_cmd);
        return new double[]{
                Math.sin(chi_cmd) * cosP,
                Math.sin(gamma_cmd),
                Math.cos(chi_cmd) * cosP
        };
    }

    /**
     * Convenience wrapper: computes ZEM PN direction without external filter.
     * Used by GuidanceTURN and any guidance class that does not maintain filter state.
     */
    protected static double[] computeZemPN(
            double mPosX, double mPosY, double mPosZ,
            double mVelX, double mVelY, double mVelZ,
            double tPosX, double tPosY, double tPosZ,
            double tVelX, double tVelY, double tVelZ,
            double N, double[] tAccel) {
        double[] raw = computeZemRaw(
                mPosX, mPosY, mPosZ, mVelX, mVelY, mVelZ,
                tPosX, tPosY, tPosZ, tVelX, tVelY, tVelZ,
                N, tAccel, 0.0);
        if (raw == null) return null;
        return zemAccelToDirection(raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6], N);
    }

    // =========================================================================
    // SHARED UTILITIES
    // =========================================================================

    /**
     * Gaussian elimination with partial pivoting for a 4×4 system A·x = b.
     * Returns the solution vector, or null if singular.
     */
    public static double[] solve4x4(double[][] A, double[] b) {
        int n = 4;
        double[][] aug = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) aug[i][j] = A[i][j];
            aug[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int pivotRow = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(aug[row][col]) > Math.abs(aug[pivotRow][col])) pivotRow = row;
            }
            double[] tmp = aug[col]; aug[col] = aug[pivotRow]; aug[pivotRow] = tmp;
            if (Math.abs(aug[col][col]) < 1e-12) return null;
            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double factor = aug[row][col] / aug[col][col];
                for (int k = col; k <= n; k++) aug[row][k] -= factor * aug[col][k];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = aug[i][n] / aug[i][i];
        return x;
    }

    protected static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    protected static double normalizeAngle(double a) {
        while (a >  Math.PI) a -= 2.0 * Math.PI;
        while (a < -Math.PI) a += 2.0 * Math.PI;
        return a;
    }
}
