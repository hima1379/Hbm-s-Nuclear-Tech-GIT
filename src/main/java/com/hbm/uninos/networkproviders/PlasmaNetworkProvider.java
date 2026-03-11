package com.hbm.uninos.networkproviders;

import com.hbm.uninos.INetworkProvider;

/**
 * Provider for Plasma networks.
 * Uses singleton pattern to ensure all Plasma nodes use the same provider instance.
 *
 * @author Adapted for 1.12.2
 */
public class PlasmaNetworkProvider implements INetworkProvider<PlasmaNetwork> {

	/** Singleton instance used by all Plasma nodes */
	public static final PlasmaNetworkProvider THE_PROVIDER = new PlasmaNetworkProvider();

	@Override
	public PlasmaNetwork provideNetwork() {
		return new PlasmaNetwork();
	}
}
