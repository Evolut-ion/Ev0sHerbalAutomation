package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.hypixel.hytale.logger.HytaleLogger;
import voidbond.arcio.components.ArcioMechanismComponent;
import voidbond.arcio.mechanisms.IMechanism;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * IMechanism handler for the Woodcutter block, registered via ArcIO.
 *
 * Animation is driven through State.Definitions in Woodcutter.json:
 *
 *   "State": {
 *     "Definitions": {
 *       "Off": { "CustomModelAnimation": "...animation.blockyanim" },
 *       "On":  { "CustomModelAnimation": "...active.blockyanim"   }
 *     }
 *   }
 *
 * ArcIO links the signal returned by process() to these named definitions:
 *   • signal <  required  →  engine uses the "Off" definition  (idle animation)
 *   • signal >= required  →  engine uses the "On"  definition  (active animation)
 *
 * getDefaultState() tells ArcIO which definition to start in before any signal
 * arrives, so the block always renders the idle animation on placement.
 *
 * WoodCutter.tick() reads the same signal to gate the actual tree-cutting work;
 * it no longer needs to call ActiveAnimationComponent directly — the state
 * definition system handles all client-side animation switching.
 */
public class WoodCutterMechanismHandler implements IMechanism {

    /** State definition name used when the mechanism has no active signal. */
    public static final String STATE_OFF = "Off";
    /** State definition name used when the mechanism receives a signal >= required. */
    public static final String STATE_ON  = "On";

    @Override
    public int process(ArcioMechanismComponent mechanismComponent, World world, int x, int y, int z) {
        int signal   = mechanismComponent.getStrongestInputSignal(world);
        int required = mechanismComponent.getRequiredSignal();
        boolean active = signal >= required;

        // Derive the target state definition name from the signal level.
        // ArcIO uses this name to look up the matching entry in State.Definitions
        // and apply its CustomModelAnimation to the block entity on all clients.
        String targetState = active ? STATE_ON : STATE_OFF;

        // If ArcioMechanismComponent exposes a setState / setCurrentState method
        // in this ArcIO build, call it here so the state definition switches
        // immediately rather than waiting for the next ArcIO processing cycle.
        try {
            mechanismComponent.getClass()
                    .getMethod("setState", String.class)
                    .invoke(mechanismComponent, targetState);
        } catch (NoSuchMethodException ignored) {
            // Older ArcIO builds manage the state internally from the signal;
            // no explicit call needed — process() return value is sufficient.
        } catch (Exception e) {
            HytaleLogger.getLogger().atFine().log(
                    "[WoodCutter] setState reflection failed at %d,%d,%d: %s", x, y, z, e.getMessage());
        }

        if (active) {
            HytaleLogger.getLogger().atFine().log(
                    "[WoodCutter] Mechanism ON at %d,%d,%d (signal=%d, required=%d)",
                    x, y, z, signal, required);
        }

        // Return the signal so ArcIO can propagate it downstream if needed.
        return signal;
    }

    /**
     * ArcIO reads this once at placement/load to decide which State.Definition
     * the block starts in.  Must match a key in the JSON "Definitions" map.
     */
    @Override
    public String getDefaultState() {
        return STATE_OFF;
    }
}
