package com.asketmc.timber;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BlockKeyTest {
    @Test
    void identicalCoordinatesInDifferentWorldsRemainDistinct() {
        UUID firstWorld = UUID.randomUUID();
        UUID secondWorld = UUID.randomUUID();

        assertNotEquals(new BlockKey(firstWorld, 10, 64, -20), new BlockKey(secondWorld, 10, 64, -20));
        assertEquals(new BlockKey(firstWorld, 10, 64, -20), new BlockKey(firstWorld, 10, 64, -20));
    }
}
