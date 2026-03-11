package com.hbm.handler;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.hbm.items.ModItems;
import com.hbm.items.machine.ItemRBMKRod;
import com.hbm.main.MainRegistry;
import com.hbm.main.tileentity.machine.rbmk.TileEntityRBMKBase;
import com.hbm.main.tileentity.machine.rbmk.TileEntityRBMKRod;
import com.hbm.physics.air.PlumeSource;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * Computes and registers Gaussian plume atmospheric contamination sources
 * when an RBMK reactor undergoes a meltdown.
 *
 * -----------------------------------------------------------------------
 * Physical model
 *
 *   The source term Q [game_rad·m²] contributed by each fuel rod is:
 *
 *     Q_rod = rod.yield × enrichment × BASELINE_SOURCE_PER_YIELD × relativeActivity
 *
 *   where:
 *     rod.yield             – total neutron-flux capacity (proxy for fissile
 *                             mass inventory); all rods ship with 1×10⁸ units
 *     enrichment [0, 1]     – remaining fuel fraction (ItemRBMKRod.getEnrichment)
 *     BASELINE_SOURCE_PER_YIELD
 *                           – converts yield units to game_rad·m²; calibrated
 *                             so a fully-enriched MEU rod yields ~11 000
 *                             game_rad·m² — comparable to the Chernobyl source
 *                             term scaled to game coordinates (1 block = 1 m)
 *     relativeActivity      – isotope-specific multiplier encoding radiotoxicity
 *                             and volatile release fraction relative to MEU = 1.0
 *
 * -----------------------------------------------------------------------
 * Release phases
 *
 *   Source A — initial explosion / fire-lofting puff (~600 s):
 *     Q_puff = Σ Q_rod  (full instantaneous release)
 *     H_eff  = 30 + reactorHalfSpan × 4 + avgCoreHeat/40  (clamped 30–250 m)
 *
 *   Source B — sustained corium / graphite-fire emission (3 real-world days):
 *     Q_fire = Q_puff × 0.30  (30 % of initial inventory released slowly)
 *     H_eff  = 10 m  (ground-level corium pool convection)
 *
 * -----------------------------------------------------------------------
 * Isotope physics basis for relativeActivity
 *
 *   UO₂ (UEU/MEU/HEU) — Chernobyl-like 3.5 % volatile release fraction.
 *   Pu  fuels          — 8–12 % release; insoluble PuO₂ stays aerosolised longer.
 *   Am  fuels          — 15 % release; Am-241 specific activity = 3.4 Ci/g (extreme α).
 *   Neutron sources    — small fissile inventory but Po-210 ≈ 4 500 Ci/g.
 *   Fictional fuels    — scaled to game narrative; balefire is the extreme end.
 *
 * Reference: INSAG-7 (1992); UNSCEAR 2008 Annex D.
 */
public final class RBMKMeltdownContaminationHandler {

    private RBMKMeltdownContaminationHandler() {}

    // -----------------------------------------------------------------
    // Tuning constants
    // -----------------------------------------------------------------

    /**
     * Converts rod.yield to game_rad·m² for the initial puff source term.
     *
     * Calibration (revised — ×100 from original to produce visible in-game contamination):
     *   MEU rod, yield = 1×10⁸, enrichment = 1.0, relativeActivity = 1.0
     *   → Q_rod = 1×10⁸ × 1.0 × 1.1×10⁻² × 1.0 = 1,100,000 game_rad·m²
     *
     *   For a 10-rod MEU reactor (Q_puff = 1.1×10⁷ game_rad·m²), puff duration 600 s:
     *   Q_rate = 18,300 game_rad·m²/s
     *
     *   At H=30 m, stability D, u=3 m/s, x=500 m (σy≈39 m, σz≈30 m):
     *     χ/Q ≈ exp(-900/1800)/(π·3·39·30) ≈ 5.5×10⁻⁵ m⁻²
     *     rate ≈ 18,300 × 5.5×10⁻⁵ ≈ 1 game_rad/s → 600 game_rad total at 500 m
     *
     *   Game balance is intentionally NOT considered (user directive);
     *   this models Chernobyl-scale fallout at full realism.
     */
    private static final double BASELINE_SOURCE_PER_YIELD = 1.1e-2;

    /** Duration of the initial explosion / fire-lofting puff [s]. */
    private static final double PUFF_DURATION_S = 600.0;

    /**
     * Duration of the sustained corium / graphite-fire emission [s].
     * 3 real-world days ≈ RBMK-4 graphite fire (Chernobyl burned ~10 days;
     * 3 days is chosen as a game-practical balance between realism and server load).
     */
    private static final double CORIUM_DURATION_S = 3.0 * 86400.0;

