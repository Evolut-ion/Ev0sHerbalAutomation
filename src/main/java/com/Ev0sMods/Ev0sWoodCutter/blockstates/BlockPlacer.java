package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Objects;

@SuppressWarnings("removal")
public class BlockPlacer extends ItemContainerState implements TickableBlockState, ItemContainerBlockState {

    public World w;
    private int square;
    public Store<EntityStore> entities;
    public static final BuilderCodec<BlockPlacer> CODEC = BuilderCodec.builder(BlockPlacer.class, BlockPlacer::new, BlockState.BASE_CODEC)
            .append(new KeyedCodec<>("Size", Codec.INTEGER, true), (i, v) -> i.square = v, i -> i.square).add().build();
    public Data data;
    public int timer = 0;
    @Override
    public void tick(
            float v,
            int i,
            ArchetypeChunk<ChunkStore> archetypeChunk,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        if (++timer < 150) return;
        timer = 0;

        World w = store.getExternalData().getWorld();

        var stack = this.getItemContainer().getItemStack((short) 0);
        if (stack == null || stack.isEmpty()) return;

        int baseX = this.getBlockX();
        int baseY = this.getBlockY()+3;
        int baseZ = this.getBlockZ();

        int remaining = stack.getQuantity();

        for (int dx = -2; dx <= 2 && remaining > 0; dx++) {
            for (int dz = -2; dz <= 2 && remaining > 0; dz++) {

                int x = baseX + dx;
                int y = baseY;
                int z = baseZ + dz;

                // Skip if there's already a sapling here
                var existing = w.getBlockType(x, y, z);
                if (existing != null && existing.getId().contains("Sapling")) {
                    continue;

                }
                if (existing != null && existing.getId().contains("Plant_Crop")) {
                    continue;

                }
                if(stack.getItemId().contains("Sapling")){
                    w.setBlock(
                            x,
                            y,
                            z,
                            stack.getBlockKey(),
                            3332
                    );
                    this.getItemContainer().removeItemStackFromSlot((short) 0, 1);
                    remaining--;

                } else if (stack.getItemId().contains("Seeds")) {
                    w.setBlock(
                            x,
                            y,
                            z,
                            stack.getItemId().replace("_Seeds", "_Crop") + "_Block",
                            3332
                    );
                    this.getItemContainer().removeItemStackFromSlot((short) 0, 1);
                    remaining--;
                }




            }
        }
    }

    @Override
    public boolean initialize(BlockType blockType) {
        if (super.initialize(blockType) && blockType.getState() instanceof Data data) {
            this.data = data;
            //SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource = (SpatialResource) .getResource(EntityModule.get().getPlayerSpatialResourceType());
            //if()
            setItemContainer(new SimpleItemContainer((short)1));
            return true;
        }

        return false;

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
    public static ComponentType<EntityStore, BlockEntity> getComponentType() {
        ComponentRegistryProxy<EntityStore> entityStoreRegistry = EntityModule.get().getEntityStoreRegistry();
        return EntityModule.get().getBlockEntityComponentType();
    }
    public static class Data extends StateData {
        @Nonnull
        public static final BuilderCodec<BlockPlacer.Data> CODEC = BuilderCodec.builder(BlockPlacer.Data.class, BlockPlacer.Data::new, StateData.DEFAULT_CODEC)
                .append(new KeyedCodec<>("Size", Codec.INTEGER), (o, v) -> o.square = v, o ->o.square).add().build();

        private int square;
    }
}
