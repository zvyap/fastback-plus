/*
 * FastBack - Fast, incremental Minecraft backups powered by Git.
 * Copyright (C) 2022 pcal.net
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; If not, see <http://www.gnu.org/licenses/>.
 */

package net.pcal.fastback.common.utils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.GZIPInputStream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/** Installs a staged world only after the server has closed the live world. */
public final class ServerWorldRestore {

    private static final String[] LFS_POINTER_PREFIXES = {
            "version https://git-lfs.github.com/spec/v1",
            "version https://hawser.github.com/spec/v1",
            "version http://git-media.io/v/2"
    };

    private ServerWorldRestore() {
    }

    public static void validate(final Path world, final Path staged, final Path previous) throws IOException {
        requireAbsoluteNormalized(world);
        requireAbsoluteNormalized(staged);
        requireAbsoluteNormalized(previous);
        if (world.getParent() == null || !world.getParent().equals(staged.getParent())
                || !world.getParent().equals(previous.getParent())
                || world.equals(staged) || world.equals(previous) || staged.equals(previous)) {
            throw new IOException("The world, staged world and previous world must be distinct sibling directories");
        }
        requireDirectory(world);
        checkForIncompleteRestore(world);
        requireDirectory(world.resolve(".git"));
        if (!Files.notExists(previous, NOFOLLOW_LINKS)) {
            throw new IOException("Previous world already exists or cannot be inspected: " + previous);
        }
        validateSnapshot(staged);
    }

    /** Validate extracted files before parsing metadata or scheduling server shutdown. */
    public static void validateSnapshot(final Path staged) throws IOException {
        requireAbsoluteNormalized(staged);
        requireDirectory(staged);
        if (!Files.notExists(staged.resolve(".git"), NOFOLLOW_LINKS)) {
            throw new IOException("Staged world contains a Git repository: " + staged);
        }
        Files.walkFileTree(staged, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                checkEntry(dir, attrs);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                checkEntry(file, attrs);
                return FileVisitResult.CONTINUE;
            }

            private void checkEntry(final Path path, final BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || (!attrs.isDirectory() && !attrs.isRegularFile())) {
                    throw new IOException("Unsupported staged world entry: " + path);
                }
                if (path.getFileName().toString().equals("session.lock")) {
                    throw new IOException("Staged world contains a session lock: " + path);
                }
                if (attrs.isRegularFile() && attrs.size() <= 1024) {
                    try (var input = Files.newInputStream(path)) {
                        final String prefix = new String(input.readNBytes(64), StandardCharsets.US_ASCII);
                        for (String pointerPrefix : LFS_POINTER_PREFIXES) {
                            if (prefix.startsWith(pointerPrefix)) {
                                throw new IOException("Staged world contains an unresolved Git LFS pointer: " + path);
                            }
                        }
                    }
                }
            }
        });
        final Path level = staged.resolve("level.dat");
        final BasicFileAttributes levelAttributes = Files.readAttributes(level, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!levelAttributes.isRegularFile() || levelAttributes.size() == 0) {
            throw new IOException("Staged world has no regular, nonempty level.dat: " + staged);
        }
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(level))) {
            if (input.read() != 10) {
                throw new IOException("Staged level.dat does not contain an NBT compound: " + level);
            }
            // Read through the trailer so damaged or truncated backups cannot reach the live world.
            input.transferTo(OutputStream.nullOutputStream());
        }
    }

    public static void install(final Path world, final Path staged, final Path previous) throws IOException {
        install(world, staged, previous, (source, target) -> Files.move(source, target, ATOMIC_MOVE));
    }

    @FunctionalInterface
    interface Move {
        void move(Path source, Path target) throws IOException;
    }

    // The movement seam lets tests exercise filesystem failures without changing host permissions.
    static void install(final Path world, final Path staged, final Path previous, final Move move) throws IOException {
        validate(world, staged, previous);
        final Path marker = incompleteRestoreFile(world);
        try (FileChannel channel = FileChannel.open(marker, CREATE_NEW, WRITE)) {
            final ByteBuffer paths = StandardCharsets.UTF_8.encode("active=" + world + "\nstaged=" + staged +
                    "\nprevious=" + previous + "\n");
            while (paths.hasRemaining()) channel.write(paths);
            channel.force(true);
        }
        forceDirectory(world.getParent());
        boolean savedPrevious = false;
        boolean installed = false;
        try {
            move.move(world, previous);
            savedPrevious = true;
            move.move(staged, world);
            installed = true;
            move.move(previous.resolve(".git"), world.resolve(".git"));
        } catch (IOException | RuntimeException failure) {
            try {
                if (installed) {
                    move.move(world, staged);
                }
                if (savedPrevious) move.move(previous, world);
                forceDirectory(world.getParent());
                Files.delete(marker);
                forceDirectory(world.getParent());
            } catch (IOException | RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        // Finalization failures retain the marker; the Git directory has already moved.
        forceDirectory(previous);
        forceDirectory(world);
        forceDirectory(world.getParent());
        Files.delete(marker);
        forceDirectory(world.getParent());
    }

    private static void forceDirectory(final Path directory) throws IOException {
        // Windows' Java provider cannot open directory channels. Process recovery still uses the marker.
        if (directory.getFileSystem().getSeparator().equals("\\")) return;
        try (FileChannel channel = FileChannel.open(directory, READ)) {
            channel.force(true);
        }
    }

    /** Refuse to start a world whose directory swap was interrupted by process or filesystem failure. */
    public static void checkForIncompleteRestore(final Path world) throws IOException {
        final Path absolute = world.toAbsolutePath();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new IOException("Cannot resolve world path: " + absolute);
        final Path canonical = existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        final Path marker = incompleteRestoreFile(canonical);
        if (!Files.notExists(marker, NOFOLLOW_LINKS)) {
            throw new IOException("Incomplete snapshot installation. Inspect the recovery paths in " + marker +
                    " and restore the intended world and its .git before removing this marker and restarting.");
        }
    }

    static Path incompleteRestoreFile(final Path world) {
        return world.resolveSibling(world.getFileName() + "-fastback-load-pending.txt");
    }

    private static void requireAbsoluteNormalized(final Path path) throws IOException {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IOException("Restore paths must be absolute and normalized: " + path);
        }
    }

    private static void requireDirectory(final Path path) throws IOException {
        final BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isSymbolicLink()) {
            throw new IOException("Restore path must be a real directory: " + path);
        }
    }
}
