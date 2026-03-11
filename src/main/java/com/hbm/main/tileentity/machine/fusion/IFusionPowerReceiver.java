package com.hbm.main.tileentity.machine.fusion;

/**
 * Interface for tile entities that can receive fusion power and neutron flux
 * from the Plasma network.
 *
 * Implementing classes:
 * - TileEntityFusionBoiler (uses fusion power, ignores neutrons)
 * - TileEntityFusionMHDT (uses fusion power, ignores neutrons)
 * - TileEntityFusionBreeder (uses neutrons, ignores fusion power)
 * - TileEntityFusionCoupler (converts fusion power back to klystron energy)
 * - TileEntityFusionCollector (passive bonus provider)
 *
 * @author Adapted for 1.12.2
 */
public interface IFusionPowerReceiver {

	/**
	 * Determines if this receiver should receive fusion power.
	 * Return false for receivers that only use neutron flux (e.g., Breeder).
	 *
	 * @return True if this receiver accepts fusion power
	 */
	public boolean receivesFusionPower();

	/**
	 * Called by the Fusion Torus to deliver plasma power to this receiver.
	 *
	 * UPDATED: Now uses physics-based units (W, K) instead of legacy TU units.
	 *
	 * @param thermalPowerWatts The fusion thermal power (in Watts)
	 * @param plasmaTemperatureK The plasma temperature (in Kelvin) - for MHD efficiency calculation
	 * @param neutronFlux The neutron flux for breeding/transmutation (n/s scaled)
	 */
	public void receiveFusionPower(double thermalPowerWatts, double plasmaTemperatureK, double neutronFlux);
}
