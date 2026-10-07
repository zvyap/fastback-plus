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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Completion statistics describe the saved tree, including the file sizes recorded by Git LFS. */
final class BackupCompletion {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(snapshot|snapshot_size|total_size|remark|creator)\\}");
    private static final Pattern LFS_OID = Pattern.compile("(?m)^oid sha256:[a-f0-9]{64}$");
    private static final Pattern LFS_SIZE = Pattern.compile("(?m)^size ([0-9]+)$");

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

    /** Substitute once so remarks containing placeholder-like text are kept literally. */
    static String expand(String template, Map<String, String> values) {
        return PLACEHOLDER.matcher(template).replaceAll(match -> Matcher.quoteReplacement(values.get(match.group(1))));
    }

    private BackupCompletion() {}
}
