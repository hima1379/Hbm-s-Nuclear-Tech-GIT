package com.hbm.uninos.networkproviders;

import com.hbm.uninos.NodeNet;

/**
 * Network for Plasma output distribution system.
 * This network carries plasma energy and neutron flux from the Fusion Torus
 * to all connected receivers (Boiler, MHDT, Breeder, Coupler, Collector).
 * The actual distribution is handled directly by the Torus, so update() is empty.
 *
 * @author Adapted for 1.12.2
 */
public class PlasmaNetwork extends NodeNet {

	@Override
	public void update() {
		// Plasma distribution handled directly by the Fusion Torus
		// Network only serves to connect receivers to the torus
	}
}
