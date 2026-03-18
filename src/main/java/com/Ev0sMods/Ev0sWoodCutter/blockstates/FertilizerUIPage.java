package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.PanelBuilder;
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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HyUI page for the Fertilizer block.
 * Split-panel layout: left = operation status + progress bar,
 * right = slot inventory representation (processing-bench style).
 */
public final class FertilizerUIPage {

    private FertilizerUIPage() {}

    /** Per-player UI session: the player's entity ref, entity store, watched block position, live page, and last render keys. */
    private record PlayerSession(Ref<EntityStore> entityRef, Store<EntityStore> store, Vector3i blockPos,
                                  HyUIPage page, int lastStructKey, int lastDynKey) {}
    /** Active UI sessions — players currently viewing a fertilizer block. */
    private static final ConcurrentHashMap<PlayerRef, PlayerSession> SESSIONS = new ConcurrentHashMap<>();
    /** Per-block watcher count — allows O(1) hasWatcher checks in the tick hot path. */
    private static final ConcurrentHashMap<Vector3i, Integer> WATCHER_COUNT = new ConcurrentHashMap<>();
    /** (no suppression) */
    /** Last System.nanoTime() at which open() was allowed per player — suppresses rapid re-opens. */
    private static final ConcurrentHashMap<PlayerRef, Long> OPEN_COOLDOWN_NS = new ConcurrentHashMap<>();
    /** Minimum nanoseconds between player-triggered opens (~667 ms = 20 ticks @ 30 TPS). */
    private static final long OPEN_COOLDOWN_NANOS = 667_000_000L;
    /** Metadata for a filled player-inventory slot — used to wire up click listeners. */
    private record SlotInfo(String id, ItemContainer container, short slot) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    public static void open(PlayerRef playerRef, Ref<EntityStore> entityRef, Store<EntityStore> store, Vector3i pos) {
        // Suppress rapid repeated opens (e.g. held-right-click spam).
        long now = System.nanoTime();
        Long lastOpen = OPEN_COOLDOWN_NS.get(playerRef);
        if (lastOpen != null && now - lastOpen < OPEN_COOLDOWN_NANOS) return;
        OPEN_COOLDOWN_NS.put(playerRef, now);

        // Manual opens allowed unconditionally.

        PlayerSession existing = SESSIONS.get(playerRef);
        if (existing != null && existing.blockPos().equals(pos)) {
            // Player already has this exact block open — no need to re-render.
            return;
        }
        // If switching from a different block, decrement the old block's watcher count.
        if (existing != null) {
            WATCHER_COUNT.merge(existing.blockPos(), -1, (a, b) -> (a + b <= 0) ? null : a + b);
        }
        SESSIONS.put(playerRef, new PlayerSession(entityRef, store, pos, null, 0, 0));
        WATCHER_COUNT.merge(pos, 1, Integer::sum);
        renderPage(playerRef, entityRef, store, pos, null);
    }

    /**
     * Force-open API: always opens the UI for the player, bypassing the manual-open
     * cooldown suppression used by `open`. Intended for explicit player actions
     * where the UI must appear regardless of ArcIO, cooldowns, or other blockers.
     */
    public static void openForced(PlayerRef playerRef, Ref<EntityStore> entityRef, Store<EntityStore> store, Vector3i pos) {
        PlayerSession existing = SESSIONS.get(playerRef);
        if (existing != null && existing.blockPos().equals(pos)) {
            return;
        }
        if (existing != null) {
            WATCHER_COUNT.merge(existing.blockPos(), -1, (a, b) -> (a + b <= 0) ? null : a + b);
        }
        SESSIONS.put(playerRef, new PlayerSession(entityRef, store, pos, null, 0, 0));
        WATCHER_COUNT.merge(pos, 1, Integer::sum);
        renderPage(playerRef, entityRef, store, pos, null);
    }

    /**
     * Force a periodic dynamic update for all watching players without rebuilding the full page.
     * Respects manual suppression so players who closed the UI won't be auto-updated.
     */
    public static void periodicRefresh(FertilizerState fs, Store<EntityStore> store, Vector3i pos) {
        int[] keys = computeKeys(fs);
        SESSIONS.forEach((playerRef, session) -> {
            if (!session.blockPos().equals(pos)) return;
            // Suppression check removed
            HyUIPage page = session.page();
            if (page == null) return;
            // Only update the dynamic pieces (progress/status) using the existing partialRefresh helper.
            partialRefresh(playerRef, session, fs, keys[1]);
        });
    }

