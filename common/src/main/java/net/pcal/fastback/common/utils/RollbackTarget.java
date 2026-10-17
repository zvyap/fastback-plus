package net.pcal.fastback.common.utils;

import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.worldupdate.UpgradeProgress;
import net.minecraft.util.filefix.FileFixException;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.IOException;
import java.io.DataInputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.BitSet;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;
import static net.pcal.fastback.common.logging.SystemLogger.syslog;

/** A selection in the runtime world layout, applied only after all world IO has closed. */
public record RollbackTarget(Kind kind, Identifier dimension, int x, int z, UUID playerId) {

    public enum Kind { REGION, DIMENSION, PLAYER, CHUNK }

    private static final List<String> CHUNK_STORES = List.of("region", "entities", "poi");
    private static final Pattern EXTERNAL_CHUNK = Pattern.compile("c\\.(-?\\d+)\\.(-?\\d+)\\.mcc");

    public RollbackTarget {
        requireNonNull(kind);
        if (kind == Kind.PLAYER) {
            requireNonNull(playerId);
        } else {
            requireNonNull(dimension);
            // Identifiers allow dots and slashes; neither may escape the dimension's namespace.
            final Path relative = Path.of("dimensions", dimension.getNamespace()).resolve(dimension.getPath());
            if (dimension.getNamespace().equals(".") || dimension.getNamespace().equals("..")
                    || dimension.getPath().startsWith("/") || !relative.equals(relative.normalize())
                    || List.of(dimension.getPath().split("/", -1)).stream().anyMatch(String::isEmpty)) {
                throw new IllegalArgumentException("Unsafe dimension ID: " + dimension);
            }
            if (kind == Kind.REGION && (x < Integer.MIN_VALUE / 32 || x > Integer.MAX_VALUE / 32
                    || z < Integer.MIN_VALUE / 32 || z > Integer.MAX_VALUE / 32)) {
                throw new IllegalArgumentException("Region coordinates exceed chunk coordinate limits");
            }
        }
    }

    public Path dimensionPath() {
        return Path.of("dimensions", dimension.getNamespace()).resolve(dimension.getPath());
    }

    public static RollbackTarget atBlock(Kind kind, Identifier dimension, BlockPos pos) {
        if (kind != Kind.REGION && kind != Kind.CHUNK) throw new IllegalArgumentException("Expected region or chunk");
        final int width = kind == Kind.REGION ? 512 : 16;
        return new RollbackTarget(kind, dimension, Math.floorDiv(pos.getX(), width), Math.floorDiv(pos.getZ(), width), null);
    }

    /** Upgrade only the disposable extraction, using the running Minecraft version's own migrations. */
    public void prepareSnapshot(Path snapshot) throws IOException {
        final CompoundTag data = NbtIo.readCompressed(snapshot.resolve("level.dat"), NbtAccounter.create(64L * 1024 * 1024))
                .getCompoundOrEmpty("Data");
        final int version = data.getIntOr("DataVersion", -1);
        final int current = SharedConstants.getCurrentVersion().dataVersion().version();
        if (version < 0 || version > current) {
            throw new IOException("Unsupported snapshot data version " + version + "; running version is " + current);
        }
        Executor.checkCancelled();
        final var fixer = DataFixers.getFileFixer();
        if (fixer.requiresFileFixing(version)) {
            final Path selected = kind == Kind.PLAYER ? null : snapshot.resolve(dimensionPath());
            Path relocated = null;
            // Vanilla discovers two-level dimension paths; flatten a selected nested path during migration.
            if (selected != null && dimension.getPath().contains("/") && Files.isDirectory(selected, NOFOLLOW_LINKS)) {
                relocated = snapshot.resolve("dimensions/fastback_rollback_" + UUID.randomUUID()).resolve("selected");
                Files.createDirectories(relocated.getParent());
                Files.move(selected, relocated);
            }
            final var storage = LevelStorageSource.createDefault(snapshot.getParent());
            try (var access = storage.createAccess(snapshot.getFileName().toString())) {
                fixer.fix(access, access.getUnfixedDataTag(false), new UpgradeProgress());
            } catch (FileFixException e) {
                throw new IOException("Could not upgrade extracted snapshot " + snapshot, e);
            }
            Files.deleteIfExists(snapshot.resolve("session.lock"));
            if (relocated != null) {
                // Refuse conflicting files rather than overwriting data created by Minecraft's migrations.
                copyTree(relocated, selected, false);
                FileUtils.rmdir(relocated);
                Files.delete(relocated.getParent());
            }
            Executor.checkCancelled();
        }
    }

