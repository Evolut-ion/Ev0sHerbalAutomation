package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.hypixel.hytale.logger.HytaleLogger;
import voidbond.arcio.components.ArcioMechanismComponent;
import voidbond.arcio.mechanisms.IMechanism;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * IMechanism handler for the Fertilizer block.
 * Registered with ArcIO so that the FertilizerState block can be
 * wired into ArcIO networks via manathreads.
 */
public class FertilizerMechanismHandler implements IMechanism {

    @Override
    public int process(ArcioMechanismComponent arcioMechanismComponent, World world, int x, int y, int z) {
        int signal = arcioMechanismComponent.getStrongestInputSignal(world);
        if (signal > 0) {
            HytaleLogger.getLogger().atFine().log(
                    "Fertilizer mechanism activated at " + x + ", " + y + ", " + z);
        }
        return signal;
    }

    @Override
    public String getDefaultState() {
        return "Off";
    }
}
