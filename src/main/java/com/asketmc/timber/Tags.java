package com.asketmc.timber;

import java.util.UUID;

/**
 * Scoreboard-tag constants — the ownership / cleanup "spine". We never use entity PDC for identity; a
 * felled trunk's owner rides a tag, and every entity we spawn carries a class tag so
 * {@link FelledTrunkStore#purgeTagged(String)} can find and purge orphans after a restart or /reload.
 * Display/interaction entities are also {@code setPersistent(false)} so they never even reach the region
 * file — the tags are a belt-and-braces second line of defence against entity accumulation.
 */
final class Tags {
    private Tags() {}

    /** On every animation display (the toppling rig). Swept aggressively — anim entities are ephemeral. */
    static final String ANIM = "amctimber_anim";
    /** On a landed, choppable trunk's displays + its interaction hitboxes. */
    static final String TRUNK = "amctimber_trunk";
    /** Prefix for the owner stamp on a trunk: amctimber_u_&lt;uuid&gt;. */
    static final String OWNER_PREFIX = "amctimber_u_";

    static String ownerTag(UUID owner) { return OWNER_PREFIX + owner; }
}
