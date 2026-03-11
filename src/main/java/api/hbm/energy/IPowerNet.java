package api.hbm.energy;

import java.util.List;

/**
 * Not mandatory to use, but making your cables IPowerNet-compliant will allow them to connect to NTM cables.
 * Cables will still work without it as long as they implement IEnergyConductor (or even IEnergyConnector) + self-built network code
 * @author hbm
 */
public interface IPowerNet {

	public void joinNetworks(IPowerNet network);

	public IPowerNet joinLink(IEnergyConductor conductor);
	public void leaveLink(IEnergyConductor conductor);

	public void subscribe(IEnergyConnector connector);
	public void unsubscribe(IEnergyConnector connector);
	public boolean isSubscribed(IEnergyConnector connector);

	public void destroy();

	/**
	 * When a link is removed, instead of destroying the network, causing it to be recreated from currently loaded conductors,
	 * we re-evaluate it, creating new nets based on the previous links.
	 */
	public void reevaluate();

	public boolean isValid();

	public List<IEnergyConductor> getLinks();
	public List<IEnergyConnector> getSubscribers();

	public long transferPower(long power);
	public long getTotalTransfer();

	// ===== EnergyValue-based methods (for BigInteger support) =====

	/**
	 * Transfer power using EnergyValue (supports values beyond long range)
	 * Default implementation converts to/from long for backward compatibility
	 * @param power The amount of power to transfer
	 * @return The amount of power that could not be transferred (overshoot)
	 */
	public default EnergyValue transferPowerEV(EnergyValue power) {
		// Default implementation: convert to long, call existing method, convert back
		long overshoot = this.transferPower(power.toLongClamped());
		return EnergyValue.of(overshoot);
	}

	/**
	 * Get total transfer as EnergyValue
	 * Default implementation uses existing getTotalTransfer() method
	 * @return Total power transferred
	 */
	public default EnergyValue getTotalTransferEV() {
		return EnergyValue.of(this.getTotalTransfer());
	}
}
