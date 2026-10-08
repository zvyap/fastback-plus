package net.pcal.fastback.common.repo;

import net.pcal.fastback.common.utils.Executor;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.time.DurationFormatUtils.formatDurationWords;

/** Completion statistics describe the saved tree, including the file sizes recorded by Git LFS. */
final class BackupCompletion {

    private static final Pattern LFS_OID = Pattern.compile("(?m)^oid sha256:[a-f0-9]{64}$");
    private static final Pattern LFS_SIZE = Pattern.compile("(?m)^size ([0-9]+)$");
    private static final Pattern LFS_OBJECT = Pattern.compile("[a-f0-9]{64}");

    record LfsStorage(Path objects, long bytes) {
        long addedBytes() throws IOException {
            return Math.max(0, lfsSize(objects) - bytes);
        }
    }

    static LfsStorage lfsStorage(Repository repository) throws IOException {
        final String configured = repository.getConfig().getString("lfs", null, "storage");
        final Path objects = repository.getDirectory().toPath()
                .resolve(configured == null || configured.isBlank() ? "lfs" : configured).resolve("objects");
        return new LfsStorage(objects, lfsSize(objects));
    }

    static long lfsSize(Path objects) throws IOException {
        Executor.checkCancelled();
        try {
            if (!Files.readAttributes(objects, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                throw new IOException("LFS object storage is not a directory");
            }
        } catch (NoSuchFileException missing) {
            return 0;
        }
        // ponytail: scan retained object metadata; track new OIDs if large histories make this too slow.
        final long[] bytes = {0};
        Files.walkFileTree(objects, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                Executor.checkCancelled();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                Executor.checkCancelled();
                if (attributes.isRegularFile() && LFS_OBJECT.matcher(file.getFileName().toString()).matches()) {
                    bytes[0] = Math.addExact(bytes[0], attributes.size());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes[0];
    }

    static long snapshotSize(Repository repository, SnapshotId snapshot) throws IOException {
        final ObjectId commitId = repository.resolve("refs/heads/" + snapshot.getBranchName());
        if (commitId == null) throw new IOException("Snapshot reference is unavailable");
        long total = 0;
        try (ObjectReader reader = repository.newObjectReader();
             RevWalk commits = new RevWalk(reader);
             TreeWalk tree = new TreeWalk(reader)) {
            tree.addTree(commits.parseCommit(commitId).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                Executor.checkCancelled();
                if (tree.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) continue;
                final ObjectLoader blob = reader.open(tree.getObjectId(0), Constants.OBJ_BLOB);
                final long size = blob.getSize() <= 1024
                        ? logicalBlobSize(blob.getBytes(1024)) : blob.getSize();
                total = Math.addExact(total, size);
            }
        }
        return total;
    }

    static long logicalBlobSize(byte[] blob) {
        final String text = new String(blob, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (text.startsWith("version https://git-lfs.github.com/spec/v1\n") && LFS_OID.matcher(text).find()) {
            final Matcher size = LFS_SIZE.matcher(text);
            if (size.find()) {
                try {
                    return Long.parseLong(size.group(1));
                } catch (NumberFormatException invalidPointer) {
                    // Malformed pointer text remains an ordinary saved blob.
                }
            }
        }
        return blob.length;
    }

    static String elapsedText(long millis) {
        return formatDurationWords(Math.max(1000, millis), true, true);
    }

    private BackupCompletion() {}
}
