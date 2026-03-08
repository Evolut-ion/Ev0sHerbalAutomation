package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import au.ellie.hyui.builders.PageBuilder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HyUI page for the Fertilizer block.
 * Split-panel layout: left = operation status + progress bar,
 * right = slot inventory representation (processing-bench style).
 */
public final class FertilizerUIPage {

    private FertilizerUIPage() {}

    /** Per-player UI session: the player's entity ref, entity store, and watched block position. */
    private record PlayerSession(Ref<EntityStore> entityRef, Store<EntityStore> store, Vector3i blockPos) {}
    /** Active UI sessions — players currently viewing a fertilizer block. */
    private static final ConcurrentHashMap<PlayerRef, PlayerSession> SESSIONS = new ConcurrentHashMap<>();
    /** Monotonically increasing generation per player. Used to tell apart a player-initiated
     *  dismiss from a programmatic page replacement caused by tick refresh. */
    private static final ConcurrentHashMap<PlayerRef, AtomicLong> PAGE_GENS = new ConcurrentHashMap<>();
    /** Metadata for a filled player-inventory slot — used to wire up click listeners. */
    private record SlotInfo(String id, ItemContainer container, short slot) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    public static void open(PlayerRef playerRef, Ref<EntityStore> entityRef, Store<EntityStore> store, Vector3i pos) {
        SESSIONS.put(playerRef, new PlayerSession(entityRef, store, pos));
        PAGE_GENS.computeIfAbsent(playerRef, k -> new AtomicLong());
        renderPage(playerRef, entityRef, store, pos, null);
    }

    /**
     * Called from {@link FertilizerState#tick} every ~15 ticks to push updated HTML to watching players.
     */
    static void tickRefresh(FertilizerState fs, Store<EntityStore> entityStore, Vector3i pos) {
        SESSIONS.forEach((playerRef, session) -> {
            if (session.blockPos().equals(pos)) {
                renderPage(playerRef, session.entityRef(), session.store(), pos, fs);
            }
        });
    }

    private static void renderPage(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                   Store<EntityStore> store, Vector3i pos, FertilizerState fsHint) {
        try {
            FertilizerState fs = (fsHint != null) ? fsHint : lookup(store, pos);
            Inventory inventory = null;
            try {
                Player player = store.getComponent(entityRef, Player.getComponentType());
                if (player != null) inventory = player.getInventory();
            } catch (Throwable ignored) {}

            List<SlotInfo> slots = new ArrayList<>();
            // Increment the generation for this player before opening so that the old page's
            // onDismiss (fired when we replace it) sees a stale generation and does nothing.
            long myGen = PAGE_GENS.computeIfAbsent(playerRef, k -> new AtomicLong()).incrementAndGet();
            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(fs, inventory, slots))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction)
                    .onDismiss((page, playerInitiated) -> {
                        AtomicLong gen = PAGE_GENS.get(playerRef);
                        if (gen != null && gen.get() == myGen) {
                            // This is the latest page — player dismissed it (Escape / F).
                            SESSIONS.remove(playerRef);
                            PAGE_GENS.remove(playerRef);
                        }
                        // else: an older page replaced by a tick refresh — ignore.
                    });

            builder.addEventListener("close-btn", CustomUIEventBindingType.Activating, (ign, ctx) -> {
                SESSIONS.remove(playerRef);
                ctx.getPage().ifPresent(p -> p.close());
            });

            for (SlotInfo info : slots) {
                final ItemContainer srcContainer = info.container();
                final short srcSlot = info.slot();
                builder.addEventListener(info.id(), CustomUIEventBindingType.Activating, (ign, ctx) ->
                        transferItem(playerRef, entityRef, store, pos, srcContainer, srcSlot, (short) 0));
                builder.addEventListener(info.id(), CustomUIEventBindingType.RightClicking, (ign, ctx) ->
                        transferItem(playerRef, entityRef, store, pos, srcContainer, srcSlot, (short) 1));
            }

