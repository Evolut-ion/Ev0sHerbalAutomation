package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import voidbond.arcio.ArcioPlugin;

/**
 * Isolated helper class for ArcIO mechanism registration.
 * This class is ONLY loaded via reflection after confirming ArcIO is present,
 * so its ArcIO imports won't cause NoClassDefFoundError during plugin loading.
 */
public class ArcioRegistration {

    public static void register() {
        ArcioPlugin.get().registerMechanism("Woodcutter", new WoodCutterMechanismHandler());
        ArcioPlugin.get().registerMechanism("Fertilizer", new FertilizerMechanismHandler());
        ArcioPlugin.get().registerMechanism("BlockPlacer", new BlockPlacerMechanismHandler());
    }
}
