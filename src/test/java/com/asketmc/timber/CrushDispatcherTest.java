package com.asketmc.timber;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrushDispatcherTest {
    @Test
    @Tag("P0")
    void alignedJobsShareQueryTokensAndProgressRoundRobin() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("runtime-work.crush-queries-per-tick", 2);
        yaml.set("runtime-work.crush-targets-per-tick", 2);
        yaml.set("runtime-work.crush-max-micros-per-tick", 10_000);
        TimberConfig cfg = new TimberConfig(yaml);
        RuntimeWorkLimiter limiter = new RuntimeWorkLimiter(cfg);
        CrushDispatcher dispatcher = new CrushDispatcher(limiter, cfg);
        AtomicInteger steps = new AtomicInteger();

        for (int job = 0; job < 4; job++) {
            assertTrue(dispatcher.submit(new CrushDispatcher.Job() {
                private int remaining = 2;
                @Override public CrushDispatcher.Step step(RuntimeWorkLimiter work) {
                    if (!work.takeCrushQuery()) return CrushDispatcher.Step.BLOCKED;
                    steps.incrementAndGet();
                    return --remaining == 0 ? CrushDispatcher.Step.DONE : CrushDispatcher.Step.PROGRESS;
                }
            }));
        }

        limiter.beginTick();
        dispatcher.tick();
        assertEquals(2, steps.get());
        limiter.beginTick();
        dispatcher.tick();
        assertEquals(4, steps.get());
        assertEquals(4, dispatcher.size());
        for (int tick = 0; tick < 2; tick++) {
            limiter.beginTick();
            dispatcher.tick();
        }
        assertEquals(8, steps.get());
        assertEquals(0, dispatcher.size());
    }
}