            builder.open(store);
        } catch (Throwable t) {
            // Player may have disconnected — remove stale session.
            SESSIONS.remove(playerRef);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTML layout
    // ─────────────────────────────────────────────────────────────────────────

    private static String buildHtml(FertilizerState fs, Inventory inventory, List<SlotInfo> slotInfoOut) {
        // ── Gather state ─────────────────────────────────────────────────────
        int    progress   = 0;
        String statusText = "Idle";
        String typeText   = "None";
        String nextIn     = "\u2014";  // em dash
        String barColor   = "#444444";
        String statusColor = "#7a9aaa";
        String slot0Id    = null;
        int    slot0Qty   = 0;
        String slot1Id    = null;
        int    slot1Qty   = 0;

        if (fs != null) {
            typeText   = friendlyName(fs.activeFertilizerType);

            if (fs.isProcessing && fs.effectiveTickInterval > 0) {
                progress     = Math.min(100,
                        (int)(100.0 * fs.processingTimer / fs.effectiveTickInterval));
                statusText   = "Active";
                barColor     = "#4caf50";
                statusColor  = "#81c784";

                int ticksLeft = fs.effectiveTickInterval - fs.processingTimer;
                int seconds   = Math.max(0, ticksLeft / 30);
                nextIn = seconds + "s";
            }

            if (fs.getItemContainer() != null) {
                ItemStack s0 = fs.getItemContainer().getItemStack((short) 0);
                ItemStack s1 = fs.getItemContainer().getItemStack((short) 1);
                if (s0 != null && !s0.isEmpty()) { slot0Id = s0.getItemId(); slot0Qty = safeQty(s0); }
                if (s1 != null && !s1.isEmpty()) { slot1Id = s1.getItemId(); slot1Qty = safeQty(s1); }
            }
        }

        // ── Left panel: operation status ─────────────────────────────────────
        // Bar track width = panel width (320) minus left+right padding (16+16) = 288px.
        int barTrackWidth = 288;
        int barFillWidth  = (int)(barTrackWidth * progress / 100.0);
        String barFill = "<div style=\"anchor-width: %d; anchor-height: 18; background-color: %s; border-radius: 9;\"></div>"
                .formatted(barFillWidth, barColor);

        String leftPanel = """
                <div style="layout-mode: Top; anchor-width: 320; padding-top: 8; padding-bottom: 8; padding-left: 16; padding-right: 16;">
                    <p class="title-label">Fertilizer Block</p>
                    <div class="separator"></div>

                    <p class="section-label">Status</p>
                    <p class="info-label" style="color: %s;">%s</p>

                    <p class="section-label">Fertilizer Type</p>
                    <p class="info-label">%s</p>

                    <p class="section-label">Progress</p>
                    <div style="layout-mode: Left; anchor-width: 288; anchor-height: 18; background-color: #1a1a1a; border-radius: 9; margin-top: 4; margin-bottom: 6;">
                        %s
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center;">
                        <p class="pct-label" style="color: %s;">%d%%</p>
                        <p class="info-label" style="padding-left: 12;">Next tick in: %s</p>
                    </div>

                    <div class="separator"></div>

                    <div style="layout-mode: Top; horizontal-align: center; padding-top: 8;">
                        <button id="close-btn" class="secondary-button"
                                style="anchor-width: 120; anchor-height: 30; font-size: 13; color: #e57373;">&#x2715; Close</button>
                    </div>
                </div>
                """.formatted(statusColor, statusText, typeText, barFill, statusColor, progress, nextIn);

        // ── Right panel: slot inventory (processing-bench style) ─────────────
        String slot0Html = buildSlotHtml(slot0Id, slot0Qty, "Fertilizer");
        String slot1Html = buildSlotHtml(slot1Id, slot1Qty, "Liquid");

        String rightPanel = """
                <div style="layout-mode: Top; anchor-width: 200; padding-top: 8; padding-bottom: 8; padding-left: 16; padding-right: 16;">
                    <div class="vert-top-pad"></div>
                    <p class="section-label">Inventory Slots</p>
                    <div class="separator"></div>
                    <div style="layout-mode: Top; padding-top: 12; padding-bottom: 8; horizontal-align: center;">
                %s
                        <div style="layout-mode: Left; horizontal-align: center; padding-top: 6; padding-bottom: 6;">
                            <p class="arrow-label">&#9660;</p>
                        </div>
                %s
                    </div>
                    <div class="separator"></div>
                    <p class="hint-label">Slot 0: fertilizer item</p>
                    <p class="hint-label">Slot 1: water / fertilizer water</p>
                </div>
                """.formatted(slot0Html, slot1Html);

        String inventoryHtml = buildInventoryHtml(inventory, slotInfoOut);
        return STYLE + """
                <div style="anchor-width: 100%%; anchor-height: 100%%; horizontal-align: center; vertical-align: middle;">
                    <div class="decorated-container" data-hyui-title="Fertilizer Block"
                         style="anchor-height: 640; anchor-width: 672;">
                        <div class="container-contents"
                             style="layout-mode: Top; padding-top: 12; padding-bottom: 12; padding-left: 16; padding-right: 16; horizontal-align: center;">
                            <div style="layout-mode: Left; horizontal-align: center;">
                %s
                                <div class="vert-separator"></div>
                %s
                            </div>
                %s
                        </div>
                    </div>
                </div>
                """.formatted(leftPanel, rightPanel, inventoryHtml);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Slot HTML helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String buildSlotHtml(String itemId, int qty, String label) {
        if (itemId != null) {
            String shortName = prettifyId(itemId);
            return """
                        <div style="layout-mode: Top; horizontal-align: center; padding-top: 4; padding-bottom: 8;">
                            <p class="slot-label">%s</p>
                            <div style="layout-mode: Left; horizontal-align: center; padding-top: 4; padding-bottom: 4;">
                                <span class="item-icon" data-hyui-item-id="%s"
                                      style="anchor-width: 48; anchor-height: 48; margin-right: 8;"></span>
                                <div style="layout-mode: Top; vertical-align: middle;">
                                    <p class="slot-item-name">%s</p>
                                    <p class="slot-item-qty">x%d</p>
                                </div>
                            </div>
                        </div>
                    """.formatted(label, itemId, shortName, qty);
        } else {
            return """
                        <div style="layout-mode: Top; horizontal-align: center; padding-top: 4; padding-bottom: 8;">
                            <p class="slot-label">%s</p>
                            <div style="layout-mode: Left; horizontal-align: center; padding-top: 4; padding-bottom: 4;">
                                <div class="empty-slot"></div>
                                <p class="info-label" style="padding-left: 8; vertical-align: middle;">(empty)</p>
                            </div>
                        </div>
                    """.formatted(label);
        }
    }

    private static String buildEmptySlotHtml(String label, String note) {
        return """
                        <div style="layout-mode: Top; horizontal-align: center; padding-top: 4; padding-bottom: 8;">
                            <p class="slot-label">%s</p>
                            <div style="layout-mode: Left; horizontal-align: center; padding-top: 4; padding-bottom: 4;">
                                <div class="empty-slot"></div>
                                <p class="info-label" style="padding-left: 8; vertical-align: middle;">%s</p>
                            </div>
                        </div>
                """.formatted(label, note);
    }

    /** Renders hotbar + storage as a single 9×5 grid. Returns "" if unavailable. */
    private static String buildInventoryHtml(Inventory inventory, List<SlotInfo> slotInfoOut) {
        if (inventory == null) return "";
        try {
            ItemContainer hotbar  = inventory.getHotbar();
            ItemContainer storage = inventory.getStorage();

            int cols = 9;
            int storageRows = 4;

            StringBuilder sb = new StringBuilder();
            sb.append("<div class=\"separator\"></div>\n");
            sb.append("<div style=\"layout-mode: Top; padding-top: 6; padding-bottom: 8; padding-left: 8; padding-right: 8;\">\n");
            sb.append("<p class=\"section-label\" style=\"horizontal-align: center;\">Player Inventory</p>\n");

            // Each row: 9 cells * 52px (48 content + 2+2 margin) = 468px wide, 52px tall.
            // anchor-height must be set so layout-mode:Top can size the decorated-container frame.
            // Hotbar row (row 0)
            sb.append("<div style=\"layout-mode: Left; anchor-width: 468; anchor-height: 52;\">\n");
            for (short i = 0; i < cols; i++) {
                sb.append(miniSlotHtml(hotbar, i, "inv_h_" + i, slotInfoOut));
            }
            sb.append("</div>\n");

            // Storage rows (rows 1..4)
            for (int row = 0; row < storageRows; row++) {
                sb.append("<div style=\"layout-mode: Left; anchor-width: 468; anchor-height: 52;\">\n");
                for (int col = 0; col < cols; col++) {
                    short idx = (short)(row * cols + col);
                    sb.append(miniSlotHtml(storage, idx, "inv_s_" + idx, slotInfoOut));
                }
                sb.append("</div>\n");
            }

            sb.append("</div>\n");
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String miniSlotHtml(ItemContainer container, short slotIndex,
                                       String slotId, List<SlotInfo> slotInfoOut) {
        // All slots: 48x48 outer box, 2px margin on each side = 52px total per cell.
        // Filled: button wrapping a 40x40 icon with 4px padding each side.
        // Empty: plain div, same outer size, same margin.
        String baseStyle = "anchor-width: 48; anchor-height: 48; "
                + "margin-top: 2; margin-bottom: 2; margin-left: 2; margin-right: 2;";
        try {
            ItemStack stack = (container != null) ? container.getItemStack(slotIndex) : null;
            if (stack != null && !stack.isEmpty()) {
                String key = stack.getItemId();
                slotInfoOut.add(new SlotInfo(slotId, container, slotIndex));
                return "<button id=\"" + slotId + "\" style=\"" + baseStyle + "\">"
                        + "<span class=\"item-icon\" data-hyui-item-id=\"" + key + "\" "
                        + "style=\"anchor-width: 40; anchor-height: 40;\"></span>"
                        + "</button>\n";
            }
        } catch (Throwable ignored) {}
        return "<div style=\"" + baseStyle + " background-color: #ffffff(0.06);\"></div>\n";
    }

    private static void transferItem(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                     Store<EntityStore> store, Vector3i pos,
                                     ItemContainer srcContainer, short srcSlot, short blockSlot) {
        try {
            Player player = store.getComponent(entityRef, Player.getComponentType());
            if (player == null) return;
            Inventory inv = player.getInventory();

            ItemStack moving = srcContainer.getItemStack(srcSlot);
            if (moving == null || moving.isEmpty()) return;

            FertilizerState fs = lookup(store, pos);
            if (fs == null) return;
            ItemContainer blockIc = fs.getItemContainer();
            if (blockIc == null) return;

            blockIc.setItemStackForSlot(blockSlot, moving);
            srcContainer.setItemStackForSlot(srcSlot, ItemStack.EMPTY);
            inv.markChanged();

            renderPage(playerRef, entityRef, store, pos, null);
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSS
    // ─────────────────────────────────────────────────────────────────────────

    private static final String STYLE = """
            <style>
                .title-label {
                    font-weight: bold;
                    color: #bdcbd3;
                    font-size: 18;
                    padding-top: 8;
                    padding-bottom: 6;
                }
                .section-label {
                    font-weight: bold;
                    color: #bdcbd3;
                    font-size: 14;
                    padding-top: 6;
                    padding-bottom: 2;
                }
                .info-label {
                    color: #a0b8c8;
                    font-size: 12;
                    padding-top: 2;
                    padding-bottom: 2;
                }
                .hint-label {
                    color: #7a9aaa;
                    font-size: 11;
                    padding-top: 2;
                    padding-bottom: 2;
                }
                .pct-label {
                    font-weight: bold;
                    font-size: 14;
                    padding-top: 2;
                    padding-bottom: 2;
                }
                .slot-label {
                    font-weight: bold;
                    color: #bdcbd3;
                    font-size: 13;
                    padding-bottom: 2;
                    horizontal-align: center;
                }
                .slot-item-name {
                    color: #c8dbe8;
                    font-size: 13;
                    font-weight: bold;
                    padding-bottom: 2;
                }
                .slot-item-qty {
                    color: #a0b8c8;
                    font-size: 12;
                }
                .arrow-label {
                    color: #5a7a8a;
                    font-size: 20;
                    horizontal-align: center;
                }
                .separator {
                    layout-mode: Full;
                    anchor-height: 1;
                    background-color: #ffffff(0.15);
                    margin-top: 6;
                    margin-bottom: 6;
                }
                .vert-separator {
                    anchor-width: 1;
                    layout-mode: Full;
                    background-color: #ffffff(0.15);
                    margin-left: 6;
                    margin-right: 6;
                }
                .empty-slot {
                    anchor-width: 48;
                    anchor-height: 48;
                    background-color: #ffffff(0.06);
                    margin-top: 4;
                    margin-bottom: 4;
                }
                .mini-icon {
                    anchor-width: 36;
                    anchor-height: 36;
                    margin: 2;
                }
                .mini-slot-empty {
                    anchor-width: 36;
                    anchor-height: 36;
                    margin: 2;
                    background-color: #0a0a14;
                }
            </style>
            """;

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String friendlyName(FertilizerState.FertilizerType type) {
        return switch (type) {
            case STANDARD_WATER -> "Standard + Liquid  (60s, or 30s w/ fert water)";
            case NOCUBE_TREE    -> "NoCube Tree Fertilizer  (30s, trees only)";
            case NOCUBE_LIME    -> "NoCube Lime Fertilizer  (30s)";
            case NOCUBE_BONE    -> "NoCube Bone Fertilizer  (15s)";
            case NOCUBE_SEASHELL -> "NoCube Seashell Fertilizer  (7.5s)";
            case NOCUBE_ELITE   -> "NoCube Elite Fertilizer  (~3.8s)";
            case NONE           -> "None";
        };
    }

    private static String prettifyId(String id) {
        if (id == null || id.isEmpty()) return "Unknown";
        int colon = id.indexOf(':');
        String local = (colon >= 0) ? id.substring(colon + 1) : id;
        return local.replace('_', ' ');
    }

    private static int safeQty(ItemStack s) {
        try { return s.getQuantity(); } catch (Throwable t) { return 0; }
    }

    private static FertilizerState lookup(Store<EntityStore> store, Vector3i pos) {
        try {
            World world = store.getExternalData().getWorld();
            if (world == null) return null;
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(pos.x, pos.z));
            if (chunk == null) return null;
            Object state = chunk.getState(pos.x, pos.y, pos.z);
            return (state instanceof FertilizerState fs) ? fs : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
