package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

/** Confirms that a requested world drop entered the world before yield is acknowledged. */
final class ItemDelivery {
    private ItemDelivery() {}

    static int tryDeliver(World world, Location location, ItemStack stack) {
        int requested = stack.getAmount();
        try {
            // The sink is pre-authorized as one exact cell; random natural offsets could cross its boundary.
            Item dropped = world.dropItem(location, stack);
            if (dropped != null && dropped.isValid() && dropped.isInWorld()) return requested;
        } catch (RuntimeException | LinkageError ignored) {
            // The caller retains its ledger and decides whether to retry interactively or in the queue.
        }
        return 0;
    }
}
