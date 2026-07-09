package com.asketmc.timber;

import java.util.Objects;
import java.util.UUID;

/** World-aware identity for one felling origin. */
record BlockKey(UUID worldId, int x, int y, int z) {
    BlockKey {
        Objects.requireNonNull(worldId, "worldId");
    }

    static BlockKey from(TreeShape shape) {
        return new BlockKey(shape.world.getUID(), shape.cutX, shape.cutY, shape.cutZ);
    }
}
