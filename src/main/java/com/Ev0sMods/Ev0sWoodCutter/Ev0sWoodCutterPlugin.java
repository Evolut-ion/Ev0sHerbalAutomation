package com.Ev0sMods.Ev0sWoodCutter;

import com.Ev0sMods.Ev0sWoodCutter.blockstates.BlockPlacer;
import com.Ev0sMods.Ev0sWoodCutter.blockstates.FertilizerState;
import com.Ev0sMods.Ev0sWoodCutter.blockstates.WoodCutter;
import com.Ev0sMods.Ev0sWoodCutter.interactions.CutterFarmingStageInteraction;
import com.Ev0sMods.Ev0sWoodCutter.interactions.FertilizerInteraction;
import com.Ev0sMods.Ev0sWoodCutter.interactions.WoodcutterChangeStateInteraction;
import com.Ev0sMods.Ev0sWoodCutter.interactions.WoodcutterInteraction;
import com.Ev0sMods.Ev0sWoodCutter.blockstates.WoodCutterComponentSystem;
import com.Ev0sMods.Ev0sWoodCutter.blockstates.FertilizerComponentSystem;
import com.Ev0sMods.Ev0sWoodCutter.blockstates.BlockPlacerComponentSystem;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

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
        var csr = this.getChunkStoreRegistry();
        WoodCutter.COMPONENT_TYPE = csr.registerComponent(WoodCutter.class, "Woodcutter", WoodCutter.CODEC);
        BlockPlacer.COMPONENT_TYPE = csr.registerComponent(BlockPlacer.class, "BlockPlacer", BlockPlacer.CODEC);
        FertilizerState.COMPONENT_TYPE = csr.registerComponent(FertilizerState.class, "FertilizerState", FertilizerState.CODEC);
        csr.registerSystem(new WoodCutterComponentSystem(WoodCutter.COMPONENT_TYPE));
        csr.registerSystem(new BlockPlacerComponentSystem(BlockPlacer.COMPONENT_TYPE));
        csr.registerSystem(new FertilizerComponentSystem(FertilizerState.COMPONENT_TYPE));
        this.getCodecRegistry(Interaction.CODEC).register("GrowthInteraction", CutterFarmingStageInteraction.class, CutterFarmingStageInteraction.CODEC);
        // Registered for future JSON use; actual state changes are driven by WoodCutter.tick().
        this.getCodecRegistry(Interaction.CODEC).register("OpenWoodcutter", WoodcutterInteraction.class, WoodcutterInteraction.CODEC);
        // Block-driven state change: called from tick when ArcIO signal changes, not by player.
        this.getCodecRegistry(Interaction.CODEC).register("WoodcutterChangeState", WoodcutterChangeStateInteraction.class, WoodcutterChangeStateInteraction.CODEC);
        this.getCodecRegistry(Interaction.CODEC).register("OpenFertilizer", FertilizerInteraction.class, FertilizerInteraction.CODEC);

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
