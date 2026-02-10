package com.Ev0sMods.Ev0sWoodCutter.blockstates;





import com.Ev0sMods.Ev0sWoodCutter.interactions.CutterFarmingStageInteraction;
import com.Ev0sMods.Ev0sWoodCutter.interactions.HarvesterCropInteraction;
import com.hypixel.hytale.builtin.adventure.farming.FarmingSystems;
import com.hypixel.hytale.builtin.adventure.farming.interactions.HarvestCropInteraction;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.asset.common.BlockyAnimationCache;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ResourceType;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import it.unimi.dsi.fastutil.objects.ObjectList;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("removal")
public class WoodCutter extends ItemContainerState implements TickableBlockState, ItemContainerBlockState {
    public World w;
    private int square;
    public Store<EntityStore> entities;
    public static final BuilderCodec<WoodCutter> CODEC = BuilderCodec.builder(WoodCutter.class, WoodCutter::new, BlockState.BASE_CODEC)
            .append(new KeyedCodec<>("Size", Codec.INTEGER, true), (i, v) -> i.square = v, i -> i.square).add().build();
    public Data data;
    public int timer = 0;
    public Ref<EntityStore> ref;


    @Override
    public void tick(
            float v,
            int i,
            ArchetypeChunk<ChunkStore> archetypeChunk,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        if(timer >= 150) {
            timer = 0;
            World w = store.getExternalData().getWorld();



    /* ---------------------------------
       PHASE 0: Collect nearby dropped items
       --------------------------------- */
            for (Ref<EntityStore> ref : getAllItemsInBox(
                    this,
                    this.getBlockPosition(),
                    w.getEntityStore().getStore(),
                    true, true, true
            )) {
                ItemComponent ic =
                        ref.getStore().getComponent(ref, ItemComponent.getComponentType());
                if (ic == null) continue;

                ItemStack stack = ic.getItemStack();
                if (stack == null) continue;

                if (this.getItemContainer().canAddItemStack(stack)) {
                    this.getItemContainer().addItemStack(stack);
                    entities.removeEntity(ref, RemoveReason.UNLOAD);
                }
            }
            this.ref = ref;
            CutterFarmingStageInteraction interaction = new CutterFarmingStageInteraction();

            int baseX = this.getBlockX();
            int baseY = this.getBlockY();
            int baseZ = this.getBlockZ();

            // List to track all sapling positions for growth
            List<Vector3i> saplingsToGrow = new ArrayList<>();

            HytaleLogger.getLogger().atInfo().log(String.valueOf(getRotationIndex()));
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = 0; dz <= 5; dz++) {

                    int x = baseX + dx;
                    int y = baseY;
                    int z = baseZ + dz;
                    if(this.getRotationIndex()== 0){
                        z = baseZ + dz;
                    }
                    if(this.getRotationIndex()== 1){
                        z = baseZ - dz;
                    }
                    if(this.getRotationIndex()== 2){
                         x = baseX - dz;
                         z = baseZ + dx;
                    }
                    if(this.getRotationIndex()== 3){
                        x = baseX + dz;
                        z = baseZ + dx;
                    }
                    interaction.interactWithBlock(
                            w,
                            InteractionType.Use,
                            new ItemStack("Tool_Growth_Potion"),
                            new Vector3i(x, y, z));
                   // chunk.markNeedsSaving();

                    BlockType block = w.getBlockType(x, y, z);
                    if (block == null) continue;

                    // If already a sapling, just queue it for growth
                    if (block.getId().startsWith("Plant_Sapling_")) {
                        saplingsToGrow.add(new Vector3i(x, y, z));
                        continue;
                    }
                    else {
                        WorldChunk chunk = w.getChunkIfInMemory(
                                ChunkUtil.indexChunkFromBlock(x, z)
                        );
                        assert chunk != null;
                        if (block.getId().contains("Plant_Crop_")) {
                            chunk.setBlock(x,y,z, "Empty", 3332);
                            if(block.getGathering().getHarvest() != null) {
                                this.itemContainer.addItemStacks(BlockHarvestUtils.getDrops(block, 3, null, block.getGathering().getHarvest().getDropListId()));
                            }
                        }
                    }

                    Item item = block.getItem();
                    if (item == null || item.getResourceTypes() == null) continue;

                    boolean isHardwood = false;
                    for (var rt : item.getResourceTypes()) {
                        if ("Wood_Hardwood".equals(rt.id)) {
                            isHardwood = true;
                            break;
                        }
                    }
                    if (!isHardwood) continue;

                    WorldChunk chunk = w.getChunkIfInMemory(
                            ChunkUtil.indexChunkFromBlock(x, z)
                    );
                    if (chunk == null) continue;

                    // Normalize the species name
                    String sapling = item.getBlockId()
                            .replaceFirst("^Wood_", "")
                            .replaceAll("_(Trunk|Full|Large|Mature|Stump)$", "");
                    sapling = sapling.replace("_Trunk", "");
                    //AnimationUtils.playAnimation(new Ref<EntityStore>(w.getEntityStore().getStore()), AnimationSlot.Status,"active.blockyanim", true, (ComponentAccessor<EntityStore>)w.getEntityStore().getStore() );
                    // Clear tree
                    chunk.breakBlock(x, y, z, 3332);
                    chunk.breakBlock(x, y + 1, z, 3332);
                    chunk.breakBlock(x, y - 1, z, 3332);
                    chunk.breakBlock(x, y - 2, z, 3332);

                    // Prepare soil
                    chunk.setBlock(x, y - 1, z, "Soil_Grass");
                    chunk.setBlock(x, y - 2, z, "Soil_Grass");

                    // Plant sapling
                    //chunk.placeBlock(x, y, z, "Plant_Sapling_" + sapling, RotationTuple.NONE, 3332, true);

                    chunk.setTicking(x, y, z, true);

                    saplingsToGrow.add(new Vector3i(x, y, z));


                    interaction.interactWithBlock(
                            w,
                            InteractionType.Use,
                            new ItemStack("Tool_Growth_Potion"),
                            new Vector3i(x, y, z));
                    chunk.markNeedsSaving();
                }
            }
        } else{
            timer++;
        }
        int baseX = this.getBlockX();
        int baseY = this.getBlockY();
        int baseZ = this.getBlockZ();
        CutterFarmingStageInteraction interaction = new CutterFarmingStageInteraction();


    /* ---------------------------------
       PHASE 3: Apply growth to all saplings
       --------------------------------- */
            // Iterate over a copy to avoid mutation issues
            //List<Vector3i> growthList = new ArrayList<>(saplingsToGrow);
        World w = store.getExternalData().getWorld();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = 0; dz <= 5; dz++) {
                    int x = baseX + dx;
                    int y = baseY;
                    int z = baseZ + dz;


                    interaction.interactWithBlock(
                            w,
                            InteractionType.Use,
                            new ItemStack("Tool_Growth_Potion"),
                            new Vector3i(x, y, z));
                }
            }


    }




    @Override
    public boolean initialize(BlockType blockType) {
        if (super.initialize(blockType) && blockType.getState() instanceof Data data) {
            this.data = data;
            //SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource = (SpatialResource) .getResource(EntityModule.get().getPlayerSpatialResourceType());
            //if()
            setItemContainer(new SimpleItemContainer((short)15));
            return true;
        }

        return false;

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public List<Ref<EntityStore>> getAllItemsInBox(WoodCutter hp, Vector3i pos, @Nonnull ComponentAccessor<EntityStore> components, boolean players, boolean entities, boolean items) {
        final ObjectList<Ref<EntityStore>> results = SpatialResource.getThreadLocalReferenceList();
        final ObjectList<Ref<Store>> results2 = SpatialResource.getThreadLocalReferenceList();
        final Vector3d min = new Vector3d(pos.x-.5, pos.y-.5 , pos.z-.5);
        final Vector3d max = new Vector3d(pos.x+.5, pos.y+.5, pos.z+.5);
        if (entities) {
            //components.getResource(EntityModule.get().getEntitySpatialResourceType()).getSpatialStructure()
        }
        if (players) {
            //components.getResource(EntityModule.get().getPlayerSpatialResourceType()).getSpatialStructure().collectCylinder(new Vector3d(pos.x,pos.y,pos.z), 4, 8, results );
        }
        if (items) {
            components.getResource(EntityModule.get().getItemSpatialResourceType()).getSpatialStructure().collectCylinder(new Vector3d(pos.x,pos.y,pos.z+2), 20,20,results );
        }
        this.entities = (Store<EntityStore>) components;
        return results;
    }

    public static ComponentType<EntityStore, BlockEntity> getComponentType() {
        ComponentRegistryProxy<EntityStore> entityStoreRegistry = EntityModule.get().getEntityStoreRegistry();
        return EntityModule.get().getBlockEntityComponentType();
    }
    public static class Data extends StateData {
        @Nonnull
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new, StateData.DEFAULT_CODEC)
                .append(new KeyedCodec<>("Size", Codec.INTEGER), (o, v) -> o.square = v, o ->o.square).add().build();

        private int square;
    }
}