    public int regionX() { return kind == Kind.CHUNK ? Math.floorDiv(x, 32) : x; }
    public int regionZ() { return kind == Kind.CHUNK ? Math.floorDiv(z, 32) : z; }
    public long minChunkX() { return kind == Kind.CHUNK ? x : (long) x * 32; }
    public long minChunkZ() { return kind == Kind.CHUNK ? z : (long) z * 32; }
    public long maxChunkX() { return minChunkX() + (kind == Kind.CHUNK ? 0 : 31); }
    public long maxChunkZ() { return minChunkZ() + (kind == Kind.CHUNK ? 0 : 31); }

    /** Reject absent selections before stopping the server; a missing target is never an implicit reset. */
    public void validateSnapshot(Path snapshot) throws IOException {
        if (kind == Kind.PLAYER) {
            final Path data = snapshot.resolve("players/data/" + playerId + ".dat");
            if (NbtIo.readCompressed(data, NbtAccounter.create(64L * 1024 * 1024)).isEmpty()) {
                throw new IOException("Snapshot has empty player data for " + playerId);
            }
        } else if (kind == Kind.DIMENSION) {
            if (!Files.isDirectory(snapshot.resolve(dimensionPath()), NOFOLLOW_LINKS)) {
                throw new IOException("Snapshot has no dimension " + dimension);
            }
            for (String store : CHUNK_STORES) {
                final Path directory = snapshot.resolve(dimensionPath()).resolve(store);
                if (!Files.exists(directory)) continue;
                try (var files = Files.newDirectoryStream(directory, "*.mca")) {
                    for (Path file : files) {
                        Executor.checkCancelled();
                        validateRegionHeader(file);
                    }
                }
            }
        } else {
            final Path terrain = snapshot.resolve(dimensionPath()).resolve("region").resolve(regionFileName());
            if (!Files.isRegularFile(terrain, NOFOLLOW_LINKS)) {
                throw new IOException("Snapshot has no region " + regionX() + ", " + regionZ() + " in " + dimension);
            }
            for (String store : CHUNK_STORES) {
                final Path file = snapshot.resolve(dimensionPath()).resolve(store).resolve(regionFileName());
                if (kind == Kind.CHUNK) {
                    if (readChunk(file, store) == null && store.equals("region")) {
                        throw new IOException("Snapshot has no chunk " + x + ", " + z + " in " + dimension);
                    }
                } else if (Files.exists(file)) {
                    validateRegionHeader(file);
                    try (RegionFile region = openRegion(file, store)) {
                        for (int dx = 0; dx < 32; dx++) {
                            for (int dz = 0; dz < 32; dz++) {
                                Executor.checkCancelled();
                                readChunk(region, new ChunkPos(x * 32 + dx, z * 32 + dz));
                            }
                        }
                    }
                }
            }
        }
    }

    /** Build from the final saved world, then reuse the full-world atomic swap and recovery marker. */
    public void install(Path world, Path snapshot, Path previous) throws IOException {
        validateSnapshot(snapshot);
        final Path merged = Files.createTempDirectory(world.getParent(), ".fastback-rollback-");
        syslog().info("Preparing partial rollback at " + merged + " from " + snapshot + " for " + this);
        // shortcut: copy the closed world for atomic recovery; use a journal if large-world disk costs require it.
        copyTree(world, merged, true);
        apply(snapshot, merged);
        ServerWorldRestore.install(world, merged, previous);
        try {
            FileUtils.rmdir(snapshot);
        } catch (IOException e) {
            syslog().error("Rollback installed, but could not remove staged snapshot " + snapshot, e);
        }
    }

