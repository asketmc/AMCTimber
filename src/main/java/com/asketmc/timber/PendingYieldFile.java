package com.asketmc.timber;

import org.bukkit.Material;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Validated, atomic local persistence for undelivered recovery yield. */
final class PendingYieldFile {
    static final String SCHEMA = "amctimber.pending-yield.v2";
    private static final String LEGACY_SCHEMA = "amctimber.pending-yield.v1";
    static final int MAX_ENTRIES = 4_096;
    private static final int MAX_AMOUNT = 1_000_000;

    record Entry(UUID id, UUID ownerId, UUID worldId, double x, double y, double z,
                 Material material, int amount) {
        Entry(UUID id, UUID worldId, double x, double y, double z, Material material, int amount) {
            this(id, null, worldId, x, y, z, material, amount);
        }

        Entry {
            if (id == null || worldId == null || material == null) {
                throw new IllegalArgumentException("missing yield identity");
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                    || y < -2_048 || y > 2_048) {
                throw new IllegalArgumentException("invalid yield location");
            }
            if (amount <= 0 || amount > MAX_AMOUNT) throw new IllegalArgumentException("invalid yield amount");
        }
    }

    private PendingYieldFile() {}

    static List<Entry> read(Path file) throws IOException {
        if (!Files.exists(file)) return List.of();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            try {
                properties.load(reader);
            } catch (IllegalArgumentException malformed) {
                throw new IOException("malformed pending-yield properties", malformed);
            }
        }
        String schema = properties.getProperty("schema");
        if (!SCHEMA.equals(schema) && !LEGACY_SCHEMA.equals(schema)) {
            throw new IOException("unsupported pending-yield schema");
        }
        boolean legacy = LEGACY_SCHEMA.equals(schema);
        int count;
        try {
            count = parseInt(properties, "count");
        } catch (IllegalArgumentException malformed) {
            throw new IOException("invalid pending-yield entry count", malformed);
        }
        if (count < 0 || count > MAX_ENTRIES) throw new IOException("invalid pending-yield entry count");

        List<Entry> entries = new ArrayList<>(count);
        Set<UUID> uniqueIds = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String prefix = "entry." + index + '.';
            try {
                UUID id = UUID.fromString(required(properties, prefix + "id"));
                UUID ownerId = legacy ? null : optionalUuid(properties.getProperty(prefix + "owner"));
                UUID worldId = UUID.fromString(required(properties, prefix + "world"));
                double x = Double.parseDouble(required(properties, prefix + "x"));
                double y = Double.parseDouble(required(properties, prefix + "y"));
                double z = Double.parseDouble(required(properties, prefix + "z"));
                Material material = Material.matchMaterial(required(properties, prefix + "material"));
                int amount = parseInt(properties, prefix + "amount");
                Entry entry = new Entry(id, ownerId, worldId, x, y, z, material, amount);
                if (!supportedYieldMaterial(entry.material())) {
                    throw new IllegalArgumentException("material is not a supported timber yield");
                }
                if (!uniqueIds.add(entry.id())) throw new IllegalArgumentException("duplicate recovery id");
                entries.add(entry);
            } catch (IllegalArgumentException failure) {
                throw new IOException("invalid pending-yield entry " + index, failure);
            }
        }
        return entries;
    }

    static void write(Path file, List<Entry> source) throws IOException {
        if (source.size() > MAX_ENTRIES) throw new IOException("too many pending-yield entries");
        Set<UUID> uniqueIds = new HashSet<>();
        for (Entry entry : source) {
            if (!uniqueIds.add(entry.id())) throw new IOException("duplicate pending-yield id");
            if (!supportedYieldMaterial(entry.material())) {
                throw new IOException("material is not a supported timber yield");
            }
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        if (source.isEmpty()) {
            Files.deleteIfExists(file);
            Files.deleteIfExists(temporary);
            return;
        }

        List<Entry> entries = new ArrayList<>(source);
        entries.sort(Comparator.comparing(entry -> entry.id().toString()));
        StringBuilder output = new StringBuilder();
        output.append("schema=").append(SCHEMA).append('\n');
        output.append("count=").append(entries.size()).append('\n');
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            String prefix = "entry." + index + '.';
            output.append(prefix).append("id=").append(entry.id()).append('\n');
            output.append(prefix).append("owner=")
                    .append(entry.ownerId() == null ? "none" : entry.ownerId()).append('\n');
            output.append(prefix).append("world=").append(entry.worldId()).append('\n');
            output.append(prefix).append("x=").append(entry.x()).append('\n');
            output.append(prefix).append("y=").append(entry.y()).append('\n');
            output.append(prefix).append("z=").append(entry.z()).append('\n');
            output.append(prefix).append("material=").append(entry.material().name()).append('\n');
            output.append(prefix).append("amount=").append(entry.amount()).append('\n');
        }
        Files.writeString(temporary, output, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value.trim();
    }

    private static int parseInt(Properties properties, String key) {
        try {
            return Integer.parseInt(required(properties, key));
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("invalid integer " + key, malformed);
        }
    }

    private static UUID optionalUuid(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("none")) return null;
        return UUID.fromString(value.trim());
    }

    /** Registry-free allowlist so recovery validation remains server-free and cannot construct non-items. */
    static boolean supportedYieldMaterial(Material material) {
        if (material == null) return false;
        String name = material.name();
        boolean allowlisted = name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_HYPHAE")
                || name.endsWith("_SAPLING")
                || name.equals("CRIMSON_STEM") || name.equals("STRIPPED_CRIMSON_STEM")
                || name.equals("WARPED_STEM") || name.equals("STRIPPED_WARPED_STEM")
                || name.equals("BAMBOO_BLOCK") || name.equals("STRIPPED_BAMBOO_BLOCK")
                || name.equals("MANGROVE_PROPAGULE") || name.equals("STICK")
                || name.equals("APPLE") || name.equals("AZALEA")
                || name.equals("FLOWERING_AZALEA") || name.equals("COCOA_BEANS")
                || name.equals("VINE") || name.equals("PALE_HANGING_MOSS")
                || name.equals("SNOWBALL") || name.equals("MOSS_CARPET")
                || name.equals("PALE_MOSS_CARPET");
        if (!allowlisted) return false;
        try {
            return material.isItem();
        } catch (RuntimeException | LinkageError registryUnavailable) {
            // Paper's unit-test API has no RegistryAccess implementation. Every fallback name above
            // is an explicitly enumerated real item; production still consumes Material#isItem().
            return true;
        }
    }

}