    /**
     * Open the UI using a known FertilizerState instance to avoid lookup overhead.
     * Passing the state avoids a chunk/state lookup on the render path, which can
     * be slow in some edge cases and caused UI open delays.
     */
    public static void open(PlayerRef playerRef, Ref<EntityStore> entityRef, Store<EntityStore> store, Vector3i pos, FertilizerState fsHint) {
        // Suppress rapid repeated opens (e.g. held-right-click spam).
        long now = System.nanoTime();
        Long lastOpen = OPEN_COOLDOWN_NS.get(playerRef);
        if (lastOpen != null && now - lastOpen < OPEN_COOLDOWN_NANOS) return;
        OPEN_COOLDOWN_NS.put(playerRef, now);

        // Manual opens allowed unconditionally.

        PlayerSession existing = SESSIONS.get(playerRef);
        if (existing != null && existing.blockPos().equals(pos)) {
            return;
        }
        if (existing != null) {
            WATCHER_COUNT.merge(existing.blockPos(), -1, (a, b) -> (a + b <= 0) ? null : a + b);
        }
        SESSIONS.put(playerRef, new PlayerSession(entityRef, store, pos, null, 0, 0));
        WATCHER_COUNT.merge(pos, 1, Integer::sum);
        renderPage(playerRef, entityRef, store, pos, fsHint);
    }

    /**
     * Forced variant that accepts a state hint and bypasses the cooldown suppression.
     */
    public static void openForced(PlayerRef playerRef, Ref<EntityStore> entityRef, Store<EntityStore> store, Vector3i pos, FertilizerState fsHint) {
        PlayerSession existing = SESSIONS.get(playerRef);
        if (existing != null && existing.blockPos().equals(pos)) {
            return;
        }
        if (existing != null) {
            WATCHER_COUNT.merge(existing.blockPos(), -1, (a, b) -> (a + b <= 0) ? null : a + b);
        }
        SESSIONS.put(playerRef, new PlayerSession(entityRef, store, pos, null, 0, 0));
        WATCHER_COUNT.merge(pos, 1, Integer::sum);
        renderPage(playerRef, entityRef, store, pos, fsHint);
    }

    /**
     * Called from {@link FertilizerState#tick} to push updated UI to watching players.
     * When {@code forceFullRender} is true (e.g. uiDirty), always rebuilds the full page.
     * Otherwise, a structural key check decides: if only progress/status changed, an
     * incremental {@link HyUIPage#updatePage} delta is sent instead of a full page replace.
     */
    static void tickRefresh(FertilizerState fs, Store<EntityStore> entityStore, Vector3i pos,
                            boolean forceFullRender) {
        int[] keys = computeKeys(fs);
        SESSIONS.forEach((playerRef, session) -> {
            if (!session.blockPos().equals(pos)) return;
            HyUIPage page = session.page();
            if (page == null) return; // only update existing open pages — do not auto-open
            if (forceFullRender || keys[0] != session.lastStructKey()) {
                renderPage(playerRef, session.entityRef(), session.store(), pos, fs);
            } else if (keys[1] != session.lastDynKey()) {
                partialRefresh(playerRef, session, fs, keys[1]);
            }
            // else: both keys match — nothing visible changed, skip.
        });
    }

    /** Returns true if at least one player currently has this block's UI open. O(1). */
    static boolean hasWatcher(Vector3i pos) {
        Integer count = WATCHER_COUNT.get(pos);
        return count != null && count > 0;
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
            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(fs, inventory, slots))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            builder.addEventListener("close-btn", CustomUIEventBindingType.Activating, (ign, ctx) -> {
                PlayerSession s = SESSIONS.remove(playerRef);
                if (s != null) {
                    WATCHER_COUNT.merge(s.blockPos(), -1, (a, b) -> (a + b <= 0) ? null : a + b);
                }
                OPEN_COOLDOWN_NS.remove(playerRef);
                ctx.getPage().ifPresent(p -> p.close());
            });

                // When the player manually dismisses the page (Escape / F or close button),
                // record that they explicitly closed it so ticks/processing don't re-open it.
                builder.onDismiss((page, playerInitiated) -> {
                    // Treat any dismiss (Escape / F / Close) the same as the close button:
                    // remove the session, decrement watcher count, and clear the open cooldown.
                    PlayerSession s = SESSIONS.remove(playerRef);
                    if (s != null) {
                        WATCHER_COUNT.merge(s.blockPos(), -1, (a, b) -> (a + b <= 0) ? null : a + b);
                    }
                    OPEN_COOLDOWN_NS.remove(playerRef);
                });

