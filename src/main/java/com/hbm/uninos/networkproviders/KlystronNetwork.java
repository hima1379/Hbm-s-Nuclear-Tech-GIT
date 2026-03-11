package com.hbm.uninos.networkproviders;

import com.hbm.uninos.NodeNet;

/**
 * Network for Klystron energy input system.
 * This network carries energy from Klystron generators to the Fusion Torus.
 * The actual energy transfer is handled directly by the tiles, so update() is empty.
 *
 * @author Adapted for 1.12.2
 */
public class KlystronNetwork extends NodeNet {

	@Override
	public void update() {
		// Energy transfer handled directly by tile entities
		// Network only serves to connect klystrons to the torus
	}
}