    /**
     * Fraction of puff source term released by the sustained corium fire.
     * Chernobyl: initial explosion ≈ 3.5 % of inventory; fire phase ≈ 1–2 %.
     * Scaled to give 30 % of puff total spread over 3 days → a meaningful
     * but lower sustained emission.
     */
    private static final double CORIUM_FRACTION = 0.30;

    /** Effective height of the corium-fire continuous source [m]. */
    private static final double CORIUM_HEIGHT_M = 10.0;

    // -----------------------------------------------------------------
    // Per-fuel relative atmospheric source-term strength
    // -----------------------------------------------------------------

    /**
     * Multiplier on the baseline MEU source term per unit yield burned.
     * Encodes isotope-specific radiotoxicity × volatile release fraction.
     *
     * Key:
     *   1.0 = MEU / UO₂ baseline (3.5 % volatile release, Chernobyl-calibrated)
     *   Values > 1 indicate greater airborne contamination per fuel unit.
     */
    private static final Map<Item, Double> RELATIVE_ACTIVITY = new HashMap<>();

    static {

        // ----------------------------------------------------------------
        // Uranium-oxide fuels  (release fraction ~3.5 %, like Chernobyl)
        // ----------------------------------------------------------------

        // Natural U: low enrichment, lower fission-product inventory
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_ueu,    0.8);

