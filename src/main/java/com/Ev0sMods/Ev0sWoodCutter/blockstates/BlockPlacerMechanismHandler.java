package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.hypixel.hytale.logger.HytaleLogger;
import voidbond.arcio.components.ArcioMechanismComponent;
import voidbond.arcio.mechanisms.IMechanism;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * IMechanism handler for the BlockPlacer block.
 * Registered with ArcIO so that the BlockPlacer block can be
 * wired into ArcIO networks via manathreads.
 */
public class BlockPlacerMechanismHandler implements IMechanism {

    @Override
    public int process(ArcioMechanismComponent arcioMechanismComponent, World world, int x, int y, int z) {
        int signal = arcioMechanismComponent.getStrongestInputSignal(world);
        if (signal > 0) {
            HytaleLogger.getLogger().atFine().log(
                    "BlockPlacer mechanism activated at " + x + ", " + y + ", " + z);
        }
        return signal;
    }

    @Override
    public String getDefaultState() {
        return "Off";
    }
}
