package com.ssomar.score.usedapi;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player-placed blocks in memory for fast natural block detection.
 * Uses primitive long hashes to minimize GC pressure.
 *
 * Memory usage: ~24 bytes per tracked block (Long object + set overhead)
 * For 100,000 blocks: ~2.4 MB
 */
public class PlayerPlacedBlockTracker implements Listener {

    private static PlayerPlacedBlockTracker instance;

    // Using Set backed by ConcurrentHashMap for thread-safety
    private final Set<Long> placedBlocks = ConcurrentHashMap.newKeySet();

    // Configuration
    private static final int MAX_TRACKED_BLOCKS = 500_000; // ~12 MB max
    private static final int EVICTION_BATCH_SIZE = 50_000; // Remove 10% when full

    private PlayerPlacedBlockTracker() {}

    public static PlayerPlacedBlockTracker getInstance() {
        if (instance == null) {
            instance = new PlayerPlacedBlockTracker();
        }
        return instance;
    }

    /**
     * Compute a unique hash for a block location using FNV-1a.
     * This is GC-free (primitive long) and fast.
     */
    private static long computeBlockHash(World world, int x, int y, int z) {
        long hash = 0xcbf29ce484222325L; // FNV offset basis
        final long prime = 0x100000001b3L; // FNV prime

        // Mix in world UUID
        hash ^= world.getUID().getMostSignificantBits();
        hash *= prime;
        hash ^= world.getUID().getLeastSignificantBits();
        hash *= prime;

        // Mix in coordinates
        hash ^= x;
        hash *= prime;
        hash ^= y;
        hash *= prime;
        hash ^= z;
        hash *= prime;

        return hash;
    }

    /**
     * Compute hash for a Block object.
     */
    public static long computeBlockHash(Block block) {
        return computeBlockHash(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        long hash = computeBlockHash(event.getBlock());
        placedBlocks.add(hash);

        // Evict old entries if over capacity
        if (placedBlocks.size() > MAX_TRACKED_BLOCKS) {
            evictOldEntries();
        }
    }

    // Note: We intentionally do NOT remove blocks on break.
    // Once a location has been player-placed, it stays "tainted" to prevent
    // exploit cycles of: place -> break -> place -> break -> ...
    // The location only becomes natural again after server restart or eviction.

    /**
     * Check if a block was placed by a player (not natural).
     * @return true if the block was player-placed, false if natural
     */
    public boolean isPlayerPlaced(Block block) {
        return placedBlocks.contains(computeBlockHash(block));
    }

    /**
     * Check if a block is natural (not player-placed).
     * @return true if the block is natural, false if player-placed
     */
    public boolean isNatural(Block block) {
        return !isPlayerPlaced(block);
    }

    /**
     * Manually mark a block as player-placed.
     */
    public void markAsPlaced(Block block) {
        placedBlocks.add(computeBlockHash(block));
    }

    /**
     * Manually mark a block as natural (remove from tracking).
     */
    public void markAsNatural(Block block) {
        placedBlocks.remove(computeBlockHash(block));
    }

    /**
     * Get current number of tracked blocks.
     */
    public int getTrackedCount() {
        return placedBlocks.size();
    }

    /**
     * Clear all tracked blocks.
     */
    public void clear() {
        placedBlocks.clear();
    }

    /**
     * Evict old entries when over capacity.
     * Uses simple FIFO-ish eviction via iterator.
     */
    private void evictOldEntries() {
        Iterator<Long> iterator = placedBlocks.iterator();
        int removed = 0;
        while (iterator.hasNext() && removed < EVICTION_BATCH_SIZE) {
            iterator.next();
            iterator.remove();
            removed++;
        }
    }
}
