package com.hbm.physics.air;

import java.util.Random;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;

/**
 * Per-world wind state used by the Gaussian plume atmospheric dispersion system.
 *
 * Wind is characterised by:
 *   - Speed [m/s]            : sampled from a realistic range per weather state
 *   - Direction [degrees]    : 0 = North (+Z), 90 = East (+X), measured clockwise
 *   - Stability class        : Pasquill-Gifford A–G
 *   - Effective rainfall     : mm/h proxy for Minecraft's rain/thunder states
 *
 * The wind field evolves by a slow random walk every server second so plumes
 * experience realistic wind shear over time.  The full state is persisted via
 * WorldSavedData so offline time is handled correctly.
 *
 * Time convention: all wall-clock times use System.currentTimeMillis().
 */
public class WindField extends WorldSavedData {

    private static final String DATA_NAME = "hbmWindField";

    // -----------------------------------------------------------------
    // Wind state
    // -----------------------------------------------------------------

    /** Mean horizontal wind speed [m/s] at 10 m height. */
    public double windSpeedMs = 3.0;

    /** Wind direction [degrees, clockwise from North (+Z)]. */
    public double windDirectionDeg = 225.0;   // SW → NE, typical mid-latitude

    /** Current Pasquill-Gifford atmospheric stability class. */
    public AtmosphericStabilityClass stabilityClass = AtmosphericStabilityClass.D;

    /**
     * Effective rainfall rate [mm/h].
     * 0 = dry.  ≈ 2.5 mm/h for light rain, ≈ 7 mm/h for moderate rain.
     */
    public double rainfallMmPerHour = 0.0;

    /** Wall-clock timestamp of the last wind update [ms since epoch]. */
    public long lastUpdateMs = 0L;

    // -----------------------------------------------------------------
    // Turbulence / random-walk parameters
    // -----------------------------------------------------------------

    /**
     * Maximum direction change per second [degrees/s].
     * Represents realistic synoptic wind backing/veering.
     */
    private static final double MAX_DIR_CHANGE_PER_SEC = 0.3;

    /**
     * Maximum speed change per second [m/s per second].
     */
    private static final double MAX_SPD_CHANGE_PER_SEC = 0.05;

    // -----------------------------------------------------------------
    // Weather-driven speed ranges [m/s]
    // -----------------------------------------------------------------
    private static final double SPEED_CLEAR_MIN    = 1.0;
    private static final double SPEED_CLEAR_MAX    = 6.0;
    private static final double SPEED_RAIN_MIN     = 2.5;
    private static final double SPEED_RAIN_MAX     = 9.0;
    private static final double SPEED_THUNDER_MIN  = 5.0;
    private static final double SPEED_THUNDER_MAX  = 14.0;

    private static final Random rng = new Random();

    // -----------------------------------------------------------------
    // WorldSavedData boilerplate
    // -----------------------------------------------------------------

    public WindField() {
        super(DATA_NAME);
    }

    public WindField(String name) {
        super(name);
    }

    /**
     * Retrieves (or creates) the WindField for the given world.
     * Only valid on the server side.
     *
     * Wind direction is unified globally: the WindField is always stored on
     * the overworld (dimension 0), so every dimension (Nether, End, etc.)
     * shares the same wind state.  This is physically realistic — synoptic
     * winds operate at planetary scale and do not change per-dimension.
     */
    public static WindField get(World world) {
        // Always delegate to the overworld's per-world storage so that all
        // dimensions read and evolve the same WindField instance.
        if (world.provider.getDimension() != 0) {
            net.minecraft.world.WorldServer overworld =
                    net.minecraftforge.common.DimensionManager.getWorld(0);
            if (overworld != null) world = overworld;
        }
        WindField inst = (WindField) world.getPerWorldStorage()
                .getOrLoadData(WindField.class, DATA_NAME);
        if (inst == null) {
            inst = new WindField();
            inst.windDirectionDeg = world.rand.nextFloat() * 360.0;
            inst.windSpeedMs      = 2.0 + world.rand.nextFloat() * 3.0;
            inst.lastUpdateMs     = System.currentTimeMillis();
            world.getPerWorldStorage().setData(DATA_NAME, inst);
            inst.markDirty();
        }
        return inst;
    }

