package com.asketmc.timber;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Progress bar glyphs + trunk shrink / hitbox geometry")
class GameMathTest {

    @Test
    void bar_fillsProgressAndClampsBothEnds() {
        assertEquals("■■□□", Messages.bar(2, 4));
        assertEquals("□□□", Messages.bar(0, 3));
        assertEquals("■■■■", Messages.bar(9, 4)); // clamp above required
        assertEquals("□□", Messages.bar(-1, 2));   // clamp below zero
    }

    @Test
    void keepCount_shrinksProportionallyAndHitsZeroOnDone() {
        assertEquals(9, FelledTrunk.keepCount(12, 4, 1));
        assertEquals(3, FelledTrunk.keepCount(12, 4, 3));
        assertEquals(0, FelledTrunk.keepCount(12, 4, 4));  // last required hit -> empty
        assertEquals(0, FelledTrunk.keepCount(12, 4, 99)); // past done -> empty
    }

    @Test
    void keepCount_smallTrunkNeverEmptiesBeforeTheFinalHit() {
        for (int progress = 1; progress < 10; progress++) {
            assertTrue(FelledTrunk.keepCount(3, 10, progress) >= 1, "progress=" + progress);
        }
        assertEquals(0, FelledTrunk.keepCount(3, 10, 10));
    }

    @Test
    void hitboxCount_onePerTwoAndAHalfBlocksCappedAtEight() {
        assertEquals(1, FelledTrunk.hitboxCount(1));
        assertEquals(1, FelledTrunk.hitboxCount(2.5));
        assertEquals(2, FelledTrunk.hitboxCount(2.6));
        assertEquals(3, FelledTrunk.hitboxCount(6));
        assertEquals(8, FelledTrunk.hitboxCount(30)); // capped
    }
}
