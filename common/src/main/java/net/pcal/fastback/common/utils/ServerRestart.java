package net.pcal.fastback.common.utils;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Shell32Util;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Relaunches the dedicated JVM after its old process, including late shutdown hooks, has exited. */
public final class ServerRestart implements AutoCloseable {

    private final List<String> command;
    private final Path directory;
    private final Path helperLocation;
    private boolean handedOff;

    private ServerRestart(List<String> command, Path directory, Path helperLocation) {
        this.command = List.copyOf(command);
        this.directory = directory;
        this.helperLocation = helperLocation;
    }

    /** Preflight while the server is still running; never guess a loader entry point or split on spaces. */
    public static ServerRestart prepare() throws IOException {
        Path helper = null;
        try {
            final ProcessHandle.Info info = ProcessHandle.current().info();
            final String executable = info.command().orElseThrow(() -> new IOException("Java executable is unavailable"));
            final List<String> command = new ArrayList<>();
            command.add(executable);
            if (Platform.isWindows()) {
                // Java's ProcessHandle does not supply argv on Windows. Use vanilla's existing JNA dependency.
                final String raw = NativeLibrary.getInstance("kernel32")
                        .getFunction("GetCommandLineW", Function.ALT_CONVENTION)
                        .invokePointer(new Object[0]).getWideString(0);
                final String[] argv = Shell32Util.CommandLineToArgv(raw);
                command.addAll(Arrays.asList(argv).subList(1, argv.length));
            } else {
                command.addAll(Arrays.asList(info.arguments().orElseThrow(
                        () -> new IOException("Java launch arguments are unavailable"))));
            }
            if (command.size() < 2 || !Files.isRegularFile(Path.of(executable)) || !Files.isExecutable(Path.of(executable))) {
                throw new IOException("The original Java launch command cannot be restarted");
            }
            helper = Files.createTempDirectory("fastback-restart-helper-");
            final Path helperClass = helper.resolve(ServerRestartLauncher.classResource());
            Files.createDirectories(helperClass.getParent());
            try (var resource = ServerRestartLauncher.class.getResourceAsStream("/" + ServerRestartLauncher.classResource())) {
                if (resource == null) throw new IOException("Standalone restart helper resource is unavailable");
                Files.copy(resource, helperClass);
            }
            final Path directory = Path.of("").toAbsolutePath().normalize();
            final Process check = new ProcessBuilder(executable, "-cp", helper.toString(),
                    ServerRestartLauncher.class.getName(), "--check").directory(directory.toFile()).inheritIO().start();
            try {
                if (!check.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Restart helper preflight timed out");
                if (check.exitValue() != 0) throw new IOException("Restart helper preflight failed");
            } finally {
                if (check.isAlive()) check.destroyForcibly().onExit().join();
            }
            return new ServerRestart(command, directory, helper);
        } catch (Exception | LinkageError e) {
            if (helper != null) {
                try {
                    ServerRestartLauncher.deleteHelper(helper);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("Cannot prepare an automatic server restart", e);
        }
    }

    @FunctionalInterface
    public interface Step {
        void run() throws Exception;
    }

    /** A failed backup or install must never advance to the next destructive step. */
    public static void complete(Step backup, Step install, Step restart) throws Exception {
        backup.run();
        install.run();
        restart.run();
    }

    /** Called only after the mandatory backup and installation succeeded. */
    public void start() throws IOException {
        final Path plan = Files.createTempFile("fastback-restart-", ".bin");
        final Path ready = plan.resolveSibling(plan.getFileName() + ".ready");
        Process launcher = null;
        try {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(plan))) {
                final ProcessHandle parent = ProcessHandle.current();
                out.writeLong(parent.pid());
                ServerRestartLauncher.writeString(out, parent.info().startInstant().map(Instant::toString).orElse(""));
                ServerRestartLauncher.writeString(out, directory.toString());
                out.writeInt(command.size());
                for (String argument : command) ServerRestartLauncher.writeString(out, argument);
            }
            launcher = new ProcessBuilder(command.getFirst(), "-Djdk.lang.Process.allowAmbiguousCommands=true", "-cp", helperLocation.toString(),
                    ServerRestartLauncher.class.getName(), plan.toString()).directory(directory.toFile()).inheritIO().start();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(ready)) {
                if (!launcher.isAlive()) throw new IOException("Restart helper exited before becoming ready");
                if (System.nanoTime() >= deadline) throw new IOException("Restart helper startup timed out");
                Thread.sleep(25);
            }
            Files.delete(ready);
            this.handedOff = true;
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (launcher != null) launcher.destroyForcibly().onExit().join();
            Files.deleteIfExists(plan);
            Files.deleteIfExists(ready);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("Unable to start the automatic restart helper", e);
        }
    }

    /** Dispose an unclaimed helper when cancellation or shutdown scheduling fails. */
    @Override
    public void close() throws IOException {
        if (!this.handedOff) ServerRestartLauncher.deleteHelper(this.helperLocation);
    }
}
