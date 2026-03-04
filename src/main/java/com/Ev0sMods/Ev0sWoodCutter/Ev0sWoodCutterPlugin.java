package com.Ev0sMods.Ev0sWoodCutter;

import com.Ev0sMods.Ev0sWoodCutter.blockstates.BlockPlacer;
import com.Ev0sMods.Ev0sWoodCutter.blockstates.FertilizerState;
import com.Ev0sMods.Ev0sWoodCutter.blockstates.WoodCutter;
import com.Ev0sMods.Ev0sWoodCutter.interactions.CutterFarmingStageInteraction;
import com.Ev0sMods.Ev0sWoodCutter.interactions.WoodcutterChangeStateInteraction;
import com.Ev0sMods.Ev0sWoodCutter.interactions.WoodcutterInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.meta.BlockStateRegistry;

import javax.annotation.Nonnull;


public class Ev0sWoodCutterPlugin extends JavaPlugin {

    public Ev0sWoodCutterPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        System.out.println("[Ev0sWoodCutter] Plugin loaded!");
    }

    @Override
    protected void setup() {
        super.setup();
        System.out.println("[Ev0sWoodCutter] Plugin enabled!");
        final BlockStateRegistry bsr = this.getBlockStateRegistry();
        bsr.registerBlockState(WoodCutter.class, "Woodcutter", WoodCutter.CODEC, WoodCutter.Data.class, WoodCutter.Data.CODEC);
        bsr.registerBlockState(BlockPlacer.class, "BlockPlacer", BlockPlacer.CODEC, BlockPlacer.Data.class, BlockPlacer.Data.CODEC);
        bsr.registerBlockState(FertilizerState.class, "FertilizerState", FertilizerState.CODEC, FertilizerState.Data.class, FertilizerState.Data.CODEC);
        this.getCodecRegistry(Interaction.CODEC).register("GrowthInteraction", CutterFarmingStageInteraction.class, CutterFarmingStageInteraction.CODEC);
        // Registered for future JSON use; actual state changes are driven by WoodCutter.tick().
        this.getCodecRegistry(Interaction.CODEC).register("OpenWoodcutter", WoodcutterInteraction.class, WoodcutterInteraction.CODEC);
        // Block-driven state change: called from tick when ArcIO signal changes, not by player.
        this.getCodecRegistry(Interaction.CODEC).register("WoodcutterChangeState", WoodcutterChangeStateInteraction.class, WoodcutterChangeStateInteraction.CODEC);

        // Register ArcIO mechanisms if ArcIO is installed
        // Uses reflection to avoid loading ArcIO classes during plugin class resolution
        try {
            Class.forName("voidbond.arcio.ArcioPlugin");
            Class.forName("com.Ev0sMods.Ev0sWoodCutter.blockstates.ArcioRegistration")
                    .getMethod("register")
                    .invoke(null);
            System.out.println("[Ev0sWoodCutter] Registered ArcIO mechanisms: Woodcutter, Fertilizer, BlockPlacer");
        } catch (ClassNotFoundException ignored) {
            System.out.println("[Ev0sWoodCutter] ArcIO not found - skipping mechanism registration");
        } catch (Exception e) {
            System.out.println("[Ev0sWoodCutter] Failed to register ArcIO mechanisms: " + e.getMessage());
        }
    }

    /**
     * Called when plugin is enabled.
     */
    public void onEnable() {

    }
    
    /**
     * Called when plugin is disabled.
     */
    public void onDisable() {
        System.out.println("[Ev0sWoodCutter] Plugin disabled!");

        // TODO: Cleanup your plugin here
        // - Save data
        // - Stop services
        // - Close connections
    }
}
