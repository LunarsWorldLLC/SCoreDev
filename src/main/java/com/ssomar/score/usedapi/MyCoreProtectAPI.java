package com.ssomar.score.usedapi;

import com.ssomar.score.SCore;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Queue;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MyCoreProtectAPI {

    // Cache configuration - using primitive long keys to minimize GC pressure
    private static final Map<Long, CachedNaturalResult> naturalBlockCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 8192;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes
    private static final int LOOKUP_TIME_SECONDS = 86400; // 24 hours (reduced for faster queries)

    // Cleanup tracking - volatile for visibility across threads
    private static volatile long lastCleanupTime = 0;
    private static final long CLEANUP_INTERVAL_MS = 30 * 1000L; // 30 seconds

    // Lightweight cache entry - minimal object overhead
    private static final class CachedNaturalResult {
        final boolean isNatural;
        final long expiryTime;

        CachedNaturalResult(boolean isNatural, long expiryTime) {
            this.isNatural = isNatural;
            this.expiryTime = expiryTime;
        }
    }

    // Compute cache key using FNV-1a hash - fast and GC-free
    private static long computeCacheKey(Block block) {
        long hash = 0xcbf29ce484222325L; // FNV offset basis
        final long prime = 0x100000001b3L; // FNV prime

        // Mix in world UUID
        hash ^= block.getWorld().getUID().getMostSignificantBits();
        hash *= prime;
        hash ^= block.getWorld().getUID().getLeastSignificantBits();
        hash *= prime;

        // Mix in coordinates
        hash ^= block.getX();
        hash *= prime;
        hash ^= block.getY();
        hash *= prime;
        hash ^= block.getZ();
        hash *= prime;

        return hash;
    }

    // Get CoreProtect API instance, returns null if unavailable
    private static CoreProtectAPI getCoreProtectAPI() {
        if (!SCore.hasCoreProtect) return null;

        Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("CoreProtect");
        if (plugin == null || !plugin.isEnabled()) return null;
        if (!(plugin instanceof CoreProtect)) return null;

        CoreProtectAPI api = ((CoreProtect) plugin).getAPI();
        if (!api.isEnabled()) return null;
        if (api.APIVersion() < 9) return null;

        return api;
    }

    // Periodic cache cleanup - amortized O(1) per call
    private static void cleanupCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) return;
        lastCleanupTime = now;

        // Remove expired entries
        naturalBlockCache.entrySet().removeIf(e -> now > e.getValue().expiryTime);

        // Evict if over capacity (simple FIFO-ish eviction)
        if (naturalBlockCache.size() > MAX_CACHE_SIZE) {
            int toRemove = naturalBlockCache.size() - (MAX_CACHE_SIZE * 3 / 4);
            Iterator<Map.Entry<Long, CachedNaturalResult>> iterator = naturalBlockCache.entrySet().iterator();
            while (iterator.hasNext() && toRemove > 0) {
                iterator.next();
                iterator.remove();
                toRemove--;
            }
        }
    }

    public static void logRemoval(String user, Location location, Material type, BlockData blockData) {
        if (SCore.hasCoreProtect) {
            Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("CoreProtect");

            if (plugin == null || !plugin.isEnabled()) return;

            // Check that CoreProtect is loaded
            if (!(plugin instanceof CoreProtect)) {
                return;
            }

            CoreProtectAPI CoreProtect = ((CoreProtect) plugin).getAPI();
            if (!CoreProtect.isEnabled()) {
                return;
            }

            // Check that a compatible version of the API is loaded
            if (CoreProtect.APIVersion() < 9) {
                return;
            }

            CoreProtect.logRemoval(user, location, type, blockData);
        }
    }

    /**
     * Check if a block is natural (not player-placed) using CoreProtect.
     * Results are cached to minimize database queries.
     */
    public static boolean isNaturalBlock(Block block) {
        CoreProtectAPI api = getCoreProtectAPI();
        if (api == null) return false;

        long cacheKey = computeCacheKey(block);
        long now = System.currentTimeMillis();

        // Fast path: check cache first
        CachedNaturalResult cached = naturalBlockCache.get(cacheKey);
        if (cached != null && now < cached.expiryTime) {
            return cached.isNatural;
        }

        // Trigger cleanup periodically (amortized)
        cleanupCacheIfNeeded();

        // Cache miss: use async API to avoid main thread warning, wait for result
        // The async lookup runs on a different thread, .join() waits for completion
        boolean isNatural;
        try {
            List<String[]> list = api.blockLookupAsync(block, LOOKUP_TIME_SECONDS).join();
            isNatural = list == null || list.isEmpty();
        } catch (Exception e) {
            // Fallback to sync if async fails
            List<String[]> list = api.blockLookup(block, LOOKUP_TIME_SECONDS);
            isNatural = list == null || list.isEmpty();
        }

        // Cache the result
        naturalBlockCache.put(cacheKey, new CachedNaturalResult(isNatural, now + CACHE_TTL_MS));

        return isNatural;
    }

    /**
     * Invalidate cache entry for a specific block location.
     * Call this when a block is placed or broken.
     */
    public static void invalidateNaturalBlockCache(Block block) {
        naturalBlockCache.remove(computeCacheKey(block));
    }

    /**
     * Clear the entire natural block cache.
     */
    public static void clearNaturalBlockCache() {
        naturalBlockCache.clear();
    }

    public void addPickup(Location location, ItemStack itemStack, Player player) {
        if (SCore.hasCoreProtect) {
            Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("CoreProtect");

            if (plugin == null || !plugin.isEnabled()) return;

            // Check that CoreProtect is loaded
            if (!(plugin instanceof CoreProtect)) {
                return;
            }

            CoreProtectAPI CoreProtect = ((CoreProtect) plugin).getAPI();
            if (!CoreProtect.isEnabled()) {
                return;
            }

            // Check that a compatible version of the API is loaded
            if (CoreProtect.APIVersion() < 9) {
                return;
            }

            if (!(Config.getConfig(location.getWorld())).ITEM_PICKUPS)
                return;

            if (itemStack == null)
                return;
            String loggingItemId = player.getName().toLowerCase(Locale.ROOT) + "." + location.getBlockX() + "." + location.getBlockY() + "." + location.getBlockZ();
            int itemId = new Register().getItem(loggingItemId);
            List<ItemStack> list = (List<ItemStack>) ConfigHandler.itemsPickup.getOrDefault(loggingItemId, new ArrayList());
            list.add(itemStack.clone());
            ConfigHandler.itemsPickup.put(loggingItemId, list);
            int time = (int) (System.currentTimeMillis() / 1000L) + 1;
            new Register().addItemTransaction(player, location.clone(), time, itemId);
        }

    }

    static class Register extends Queue {
        public Register() {

        }

        public static int getItem(String loggingItemId) {
            return getItemId(loggingItemId);
        }

        public void addItemTransaction(Player player, Location location, int time, int itemId) {
            Queue.queueItemTransaction(player.getName(), location.clone(), time, 0, itemId);
        }
    }

}
