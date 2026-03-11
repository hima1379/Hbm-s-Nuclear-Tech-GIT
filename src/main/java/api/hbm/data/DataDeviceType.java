package api.hbm.data;

/**
 * Types of devices that can connect to the DataNet network
 */
public enum DataDeviceType {
    /** Phased array radar system */
    RADAR_PHASED_ARRAY("Phased Array Radar"),

    /** Fire control radar */
    RADAR_FIRE_CONTROL("Fire Control Radar"),

    /** Missile launcher */
    MISSILE_LAUNCHER("Missile Launcher"),

    /** Command and control console */
    FCS_CONSOLE("FCS Console"),

    /** Data transmission cable */
    CABLE("Data Cable"),

    /** Generic data device */
    GENERIC("Generic Device");

    private final String displayName;

    DataDeviceType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