        // Standard enriched UO₂ — calibration reference
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_meu,    1.0);

        // U-233 fission chain produces Pa-233 / Th-229 daughters, more radiotoxic
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_heu233, 1.4);

        // Highly enriched U-235: denser fission-product spectrum
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_heu235, 1.2);

        // Thorium-MEU cycle: produces Ra-228 / Ac-228 decay chain daughters
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_thmeu,  1.3);

        // ----------------------------------------------------------------
        // MOX fuel  (release fraction ~5 %; elevated Pu content)
        // ----------------------------------------------------------------

        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_mox,    1.5);

        // ----------------------------------------------------------------
        // Plutonium fuels  (release fraction 8–12 %)
        //   PuO₂ is insoluble; particles stay aerosolised much longer than UO₂.
        //   Even nanogram amounts exceed ICRP ingestion dose limits.
        // ----------------------------------------------------------------

        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_lep,    2.0);  // Low-enriched Pu
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_mep,    2.5);  // Medium-enriched Pu
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_hep239, 3.0);  // Weapons-grade Pu-239
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_hep241, 3.5);  // Pu-241 → Am-241 daughters

        // ----------------------------------------------------------------
        // Americium fuels  (release fraction ~15 %)
        //   Am-241: specific activity 3.4 Ci/g — extreme α-emitter.
        //   Am-242: t½ = 141 y but also decays via Am-242m (t½ = 141 y),
        //           highest specific activity in this group.
        // ----------------------------------------------------------------

        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_lea,    4.0);
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_mea,    5.0);
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_hea241, 5.5);
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_hea242, 6.5);

        // ----------------------------------------------------------------
        // Neptunium fuels  (release fraction ~5 %)
        //   Np-237: t½ = 2.14×10⁶ y, long-lived α-emitter, moderately radiotoxic.
        // ----------------------------------------------------------------

        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_men,    1.8);
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_hen,    2.2);

        // ----------------------------------------------------------------
        // Schrabidium fuels  (fictional; release fraction ~20 %)
        //   Exotic isotopes with anomalously high specific activity.
        // ----------------------------------------------------------------

        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_les,    5.0);
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_mes,    6.0);
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_hes,    7.5);

        // ----------------------------------------------------------------
        // Australium fuels  (fictional; release fraction ~25 %)
        //   Extreme exotic isotopes; massive airborne contamination.
        // ----------------------------------------------------------------

        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_leaus,  6.0);
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_heaus,  9.0);

        // ----------------------------------------------------------------
        // Unobtainium  (fictional; extraordinarily stable lattice)
        //   Very low release fraction — most inventory stays in the corium.
        // ----------------------------------------------------------------

        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_unobtainium, 0.5);

        // ----------------------------------------------------------------
        // Alpha-emitter neutron sources
        //   Small fissile inventory per unit yield, but extreme specific activity.
        //
        //   Ra-226:  1 Ci/g  — radium contamination persists centuries
        //   Po-210:  ~4 500 Ci/g — lethal in sub-microgram amounts
        //   Pu-238:  17 Ci/g — primary decay-heat isotope, highly radiotoxic
        // ----------------------------------------------------------------

        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_ra226be,  2.5);
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_po210be,  9.0);
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_pu238be,  2.5);

        // ----------------------------------------------------------------
        // Fantasy / exotic fuels
        // ----------------------------------------------------------------

        // Balefire Gold: persistent magical contamination, elevated release
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_balefire_gold, 4.0);

        // Flashlead: anomalous fission products with high volatility
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_flashlead,     5.0);

        // ZFB-Bismuth: Bi-209 transmutation chain, moderate contamination
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_zfb_bismuth,   2.5);

        // ZFB-Pu241: Pu-241 → Am-241 breeding, elevated long-term hazard
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_zfb_pu241,     4.0);

        // ZFB-Am mix: high americium content
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_zfb_am_mix,    5.5);

        // Pure balefire: Fallout-universe magical fire, extreme and persistent.
        // 15× baseline reflects both the magical spread and the narrative
        // "uninhabitable for generations" lore.
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_balefire, 15.0);

        // DRX fuel: triggers the Digamma event; radiation is secondary.
        RELATIVE_ACTIVITY.put(ModItems.rbmk_fuel_drx,  1.0);
    }

    // -----------------------------------------------------------------
    // Main entry point
    // -----------------------------------------------------------------

    /**
     * Called from {@link com.hbm.main.tileentity.machine.rbmk.TileEntityRBMKBase#meltdown()}
     * <b>after</b> the reactor bounds are computed but <b>before</b>
     * {@code onMelt()} removes fuel from the rod inventories.
     *
     * <p>Iterates over every fuel-bearing column in {@code columns}, sums the
     * isotope-weighted source term, then registers two
     * {@link AtmosphericDispersionSystem} sources:
     * <ol>
     *   <li>A finite puff representing the initial explosion / fire lofting.</li>
     *   <li>A sustained low-level emission representing the ongoing corium fire.</li>
     * </ol>
     *
     * @param world   The server-side world instance.
     * @param columns All RBMK columns belonging to this reactor (flood-fill set).
     * @param minX    Minimum column X block coordinate.
     * @param maxX    Maximum column X block coordinate.
     * @param minZ    Minimum column Z block coordinate.
     * @param maxZ    Maximum column Z block coordinate.
     */
    public static void onMeltdown(World world,
                                   Set<TileEntityRBMKBase> columns,
                                   int minX, int maxX,
                                   int minZ, int maxZ) {

        if (world == null || world.isRemote) return;

        // ------------------------------------------------------------------
        // [Debug] Log meltdown event header
        // ------------------------------------------------------------------
        MainRegistry.logger.info(String.format(
                "[RBMK-Contamination] Meltdown event: dim=%d  bounds=[%d–%d, %d–%d]  columns=%d",
                world.provider.getDimension(), minX, maxX, minZ, maxZ, columns.size()));

        double totalPuffQ     = 0.0;
        double totalCoreHeat  = 0.0;
        int    fuelRodCount   = 0;

        // ------------------------------------------------------------------
        // 1. Accumulate source term from all fuel-bearing columns
        //    (inventories still intact at this point)
        // ------------------------------------------------------------------

        for (TileEntityRBMKBase rbmk : columns) {

            if (!(rbmk instanceof TileEntityRBMKRod)) continue;
            TileEntityRBMKRod rodColumn = (TileEntityRBMKRod) rbmk;

            ItemStack stack = rodColumn.inventory.getStackInSlot(0);
            if (!(stack.getItem() instanceof ItemRBMKRod)) continue;

            ItemRBMKRod fuelDef      = (ItemRBMKRod) stack.getItem();
            double      enrichment   = ItemRBMKRod.getEnrichment(stack);        // [0, 1]
            double      relativeAct  = RELATIVE_ACTIVITY.getOrDefault(fuelDef, 1.0);

            //   Q_rod = yield × enrichment × scale × isotope_multiplier
            double rodQ = fuelDef.yield * enrichment
                          * BASELINE_SOURCE_PER_YIELD * relativeAct;

            totalPuffQ    += rodQ;
            totalCoreHeat += ItemRBMKRod.getCoreHeat(stack);
            fuelRodCount++;

            // [Debug] Per-rod contribution
            MainRegistry.logger.info(String.format(
                    "[RBMK-Contamination]   rod '%s'  enrichment=%.3f  relAct=%.2f"
                    + "  yield=%.3e  rodQ=%.4e game_rad·m²",
                    stack.getDisplayName(), enrichment, relativeAct,
                    (double) fuelDef.yield, rodQ));
        }

        // [Debug] Accumulation totals
        MainRegistry.logger.info(String.format(
                "[RBMK-Contamination] Accumulation: fuelRods=%d  totalPuffQ=%.4e game_rad·m²",
                fuelRodCount, totalPuffQ));

        if (totalPuffQ < 1.0 || fuelRodCount == 0) {
            MainRegistry.logger.info("[RBMK-Contamination] Skipped source registration:"
                    + " no fuel rods or totalPuffQ < 1.0");
            return;
        }

        // ------------------------------------------------------------------
        // 2. Compute release geometry
        // ------------------------------------------------------------------

        // Horizontal centre of the reactor footprint
        double centerX = (minX + maxX) * 0.5 + 0.5;
        double centerZ = (minZ + maxZ) * 0.5 + 0.5;
        double surfaceY = world.getHeight((int) centerX, (int) centerZ);

        // Reactor half-span (blocks) → larger reactors produce taller blast ejection
        int    reactorHalfSpan = Math.max(maxX - minX, maxZ - minZ) / 2 + 1;
        double avgCoreHeat     = totalCoreHeat / fuelRodCount;  // retained for debug log

        // Effective plume height for the initial explosion puff [m].
        //
        // The puff represents immediate explosive dispersal of radioactive material
        // (ejected fuel fragments, graphite, fission-product aerosols) at low
        // altitude around the destroyed reactor.  Unlike the sustained fire plume
        // (Chernobyl: 1–2 km over 10 days), the mechanical ejection keeps most
        // material below 15 m where the Gaussian model can deposit it within the
        // 512 m online scan zone.
        //
        // Formula:
        //   base  = 5 m  (minimum scatter height for ejected debris)
        //   size  = +1.5 m per half-span block (larger explosion for bigger reactors)
        //   max   = 15 m  (ensures exp(-H²/2σz²) is non-negligible at σz ≈ 6 m)
        //
        // avgCoreHeat is intentionally excluded: rod temperature drives volatile
        // release fraction (already captured in relativeActivity), not the
        // mechanical ejection height of the explosion.
        //
        // Gaussian deposition check (class D, u=3 m/s, H=7 m, x=127 m):
        //   σz(127 m) ≈ 6 m  →  exp(-49/72) ≈ 0.51  →  meaningful deposition ✓
        double plumeHeightM = 5.0 + reactorHalfSpan * 1.5;
        plumeHeightM = Math.min(plumeHeightM, 15.0);

        // [Debug] Release geometry
        MainRegistry.logger.info(String.format(
                "[RBMK-Contamination] Geometry: center=(%.1f, %.1f, %.1f)"
                + "  halfSpan=%d blocks  avgCoreHeat=%.1f  plumeHeight=%.1f m",
                centerX, surfaceY, centerZ, reactorHalfSpan, avgCoreHeat, plumeHeightM));

        // ------------------------------------------------------------------
        // 3. Register plume sources with AtmosphericDispersionSystem
        // ------------------------------------------------------------------

        AtmosphericDispersionSystem ads = AtmosphericDispersionSystem.get(world);

        // Source A: initial explosion / fire-lofting puff
        //   High altitude, short duration — the "acute" release phase.
        ads.registerSource(centerX, surfaceY, centerZ,
                           plumeHeightM,
                           totalPuffQ,
                           PUFF_DURATION_S);

        MainRegistry.logger.info(String.format(
                "[RBMK-Contamination] Source A (puff) registered:"
                + " H=%.1f m  totalQ=%.4e game_rad·m²  dur=%.0f s",
                plumeHeightM, totalPuffQ, PUFF_DURATION_S));

        // Source B: sustained corium / graphite-fire emission
        //   Near ground level, multi-day duration — the "chronic" release phase.
        //   Total quantity = CORIUM_FRACTION × puffQ spread over CORIUM_DURATION_S.
        ads.registerSource(centerX, surfaceY, centerZ,
                           CORIUM_HEIGHT_M,
                           totalPuffQ * CORIUM_FRACTION,
                           CORIUM_DURATION_S);

        MainRegistry.logger.info(String.format(
                "[RBMK-Contamination] Source B (corium) registered:"
                + " H=%.1f m  totalQ=%.4e game_rad·m²  dur=%.0f s (%.1f real days)",
                CORIUM_HEIGHT_M, totalPuffQ * CORIUM_FRACTION,
                CORIUM_DURATION_S, CORIUM_DURATION_S / 86400.0));
    }
}
