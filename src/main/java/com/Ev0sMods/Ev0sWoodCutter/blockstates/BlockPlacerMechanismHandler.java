package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;

import voidbond.arcio.components.ArcioMechanismComponent;
import voidbond.arcio.mechanisms.IMechanism;

/**
 * IMechanism handler for the BlockPlacer block.
 * Registered with ArcIO so that the BlockPlacer block can be
 * wired into ArcIO networks via manathreads.
 */
public class BlockPlacerMechanismHandler implements IMechanism {

    public static final String STATE_OFF = "Off";
    public static final String STATE_ON  = "On";

    @Override
    public int process(ArcioMechanismComponent arcioMechanismComponent, World world, int x, int y, int z) {
        int signal   = arcioMechanismComponent.getStrongestInputSignal(world);
        int required = arcioMechanismComponent.getRequiredSignal();
        boolean active = signal >= required;

        String targetState = active ? STATE_ON : STATE_OFF;
        try {
            arcioMechanismComponent.getClass()
                    .getMethod("setState", String.class)
                    .invoke(arcioMechanismComponent, targetState);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            HytaleLogger.getLogger().atFine().log(
                    "[BlockPlacer] setState reflection failed at %d,%d,%d: %s", x, y, z, e.getMessage());
        }

        arcioMechanismComponent.setSignal(active ? signal : 0);

        if (active) {
            HytaleLogger.getLogger().atFine().log(
                    "[BlockPlacer] Mechanism ON at %d,%d,%d (signal=%d, required=%d)",
                    x, y, z, signal, required);
        }
        return 0;
    }

    @Override
    public String getDefaultState() {
        return STATE_OFF;
    }
}
