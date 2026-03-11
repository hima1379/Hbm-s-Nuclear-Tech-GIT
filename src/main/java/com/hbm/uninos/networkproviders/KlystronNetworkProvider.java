package com.hbm.uninos.networkproviders;

import com.hbm.uninos.INetworkProvider;

/**
 * Provider for Klystron networks.
 * Uses singleton pattern to ensure all Klystron nodes use the same provider instance.
 *
 * @author Adapted for 1.12.2
 */
public class KlystronNetworkProvider implements INetworkProvider<KlystronNetwork> {

	/** Singleton instance used by all Klystron nodes */
	public static final KlystronNetworkProvider THE_PROVIDER = new KlystronNetworkProvider();

	@Override
	public KlystronNetwork provideNetwork() {
		return new KlystronNetwork();
	}
}
