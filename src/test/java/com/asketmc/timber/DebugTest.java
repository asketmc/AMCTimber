package com.asketmc.timber;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugTest {

    @Test
    void offSuppressesRoutineMessagesButKeepsWarningsAndErrors() {
        CapturingHandler handler = new CapturingHandler();
        Debug debug = debugWith(handler);
        debug.setLevel("off");

        debug.info("lifecycle");
        debug.full("per-fell detail");
        debug.warn("warning");
        debug.severe("error");

        assertEquals(List.of("WARNING:warning", "SEVERE:error"), handler.messages);
    }

    @Test
    void infoSuppressesPerFellDiagnostics() {
        CapturingHandler handler = new CapturingHandler();
        Debug debug = debugWith(handler);
        debug.setLevel("info");

        debug.info("lifecycle");
        debug.full("per-fell detail");

        assertEquals(List.of("INFO:lifecycle"), handler.messages);
    }

    @Test
    void fullIncludesPerFellDiagnostics() {
        CapturingHandler handler = new CapturingHandler();
        Debug debug = debugWith(handler);
        debug.setLevel("full");

        debug.info("lifecycle");
        debug.full("per-fell detail");

        assertEquals(List.of("INFO:lifecycle", "INFO:per-fell detail"), handler.messages);
    }

    private static Debug debugWith(Handler handler) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return new Debug(logger);
    }

    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getLevel() + ":" + record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
