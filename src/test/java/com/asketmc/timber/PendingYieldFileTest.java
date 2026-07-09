package com.asketmc.timber;

import org.bukkit.Material;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PendingYieldFileTest {
    @TempDir
    Path tempDir;

    @Test
    @Tag("P0")
    void roundTripsValidatedRecoveryStateAndDeletesEmptyState() throws IOException {
        Path file = tempDir.resolve("pending-yields.properties");
        PendingYieldFile.Entry entry = new PendingYieldFile.Entry(
                UUID.randomUUID(), UUID.randomUUID(), 12.5, 64, -7.25, Material.OAK_LOG, 130);

        PendingYieldFile.write(file, List.of(entry));
        assertEquals(List.of(entry), PendingYieldFile.read(file));
        PendingYieldFile.write(file, List.of());
        assertFalse(Files.exists(file));
    }

    @Test
    void rejectsUnknownSchemaAndMalformedEntries() throws IOException {
        Path file = tempDir.resolve("pending-yields.properties");
        Files.writeString(file, "schema=unknown\ncount=0\n");
        assertThrows(IOException.class, () -> PendingYieldFile.read(file));

        Files.writeString(file, "schema=" + PendingYieldFile.SCHEMA + "\ncount=1\n"
                + "entry.0.id=" + UUID.randomUUID() + "\n"
                + "entry.0.world=" + UUID.randomUUID() + "\n"
                + "entry.0.x=0\nentry.0.y=64\nentry.0.z=0\n"
                + "entry.0.material=DIAMOND_BLOCK\nentry.0.amount=1\n");
        assertThrows(IOException.class, () -> PendingYieldFile.read(file));

        Files.writeString(file, "schema=" + PendingYieldFile.SCHEMA + "\ncount=not-a-number\n");
        assertThrows(IOException.class, () -> PendingYieldFile.read(file));
    }
}