    // -----------------------------------------------------------------
    // Update
    // -----------------------------------------------------------------

    /**
     * Advances the wind state for the given world.
     * Should be called once per server second (every 20 game ticks).
     *
     * @param world The world whose weather state is queried.
     */
    public void update(World world) {
        long nowMs    = System.currentTimeMillis();
        long elapsedMs = lastUpdateMs > 0L ? (nowMs - lastUpdateMs) : 1000L;
        lastUpdateMs  = nowMs;
        double dt     = Math.max(elapsedMs / 1000.0, 0.0);

        boolean thundering = world.isThundering();
        boolean raining    = world.isRaining();
        boolean daytime    = world.isDaytime();

        // Determine target speed range from weather
        double minSpeed, maxSpeed;
        if (thundering) {
            minSpeed = SPEED_THUNDER_MIN;
            maxSpeed = SPEED_THUNDER_MAX;
            rainfallMmPerHour = 7.0 + world.rand.nextFloat() * 5.0;
        } else if (raining) {
            minSpeed = SPEED_RAIN_MIN;
            maxSpeed = SPEED_RAIN_MAX;
            rainfallMmPerHour = 1.5 + world.rand.nextFloat() * 3.5;
        } else {
            minSpeed = SPEED_CLEAR_MIN;
            maxSpeed = SPEED_CLEAR_MAX;
            rainfallMmPerHour = 0.0;
        }

        // Nudge wind speed towards its weather-appropriate range
        if (windSpeedMs < minSpeed) {
            windSpeedMs = Math.min(windSpeedMs + MAX_SPD_CHANGE_PER_SEC * dt, minSpeed);
        } else if (windSpeedMs > maxSpeed) {
            windSpeedMs = Math.max(windSpeedMs - MAX_SPD_CHANGE_PER_SEC * dt, maxSpeed);
        } else {
            // Random walk within range
            double delta = (rng.nextDouble() * 2.0 - 1.0) * MAX_SPD_CHANGE_PER_SEC * dt;
            windSpeedMs = Math.max(minSpeed, Math.min(maxSpeed, windSpeedMs + delta));
        }

        // Random walk on direction
        double dirDelta = (rng.nextDouble() * 2.0 - 1.0) * MAX_DIR_CHANGE_PER_SEC * dt;
        windDirectionDeg = (windDirectionDeg + dirDelta + 360.0) % 360.0;

        // Update stability class
        stabilityClass = AtmosphericStabilityClass.determine(
                windSpeedMs, daytime, raining || thundering);

        markDirty();
    }

    // -----------------------------------------------------------------
    // Convenience accessors
    // -----------------------------------------------------------------

    /**
     * Decomposes wind into world-X and world-Z components.
     * Wind blowing at direction θ (clockwise from North):
     *   u_x = windSpeed * sin(θ)
     *   u_z = windSpeed * cos(θ)
     *
     * @return double[2] = { u_x [m/s], u_z [m/s] }
     */
    public double[] getComponents() {
        double rad = Math.toRadians(windDirectionDeg);
        return new double[] { windSpeedMs * Math.sin(rad),
                              windSpeedMs * Math.cos(rad) };
    }

    // -----------------------------------------------------------------
    // NBT persistence
    // -----------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        windSpeedMs      = nbt.getDouble("windSpeed");
        windDirectionDeg = nbt.getDouble("windDir");
        stabilityClass   = AtmosphericStabilityClass.fromNBT(nbt.getInteger("stabClass"));
        rainfallMmPerHour = nbt.getDouble("rainfall");
        lastUpdateMs     = nbt.getLong("lastUpdate");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setDouble("windSpeed",  windSpeedMs);
        nbt.setDouble("windDir",    windDirectionDeg);
        nbt.setInteger("stabClass", stabilityClass.toNBT());
        nbt.setDouble("rainfall",   rainfallMmPerHour);
        nbt.setLong("lastUpdate",   lastUpdateMs);
        return nbt;
    }
}
