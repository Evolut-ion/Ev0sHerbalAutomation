package com.Ev0sMods.Ev0sWoodCutter;

import com.Ev0sMods.Ev0sWoodCutter.blockstates.BlockPlacer;
import com.Ev0sMods.Ev0sWoodCutter.blockstates.WoodCutter;
import com.Ev0sMods.Ev0sWoodCutter.interactions.CutterFarmingStageInteraction;
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
        this.getCodecRegistry(Interaction.CODEC).register("GrowthInteraction", CutterFarmingStageInteraction.class, CutterFarmingStageInteraction.CODEC);
        // TODO: Initialize your plugin here
        // - Load configuration
        // - Register event listeners
        // - Register commands
        // - Start services
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
