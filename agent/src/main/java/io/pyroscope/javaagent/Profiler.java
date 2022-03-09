package io.pyroscope.javaagent;

import one.profiler.AsyncProfiler;
import one.profiler.Counter;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

class Profiler {
    private final Logger logger;
    private final EventType eventType;
    private final Duration interval;

    private static String libraryPath;
    static {
        try {
            deployLibrary();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Extracts the profiler library file from the JAR and puts it in the temp directory.
     */
    private static void deployLibrary() throws IOException {
        final String fileName = libraryFileName();

        System.out.println("Deploy library: " + fileName);

        final String userName = System.getProperty("user.name");
        final String tmpDir = System.getProperty("java.io.tmpdir");
        final File targetDir = new File(tmpDir, userName + "-pyroscope/");
        targetDir.mkdirs();

        try (final InputStream is = Objects.requireNonNull(
                Profiler.class.getResourceAsStream("/" + fileName))) {
            final Path target = targetDir.toPath().resolve(targetLibraryFileName(fileName)).toAbsolutePath();
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            libraryPath = target.toString();
        }
    }

    /**
     * Creates the library file name based on the current OS and architecture name.
     */
    private static String libraryFileName() {
        String os;
        String arch;
        final String osProperty = System.getProperty("os.name");
        final String archProperty = System.getProperty("os.arch");
        switch (osProperty) {
            case "Linux":
                os = "linux";
                switch (archProperty) {
                    case "x86":
                        arch = "x86";
                        break;

                    case "amd64":
                        arch = "x64";
                        break;

                    case "arm":
                        arch = "arm";
                        break;

                    case "aarch64":
                        arch = "aarch64";
                        break;

                    default:
                        throw new RuntimeException("Unsupported architecture " + archProperty);
                }
                break;

            case "Mac OS X":
                // async-profiler 2.1 is likely to bring macOS/AArch64 support
                // https://github.com/jvm-profiling-tools/async-profiler/releases/tag/v2.1-ea
                os = "macos";
                if (!"x86_64".equals(archProperty)) {
                    throw new RuntimeException("Unsupported architecture " + archProperty);
                } else {
                    arch = "x64";
                }
                break;

            default:
                throw new RuntimeException("Unsupported OS " + osProperty);
        }

        String muslDash = isLinuxMusl(os, arch) ? "-dash-" : "-";
        return "libasyncProfiler-" + os + muslDash + arch + ".so";
    }

    private static boolean isLinuxMusl(String os, String arch) {
        boolean linuxMusl = false;
        if (os.equals("linux") && arch.equals("x86")) {
            // check if musl (Alpine) is used instead of std lib
            // first is just "Linux", second is "Linux/GNU"
            String uname = execCommand("uname -o");
            System.out.println("Output command uname -o: [" + uname + "]");
            if (uname.equals("Linux")) {
                linuxMusl = true;
            }
        }
        return linuxMusl;
    }

    private static String execCommand(String cmd) {
        Runtime run = Runtime.getRuntime();
        Process process;
        try {
            process = run.exec(cmd);
            process.waitFor(2, TimeUnit.SECONDS);
            BufferedReader buf = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            StringBuilder output = new StringBuilder();
            while ((line = buf.readLine()) != null) {
                output.append(line);
            }
            return output.toString();
        } catch (IOException | InterruptedException e) {
            // failure to run command, return default value
            System.err.println("Failed to execute command: " + cmd);
            return "Linux/GNU";
        }
    }

    /**
     * <p>Adds the checksum to the library file name.</p>
     *
     * <p>E.g. {@code libasyncProfiler-linux-x64.so} ->
     *     {@code libasyncProfiler-linux-x64-7b43b7cc6c864dd729cc7dcdb6e3db8f5ee5b4a4.so}</p>
     */
    private static String targetLibraryFileName(final String libraryFileName) throws IOException {
        if (!libraryFileName.endsWith(".so")) {
            throw new IllegalArgumentException("Incorrect library file name: " + libraryFileName);
        }

        final String checksumFileName = libraryFileName + ".sha1";
        String checksum;
        try (final InputStream is = Objects.requireNonNull(
                Profiler.class.getResourceAsStream("/" + checksumFileName))) {
            checksum = InputStreamUtils.readToString(is);
        }

        return libraryFileName.substring(0, libraryFileName.length() - 3) + "-" + checksum + ".so";
    }

    public static void init() {
        // Do nothing, rely in the static initialization in the static block.
    }

    private final AsyncProfiler instance = AsyncProfiler.getInstance(libraryPath);

    // TODO this is actually start of snapshot, not profiling as a whole
    private Instant profilingStarted = null;

    Profiler(final Logger logger, final EventType eventType, final Duration interval) {
        this.logger = logger;
        this.eventType = eventType;
        this.interval = interval;
    }

    // TODO new method for starting new snapshot/batch
    final synchronized void start() {
        instance.start(eventType.id, interval.toNanos());
        profilingStarted = Instant.now();
        logger.info("Profiling started");
    }

    final synchronized Snapshot dump() {
        if (profilingStarted == null) {
            throw new IllegalStateException("Profiling is not started");
        }

        final Snapshot result = new Snapshot(
            eventType,
            profilingStarted,
            Instant.now(),
            instance.dumpCollapsed(Counter.SAMPLES)
        );

        // TODO use `this.start()` or analogue
        profilingStarted = Instant.now();
        instance.start(eventType.id, interval.toNanos());
        return result;
    }
}
