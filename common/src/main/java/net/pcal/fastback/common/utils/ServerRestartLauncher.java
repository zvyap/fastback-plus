package net.pcal.fastback.common.utils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Standalone bootstrap: this class must depend only on the JDK and require no mod classloader. */
public final class ServerRestartLauncher {

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--check")) return;
        final Path plan = Path.of(args[0]);
        final long parentPid;
        final String parentStarted;
        final Path directory;
        final List<String> command = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(plan))) {
            parentPid = in.readLong();
            parentStarted = readString(in);
            directory = Path.of(readString(in));
            final int count = in.readInt();
            if (count < 2 || count > 65536) throw new IOException("Invalid restart argument count");
            for (int i = 0; i < count; i++) command.add(readString(in));
        } finally {
            Files.deleteIfExists(plan);
        }
        // Directory classfiles have no persistent JVM file lock, unlike a helper JAR on Windows.
        deleteHelper(Path.of(ServerRestartLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        final ProcessHandle parent = ProcessHandle.of(parentPid).orElse(null);
        final Path ready = plan.resolveSibling(plan.getFileName() + ".ready");
        Files.createFile(ready);
        if (parent != null && (parentStarted.isEmpty()
                || parent.info().startInstant().map(Instant::toString).orElse("").equals(parentStarted))) {
            parent.onExit().join();
        }
        Files.deleteIfExists(ready);
        new ProcessBuilder(processCommand(command)).directory(directory.toFile()).inheritIO().start();
    }

    static String classResource() {
        return ServerRestartLauncher.class.getName().replace('.', '/') + ".class";
    }

    /** Delete only the fixed helper file and its known package directories inside the native temp root. */
    static void deleteHelper(Path root) throws IOException {
        final Path absoluteRoot = root.toAbsolutePath().normalize();
        final Path helper = absoluteRoot.resolve(classResource()).normalize();
        if (!helper.startsWith(absoluteRoot)) throw new IOException("Invalid restart helper path");
        Files.deleteIfExists(helper);
        for (Path path = helper.getParent(); path != null && path.startsWith(absoluteRoot); path = path.getParent()) {
            Files.deleteIfExists(path);
        }
    }

    // Windows ProcessBuilder's legacy mode passes already quoted arguments through unchanged.
    // Its default automatic quoting does not escape literal quotes, so encode each complete argv value.
    static List<String> processCommand(List<String> command) {
        if (!System.getProperty("os.name").startsWith("Windows")) return command;
        final List<String> encoded = new ArrayList<>();
        encoded.add(command.getFirst());
        for (String argument : command.subList(1, command.size())) {
            final StringBuilder quoted = new StringBuilder("\"");
            int backslashes = 0;
            for (int i = 0; i < argument.length(); i++) {
                final char c = argument.charAt(i);
                if (c == '\\') {
                    backslashes++;
                } else {
                    quoted.append("\\".repeat(c == '"' ? backslashes * 2 + 1 : backslashes));
                    quoted.append(c);
                    backslashes = 0;
                }
            }
            quoted.append("\\".repeat(backslashes * 2)).append('"');
            encoded.add(quoted.toString());
        }
        return encoded;
    }

    static void writeString(DataOutputStream out, String value) throws IOException {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) throw new IOException("Invalid restart argument length");
        final byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated restart plan");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
