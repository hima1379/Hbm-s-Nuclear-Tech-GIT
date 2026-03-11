package com.hbm.world.biome;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

/**
 * Nuclear Crater Biome
 *
 * Forced onto terrain within the apparent-crater radius after a nuclear
 * ground burst (EntityFalloutRain.forceCraterBiome).
 *
 * Physical basis:
 *  - Temperature 2.0 / rainfall 0.0 reflect the scorched, sterile wasteland
 *    left by the fireball (Glasstone & Dolan §2.11, §2.16).
 *  - All natural entity spawn lists are empty: the ionising radiation dose
 *    rate inside the crater far exceeds lethal levels for any biology.
 *    (Glasstone & Dolan §9.107: dose > 700 R in 96 h = majority fatality).
 *  - Spawn eggs and programmatic world.spawnEntity() calls bypass
 *    LivingSpawnEvent.CheckSpawn, so they still work for intentional spawning.
 *
 * Grass and foliage colour:
 *  - 0x5C4D3C : ashen grey-brown (irradiated, desiccated soil)
 *  - 0x3D3028 : dark brown (burnt organic matter)
 */
public class BiomeCrater extends Biome {

    public BiomeCrater() {
        super(new BiomeProperties("Nuclear Crater")
                .setTemperature(2.0f)
                .setRainfall(0.0f)
                .setBaseHeight(0.1f)
                .setHeightVariation(0.05f));

        // Clear all natural spawn lists — nothing should spawn here.
        // LivingSpawnEvent.CheckSpawn (registered in MainRegistry) enforces
        // this at runtime even if other code adds entries.
        this.spawnableCreatureList.clear();
        this.spawnableMonsterList.clear();
        this.spawnableWaterCreatureList.clear();
        this.spawnableCaveCreatureList.clear();
    }

    /**
     * Ashen grey-brown: irradiated, completely desiccated topsoil.
     * RGB 0x5C4D3C.
     */
    @Override
    public int getGrassColorAtPos(BlockPos pos) {
        return 0x5C4D3C;
    }

    /**
     * Dark charcoal brown: thermally carbonised vegetation residue.
     * RGB 0x3D3028.
     */
    @Override
    public int getFoliageColorAtPos(BlockPos pos) {
        return 0x3D3028;
    }
}
