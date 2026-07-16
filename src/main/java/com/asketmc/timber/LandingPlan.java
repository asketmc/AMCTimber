package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable destination geometry shared by authorization and every landing consumer. */
final class LandingPlan {
    record EntityCell(Location location, Material material) {
        EntityCell { location = location.clone(); }
    }

    private record EntityKey(int x, int y, int z, Material material) {}

    final double yDrop;
    final List<double[]> leafImpactPositions;
    final List<Location> hitboxLocations;
    final Location recoveryLocation;
    final Location logDropLocation;
    final List<EntityCell> entityCells;

    private LandingPlan(double yDrop, List<double[]> leafImpactPositions,
                        List<Location> hitboxLocations, Location recoveryLocation,
                        Location logDropLocation, List<EntityCell> entityCells) {
        this.yDrop = yDrop;
        this.leafImpactPositions = List.copyOf(leafImpactPositions);
        this.hitboxLocations = cloneLocations(hitboxLocations);
        this.recoveryLocation = recoveryLocation.clone();
        this.logDropLocation = logDropLocation.clone();
        this.entityCells = List.copyOf(entityCells);
    }

    static LandingPlan compute(TreeShape shape, TimberConfig cfg, double yDrop) {
        Quaternionf rest = ToppleAnimator.quat(ToppleAnimator.axis(shape.dirX, shape.dirZ),
                Math.toRadians(cfg.fallRestDegrees));
        Map<EntityKey, EntityCell> footprint = new LinkedHashMap<>();

        for (TreeShape.Node node : shape.logs) addRotatedCube(footprint, shape, node, rest, yDrop);

        int leafBudget = Math.max(0, cfg.maxDisplayEntities - shape.logs.size());
        List<TreeShape.Node> renderedLeaves = ToppleAnimator.decimate(shape.leaves, leafBudget);
        List<double[]> leafImpact = new ArrayList<>();
        for (TreeShape.Node node : renderedLeaves) addRotatedCube(footprint, shape, node, rest, yDrop);
        for (TreeShape.Node node : ToppleAnimator.decimate(renderedLeaves, 40)) {
            leafImpact.add(ToppleAnimator.landedPos(node, shape.pivotX, shape.pivotY,
                    shape.pivotZ, rest, yDrop));
        }

        double groundY = shape.pivotY - yDrop;
        List<Location> hitboxes = new ArrayList<>();
        int boxes = FelledTrunk.hitboxCount(shape.height);
        for (int index = 0; index < boxes; index++) {
            double distance = (index + 0.5) * shape.height / boxes;
            Location location = new Location(shape.world,
                    shape.pivotX + shape.dirX * distance, groundY,
                    shape.pivotZ + shape.dirZ * distance);
            hitboxes.add(location);
            addAabb(footprint, shape.world, shape.baseMaterial,
                    location.getX() - 0.85, location.getY(), location.getZ() - 0.85,
                    location.getX() + 0.85, location.getY() + 1.4, location.getZ() + 0.85);
        }

        Location recovery = centered(shape.world, shape.pivotX, groundY + 0.5, shape.pivotZ);
        Location logDrop = centered(shape.world,
                shape.pivotX + shape.dirX * Math.max(2.0, shape.height) * 0.5,
                groundY + 0.5,
                shape.pivotZ + shape.dirZ * Math.max(2.0, shape.height) * 0.5);

        return new LandingPlan(yDrop, leafImpact, hitboxes, recovery, logDrop,
                new ArrayList<>(footprint.values()));
    }

    private static void addRotatedCube(Map<EntityKey, EntityCell> footprint, TreeShape shape,
                                       TreeShape.Node node, Quaternionf rotation, double yDrop) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    Vector3f offset = new Vector3f((float) (node.x + dx - shape.pivotX),
                            (float) (node.y + dy - shape.pivotY),
                            (float) (node.z + dz - shape.pivotZ));
                    new Quaternionf(rotation).transform(offset);
                    double x = shape.pivotX + offset.x;
                    double y = shape.pivotY + offset.y - yDrop;
                    double z = shape.pivotZ + offset.z;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
            }
        }
        addAabb(footprint, shape.world, node.data.getMaterial(), minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void addAabb(Map<EntityKey, EntityCell> footprint, org.bukkit.World world,
                                Material material, double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ) {
        int fromX = (int) Math.floor(minX);
        int fromY = (int) Math.floor(minY);
        int fromZ = (int) Math.floor(minZ);
        int toX = (int) Math.floor(Math.nextDown(maxX));
        int toY = (int) Math.floor(Math.nextDown(maxY));
        int toZ = (int) Math.floor(Math.nextDown(maxZ));
        for (int x = fromX; x <= toX; x++) {
            for (int y = fromY; y <= toY; y++) {
                for (int z = fromZ; z <= toZ; z++) {
                    EntityKey key = new EntityKey(x, y, z, material);
                    footprint.putIfAbsent(key,
                            new EntityCell(new Location(world, x, y, z), material));
                }
            }
        }
    }

    private static Location centered(org.bukkit.World world, double x, double y, double z) {
        return new Location(world, Math.floor(x) + 0.5, y, Math.floor(z) + 0.5);
    }

    private static List<Location> cloneLocations(List<Location> source) {
        List<Location> copy = new ArrayList<>(source.size());
        for (Location location : source) copy.add(location.clone());
        return List.copyOf(copy);
    }
}