    void apply(Path snapshot, Path merged) throws IOException {
        if (kind == Kind.DIMENSION) {
            replace(snapshot, merged, dimensionPath());
        } else if (kind == Kind.PLAYER) {
            for (String path : List.of("players/data/" + playerId + ".dat", "players/data/" + playerId + ".dat_old",
                    "players/advancements/" + playerId + ".json",
                    "players/stats/" + playerId + ".json")) {
                replace(snapshot, merged, Path.of(path));
            }
        } else {
            for (String store : CHUNK_STORES) {
                final Path relative = dimensionPath().resolve(store);
                final Path source = snapshot.resolve(relative).resolve(regionFileName());
                final Path target = merged.resolve(relative).resolve(regionFileName());
                if (kind == Kind.CHUNK) {
                    final CompoundTag chunk = readChunk(source, store);
                    if (chunk == null && !Files.exists(target)) continue;
                    if (Files.exists(target)) validateRegionHeader(target);
                    Files.createDirectories(target.getParent());
                    try (RegionFile region = openRegion(target, store)) {
                        if (chunk == null) {
                            region.clear(new ChunkPos(x, z));
                        } else {
                            try (var output = region.getChunkDataOutputStream(new ChunkPos(x, z))) {
                                NbtIo.write(chunk, output);
                            }
                        }
                    }
                } else {
                    replace(snapshot, merged, relative.resolve(regionFileName()));
                    // Oversized chunks live beside the .mca file and must be replaced with their region.
                    for (Path external : externalChunks(merged.resolve(relative))) Files.delete(external);
                    for (Path external : externalChunks(snapshot.resolve(relative))) {
                        replace(snapshot, merged, relative.resolve(external.getFileName()));
                    }
                }
            }
        }
    }

    private String regionFileName() {
        return "r." + regionX() + "." + regionZ() + ".mca";
    }

    private RegionFile openRegion(Path file, String store) throws IOException {
        return new RegionFile(new RegionStorageInfo("fastback-rollback",
                ResourceKey.create(Registries.DIMENSION, dimension), store), file, file.getParent(), true);
    }

    private CompoundTag readChunk(Path file, String store) throws IOException {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null;
        validateRegionHeader(file);
        try (RegionFile region = openRegion(file, store)) {
            return readChunk(region, new ChunkPos(x, z));
        }
    }

    private static CompoundTag readChunk(RegionFile region, ChunkPos pos) throws IOException {
        if (!region.hasChunk(pos)) return null;
        try (var input = region.getChunkDataInputStream(pos)) {
            if (input == null) throw new IOException("Unreadable chunk " + pos + " in " + region.getPath());
            final CompoundTag tag = NbtIo.read(input, NbtAccounter.create(64L * 1024 * 1024));
            if (tag.isEmpty()) throw new IOException("Empty chunk " + pos + " in " + region.getPath());
            return tag;
        }
    }

    private static void validateRegionHeader(Path file) throws IOException {
        final long size = Files.size(file);
        if (size < 8192) throw new IOException("Truncated region file: " + file);
        final BitSet sectors = new BitSet();
        sectors.set(0, 2);
        try (var input = new DataInputStream(Files.newInputStream(file))) {
            for (int i = 0; i < 1024; i++) {
                final int location = input.readInt();
                if (location == 0) continue;
                final int start = location >>> 8;
                final int count = location & 255;
                final int overlap = sectors.nextSetBit(start);
                // Running servers may save a complete chunk without padding the final sector.
                if (start < 2 || count == 0 || (long) start + count > Math.ceilDiv(size, 4096)
                        || (overlap >= 0 && overlap < start + count)) {
                    throw new IOException("Invalid or overlapping chunk sectors in " + file);
                }
                sectors.set(start, start + count);
            }
        }
    }

    private List<Path> externalChunks(Path directory) throws IOException {
        if (!Files.exists(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(file -> {
                final var match = EXTERNAL_CHUNK.matcher(file.getFileName().toString());
                if (!match.matches()) return false;
                try {
                    return Math.floorDiv(Long.parseLong(match.group(1)), 32) == regionX()
                            && Math.floorDiv(Long.parseLong(match.group(2)), 32) == regionZ();
                } catch (NumberFormatException invalid) {
                    return false;
                }
            }).toList();
        }
    }

    private static void replace(Path snapshot, Path merged, Path relative) throws IOException {
        final Path target = merged.resolve(relative);
        if (Files.isDirectory(target, NOFOLLOW_LINKS)) FileUtils.rmdir(target);
        else Files.deleteIfExists(target);
        final Path source = snapshot.resolve(relative);
        if (Files.isDirectory(source, NOFOLLOW_LINKS)) {
            copyTree(source, target, false);
        } else if (Files.exists(source, NOFOLLOW_LINKS)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }
    }

    private static void copyTree(Path source, Path target, boolean worldRoot) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (worldRoot && dir.equals(source.resolve(".git"))) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (worldRoot && file.equals(source.resolve("session.lock"))) return FileVisitResult.CONTINUE;
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                    throw new IOException("Unsupported world entry: " + file);
                }
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