            for (SlotInfo info : slots) {
                final ItemContainer srcContainer = info.container();
                final short srcSlot = info.slot();
                builder.addEventListener(info.id(), CustomUIEventBindingType.Activating, (ign, ctx) -> {
                    // Route to the correct block slot based on the item type:
                    // fertilizer water → slot 1 (liquid), everything else → slot 0 (fertilizer).
                    ItemStack moving = srcContainer.getItemStack(srcSlot);
                    short target = (moving != null && FertilizerState.isFertilizerWater(moving.getItemId()))
                            ? (short) 1 : (short) 0;
                    transferItem(playerRef, entityRef, store, pos, srcContainer, srcSlot, target);
                });
                builder.addEventListener(info.id(), CustomUIEventBindingType.RightClicking, (ign, ctx) ->
                        transferItem(playerRef, entityRef, store, pos, srcContainer, srcSlot, (short) 1));
            }

            HyUIPage page = builder.open(store);
            // Store the live page reference + new render keys so subsequent ticks can
            // use updatePage(false) for progress-only changes instead of rebuilding HTML.
            int[] keys = computeKeys(fs);
            SESSIONS.compute(playerRef, (k, s) -> s == null ? null
                    : new PlayerSession(s.entityRef(), s.store(), s.blockPos(), page, keys[0], keys[1]));
        } catch (Throwable t) {
            // Player may have disconnected — remove stale session.
            SESSIONS.remove(playerRef);
        }
    }

    // suppression helpers removed — UI opens only on interaction

    /**
     * Computes the two-level render keys for a {@link FertilizerState}.
     * <ul>
     *   <li>{@code keys[0]} — structural key: fertilizer type + block slot items/quantities.
     *       Changes require a full {@link #renderPage} rebuild (new HTML + event listeners).</li>
     *   <li>{@code keys[1]} — dynamic key: progress bucket + isProcessing.
     *       Changes need only a {@link #partialRefresh} (update a handful of labels/bar).</li>
     * </ul>
     */
    private static int[] computeKeys(FertilizerState fs) {
        if (fs == null) return new int[]{0, 0};
        String s0 = null, s1 = null;
        int q0 = 0, q1 = 0;
        if (fs.getItemContainer() != null) {
            ItemStack is0 = fs.getItemContainer().getItemStack((short) 0);
            ItemStack is1 = fs.getItemContainer().getItemStack((short) 1);
            if (is0 != null && !is0.isEmpty()) { s0 = is0.getItemId(); q0 = is0.getQuantity(); }
            if (is1 != null && !is1.isEmpty()) { s1 = is1.getItemId(); q1 = is1.getQuantity(); }
        }
        int structKey = Objects.hash(fs.activeFertilizerType, s0, q0, s1, q1);
        int pct = (fs.isProcessing && fs.effectiveTickInterval > 0)
                ? (int)(100.0 * fs.processingTimer / fs.effectiveTickInterval) : -1;
        // Use the same 20% bucket as FertilizerState.tick() so dynKey only changes when
        // the bucket boundary is crossed — matching the cadence that triggers tickRefresh.
        int dynKey = Objects.hash(pct / 20, fs.isProcessing);
        return new int[]{structKey, dynKey};
    }

    /**
     * Sends an incremental delta to the client updating only the four dynamic elements:
     * progress bar fill, status text, progress percentage, and next-tick countdown.
     * Avoids {@code buildHtml} string generation, {@code fromHtml} HTML parsing,
     * and {@code PageBuilder.open} full page replacement.
     */
    private static void partialRefresh(PlayerRef playerRef, PlayerSession session,
                                       FertilizerState fs, int newDynKey) {
        HyUIPage page = session.page();
        if (page == null) return;

        // ── Recompute only the values that can change between partial refreshes ──
        int progress = 0;
        String statusText  = "Idle";
        String statusColor = "#7a9aaa";
        String barColor    = "#444444";
        String nextIn      = "\u2014"; // em dash

        if (fs.isProcessing && fs.effectiveTickInterval > 0) {
            progress    = Math.min(100, (int)(100.0 * fs.processingTimer / fs.effectiveTickInterval));
            statusText  = "Active";
            barColor    = "#4caf50";
            statusColor = "#81c784";
            int ticksLeft = fs.effectiveTickInterval - fs.processingTimer;
            int seconds   = Math.max(0, ticksLeft / 30);
            nextIn = seconds + "s";
        }

        final int barFillWidth = (int)(288 * progress / 100.0);
        final String fBarColor = barColor, fStatusText = statusText,
                     fStatusColor = statusColor, fNextIn = nextIn;
        final int fProgress = progress;

        // PanelBuilder requires the raw-string withStyle overload because HyUIStyle covers only
        // text properties — layout properties (anchor-width, background-color, border-radius)
        // have no typed setters in this HyUI version.
        @SuppressWarnings("removal")
        Runnable barUpdate = () -> page.getById("fert-bar-fill", PanelBuilder.class).ifPresent(p ->
                p.withStyle("anchor-width: " + barFillWidth + "; anchor-height: 18; background-color: "
                        + fBarColor + "; border-radius: 9;"));
        barUpdate.run();

        page.getById("fert-status-val",   LabelBuilder.class).ifPresent(lb ->
                lb.withText(fStatusText).withStyle(new HyUIStyle().setTextColor(fStatusColor)));
        page.getById("fert-pct-val",      LabelBuilder.class).ifPresent(lb ->
                lb.withText(fProgress + "%").withStyle(new HyUIStyle().setTextColor(fStatusColor)));
        page.getById("fert-nexttick-val", LabelBuilder.class).ifPresent(lb ->
                lb.withText("Next tick in: " + fNextIn));

        page.updatePage(false);

        // Update session's dynKey — structKey is unchanged.
        SESSIONS.put(playerRef, new PlayerSession(session.entityRef(), session.store(),
                session.blockPos(), page, session.lastStructKey(), newDynKey));
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
        String barFill = "<div id=\"fert-bar-fill\" style=\"anchor-width: %d; anchor-height: 18; background-color: %s; border-radius: 9;\"></div>"
                .formatted(barFillWidth, barColor);

        String leftPanel = """
                <div style="layout-mode: Top; anchor-width: 320; padding-top: 8; padding-bottom: 8; padding-left: 16; padding-right: 16;">
                    <p class="title-label">Fertilizer Block</p>
                    <div class="separator"></div>

                    <p class="section-label">Status</p>
                    <p id="fert-status-val" class="info-label" style="color: %s;">%s</p>

                    <p class="section-label">Fertilizer Type</p>
                    <p class="info-label">%s</p>

                    <p class="section-label">Progress</p>
                    <div style="layout-mode: Left; anchor-width: 288; anchor-height: 18; background-color: #1a1a1a; border-radius: 9; margin-top: 4; margin-bottom: 6;">
                        %s
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center;">
                        <p id="fert-pct-val" class="pct-label" style="color: %s;">%d%%</p>
                        <p id="fert-nexttick-val" class="info-label" style="padding-left: 12;">Next tick in: %s</p>
                    </div>

                    <div class="separator"></div>

                    <div style="layout-mode: Top; horizontal-align: center; padding-top: 8;">
                        <button id="close-btn" class="secondary-button"
                                style="anchor-width: 120; anchor-height: 30; font-size: 13; color: #e57373;">&#x2715; Close</button>
                    </div>
                </div>
                """.formatted(statusColor, statusText, typeText, barFill, statusColor, progress, nextIn);

        // ── Right panel: slot inventory (processing-bench style) ─────────────
        String slot0Html = buildSlotHtml(slot0Id, slot0Qty, "Fertilizer", "fert-slot0");
        String slot1Html = buildSlotHtml(slot1Id, slot1Qty, "Liquid",      "fert-slot1");

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

    private static String buildSlotHtml(String itemId, int qty, String label, String containerId) {
        if (itemId != null) {
            String shortName = prettifyId(itemId);
            return """
                        <div id="%s" style="layout-mode: Top; horizontal-align: center; padding-top: 4; padding-bottom: 8;">
                            <p class="slot-label">%s</p>
                            <div style="layout-mode: Left; horizontal-align: center; padding-top: 4; padding-bottom: 4;">
                                <span id="%s-icon" class="item-icon" data-hyui-item-id="%s"
                                      style="anchor-width: 48; anchor-height: 48; margin-right: 8;"></span>
                                <div style="layout-mode: Top; vertical-align: middle;">
                                    <p id="%s-name" class="slot-item-name">%s</p>
                                    <p id="%s-qty" class="slot-item-qty">x%d</p>
                                </div>
                            </div>
                        </div>
                    """.formatted(containerId, label, containerId, itemId, containerId, shortName, containerId, qty);
        } else {
            return """
                        <div id="%s" style="layout-mode: Top; horizontal-align: center; padding-top: 4; padding-bottom: 8;">
                            <p class="slot-label">%s</p>
                            <div style="layout-mode: Left; horizontal-align: center; padding-top: 4; padding-bottom: 4;">
                                <div id="%s-icon" class="empty-slot"></div>
                                <p id="%s-name" class="info-label" style="padding-left: 8; vertical-align: middle;">(empty)</p>
                            </div>
                        </div>
                    """.formatted(containerId, label, containerId, containerId);
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
                        + "<span id=\"" + slotId + "-icon\" class=\"item-icon\" data-hyui-item-id=\"" + key + "\" "
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

            // Try to perform an incremental UI update on any open HyUIPage for this block.
            // If no page is present, fall back to marking uiDirty for the next tick.
            boolean updated = false;
            ItemStack newSlot0 = blockIc.getItemStack((short)0);
            ItemStack newSlot1 = blockIc.getItemStack((short)1);
            String new0Id = (newSlot0 != null && !newSlot0.isEmpty()) ? newSlot0.getItemId() : null;
            int new0Qty = (newSlot0 != null && !newSlot0.isEmpty()) ? safeQty(newSlot0) : 0;
            String new1Id = (newSlot1 != null && !newSlot1.isEmpty()) ? newSlot1.getItemId() : null;
            int new1Qty = (newSlot1 != null && !newSlot1.isEmpty()) ? safeQty(newSlot1) : 0;

            for (var entry : SESSIONS.entrySet()) {
            PlayerRef r = entry.getKey();
            PlayerSession s = entry.getValue();
            if (!Objects.equals(s.blockPos(), pos)) continue;
            HyUIPage page = s.page();
            if (page == null) continue;

            // Update block slot 0
            if (new0Id != null) {
                page.getById("fert-slot0-icon", au.ellie.hyui.builders.ItemIconBuilder.class)
                    .ifPresent(b -> b.withItemId(new0Id));
                page.getById("fert-slot0-name", LabelBuilder.class)
                    .ifPresent(lb -> lb.withText(prettifyId(new0Id)));
                page.getById("fert-slot0-qty", LabelBuilder.class)
                    .ifPresent(lb -> lb.withText("x" + new0Qty));
            } else {
                page.getById("fert-slot0-name", LabelBuilder.class)
                    .ifPresent(lb -> lb.withText("(empty)"));
                page.getById("fert-slot0-icon", au.ellie.hyui.builders.ItemIconBuilder.class)
                    .ifPresent(b -> b.withItemId(""));
                page.getById("fert-slot0-qty", LabelBuilder.class)
                    .ifPresent(lb -> lb.withText(""));
            }

            // Update block slot 1
            if (new1Id != null) {
                page.getById("fert-slot1-icon", au.ellie.hyui.builders.ItemIconBuilder.class)
                    .ifPresent(b -> b.withItemId(new1Id));
                page.getById("fert-slot1-name", LabelBuilder.class)
                    .ifPresent(lb -> lb.withText(prettifyId(new1Id)));
                page.getById("fert-slot1-qty", LabelBuilder.class)
                    .ifPresent(lb -> lb.withText("x" + new1Qty));
            } else {
                page.getById("fert-slot1-name", LabelBuilder.class)
                    .ifPresent(lb -> lb.withText("(empty)"));
                page.getById("fert-slot1-icon", au.ellie.hyui.builders.ItemIconBuilder.class)
                    .ifPresent(b -> b.withItemId(""));
                page.getById("fert-slot1-qty", LabelBuilder.class)
                    .ifPresent(lb -> lb.withText(""));
            }

            // Update the source player's inventory slot icon (hotbar or storage)
            String srcSlotId = null;
            try {
                ItemContainer hotbar = player.getInventory().getHotbar();
                if (hotbar == srcContainer) srcSlotId = "inv_h_" + srcSlot;
                else srcSlotId = "inv_s_" + srcSlot;
            } catch (Throwable ignored) {}

            if (srcSlotId != null) {
                ItemStack after = srcContainer.getItemStack(srcSlot);
                String afterId = (after != null && !after.isEmpty()) ? after.getItemId() : "";
                page.getById(srcSlotId + "-icon", au.ellie.hyui.builders.ItemIconBuilder.class)
                    .ifPresent(b -> b.withItemId(afterId));
            }

            // Push the incremental update
            page.updatePage(false);

            // Refresh the stored keys for this session
            int[] keys = computeKeys(fs);
            SESSIONS.put(r, new PlayerSession(s.entityRef(), s.store(), s.blockPos(), page, keys[0], keys[1]));
            updated = true;
            }

            if (!updated) {
            // No open pages to update — mark dirty for the next tick refresh path.
            fs.uiDirty = true;
            }
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
            case TOOL_COMPOST        -> "Compost  (60s)";
            case TOOL_SUPER_COMPOST  -> "Super Compost  (30s)";
            case TOOL_ULTRA_COMPOST -> "Super Compost (matching)  (30s)";
            case NONE           -> "None";
            default -> throw new IllegalArgumentException("Unexpected value: " + type);
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
