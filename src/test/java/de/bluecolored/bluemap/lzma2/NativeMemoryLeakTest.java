package de.bluecolored.bluemap.lzma2;

import de.bluecolored.bluemap.lzma2.nativexz.NativeDecoderPool;
import de.bluecolored.bluemap.lzma2.nativexz.NativeEncoderPool;
import de.bluecolored.bluemap.lzma2.nativexz.NativeLZMA2;
import de.bluecolored.bluemap.lzma2.nativexz.NativeLZMA2Loader;
import de.bluecolored.bluemap.lzma2.nativexz.NativeXZInputStream;
import de.bluecolored.bluemap.lzma2.nativexz.NativeXZOutputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Random;

/**
 * Cross-platform native memory-leak tests.
 *
 * <p>These tests only use dependencies that are already part of the build
 * (JDK + JUnit 5). They detect leaks of NATIVE (off-heap) memory by watching
 * the resident set size of the JVM process via {@code com.sun.management.
 * OperatingSystemMXBean#getPhysicalMemoryUsage()} which is available on all
 * platforms supported by the HotSpot JDK (Windows / Linux / macOS, x86_64 / aarch64).
 *
 * <p>The idea: repeatedly create/reset/free native LZMA2 encoders and decoders
 * with a big preset (big dictionary). If any native state is not released, the
 * process RSS grows roughly linearly with the number of iterations, while a
 * leak-free implementation keeps it flat.
 */
public class NativeMemoryLeakTest {

    /**
     * Reads the physical memory currently used by this JVM process (RSS).
     * Works on Windows/Linux/macOS with the standard Oracle/HotSpot JDK.
     *
     * <p>Uses reflection on {@code com.sun.management.OperatingSystemMXBean#
     * getPhysicalMemoryUsage()} so that the test also compiles against JDKs
     * where that method does not exist yet (it was added in JDK 14); on such
     * JVMs the leak tests are skipped via JUnit assumptions.
     */
    private static long physicalMemoryUsage() {
        try {
            Object bean = ManagementFactory.getPlatformMXBean(
                    Class.forName("com.sun.management.OperatingSystemMXBean").asSubclass(java.lang.management.PlatformManagedObject.class));
            // JDK >= 14: getPhysicalMemoryUsage() returns the RSS of this process (bytes)
            Method m;
            try {
                m = bean.getClass().getMethod("getPhysicalMemoryUsage");
            } catch (NoSuchMethodException e) {
                // Older JDKs: deprecated equivalent returning resident set size in KB
                m = bean.getClass().getMethod("getProcessResidentMemorySize");
                return ((long) m.invoke(bean)) * 1024L;
            }
            return (long) m.invoke(bean);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Assumptions.abort("No way to read process memory usage on this JVM: " + e);
            return -1; // unreachable
        }
    }

    private static void forceGC() {
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        System.gc();
    }

    @BeforeAll
    public static void checkNativeLibrary() {
        Assertions.assertTrue(NativeLZMA2Loader.isLoaded(),
                "Native library should be loaded: " + NativeLZMA2Loader.getLoadError());
    }

    /**
     * Leak test for the encoder pool: reusing (resetting) a pooled encoder must
     * release the previous native encoder state (dictionary + MT worker pool).
     * With the old buggy resetEncoder() every reuse leaked ~preset-dependent
     * amount of native memory (tens of MB at preset 9), so RSS grew linearly.
     */
    @Test
    public void testNoLeakOnEncoderReuse() throws Exception {
        runReuseLeakTest("encoder", true);
    }

    /**
     * Same as above but for the decoder pool (resetDecoder()).
     */
    @Test
    public void testNoLeakOnDecoderReuse() throws Exception {
        runReuseLeakTest("decoder", false);
    }

    private void runReuseLeakTest(String what, boolean encoder) throws Exception {
        final int warmupIterations = 3;
        final int measuredIterations = 12;
        final byte[] data = new byte[256 * 1024];
        new Random(1).nextBytes(data);

        // Compress once to get valid input for the decoder runs.
        ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
        try (NativeXZOutputStream xzOut = new NativeXZOutputStream(compressedOut, 6, 1)) {
            xzOut.write(data);
        }
        final byte[] compressed = compressedOut.toByteArray();

        // Use preset 9 => ~64..700 MB dictionary if leaked per iteration.
        // Single thread to keep the test fast; the leak lives in the
        // (re-)allocated lzma_state_easy / dictionary either way.
        NativeEncoderPool encPool = encoder ? new NativeEncoderPool(9, 1, 1) : null;
        NativeDecoderPool decPool = !encoder ? new NativeDecoderPool(1) : null;

        // Warm-up phase: let lazily allocated native/JVM memory settle.
        for (int i = 0; i < warmupIterations; i++) {
            cycleOnce(data, compressed, encPool, decPool);
        }
        forceGC();
        long baseline = physicalMemoryUsage();

        // Measured phase: grow the heap deliberately so a real leak stands out.
        long maxGrowth = 0;
        for (int i = 1; i <= measuredIterations; i++) {
            cycleOnce(data, compressed, encPool, decPool);
            if (i % 4 == 0) {
                forceGC();
                long growth = physicalMemoryUsage() - baseline;
                maxGrowth = Math.max(maxGrowth, growth);
            }
        }
        forceGC();
        long finalGrowth = physicalMemoryUsage() - baseline;
        maxGrowth = Math.max(maxGrowth, finalGrowth);

        // Tolerance: JVM/RSS noise plus the small steady-state pools.
        // A leak would add ~60+ MB per reused handle at preset 9, i.e. hundreds
        // of MB over the measured phase, far above this bound.
        final long MAX_ALLOWED_GROWTH = 64L * 1024 * 1024;

        Assertions.assertTrue(maxGrowth < MAX_ALLOWED_GROWTH,
                String.format("Suspected native %s memory leak: process RSS grew by %.1f MB " +
                                "after %d reuse cycles (limit %.0f MB).",
                        what, maxGrowth / (1024.0 * 1024.0), measuredIterations,
                        MAX_ALLOWED_GROWTH / (1024.0 * 1024.0)));
    }

    private void cycleOnce(byte[] data, byte[] compressed,
                            NativeEncoderPool encPool, NativeDecoderPool decPool) throws IOException {
        if (encPool != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (NativeXZOutputStream xzOut = new NativeXZOutputStream(out, encPool)) {
                xzOut.write(data);
            }
        } else {
            ByteArrayInputStream in = new ByteArrayInputStream(compressed);
            try (NativeXZInputStream xzIn = new NativeXZInputStream(in, decPool)) {
                byte[] buf = new byte[8192];
                while (xzIn.read(buf) != -1) { /* drain */ }
            }
        }
    }

    /**
     * Sanity check: handles explicitly freed via NativeLZMA2.free() must not
     * keep growing RSS either (guards against regressions in free()/lzma_end).
     */
    @Test
    public void testNoLeakOnCreateFreeCycle() throws Exception {
        final int warmupIterations = 3;
        final int measuredIterations = 12;

        for (int i = 0; i < warmupIterations; i++) {
            long h = NativeLZMA2.createEncoder(9, 1);
            Assertions.assertNotEquals(0, h, "createEncoder failed");
            NativeLZMA2.free(h);

            long d = NativeLZMA2.createDecoder();
            Assertions.assertNotEquals(0, d, "createDecoder failed");
            NativeLZMA2.free(d);
        }
        forceGC();
        long baseline = physicalMemoryUsage();

        for (int i = 0; i < measuredIterations; i++) {
            long h = NativeLZMA2.createEncoder(9, 1);
            Assertions.assertNotEquals(0, h);
            NativeLZMA2.free(h);

            long d = NativeLZMA2.createDecoder();
            Assertions.assertNotEquals(0, d);
            NativeLZMA2.free(d);
        }
        forceGC();
        long growth = physicalMemoryUsage() - baseline;

        final long MAX_ALLOWED_GROWTH = 64L * 1024 * 1024;
        Assertions.assertTrue(growth < MAX_ALLOWED_GROWTH,
                String.format("Suspected native memory leak in create/free cycle: RSS grew by %.1f MB " +
                                "over %d iterations (limit %.0f MB).",
                        growth / (1024.0 * 1024.0), measuredIterations,
                        MAX_ALLOWED_GROWTH / (1024.0 * 1024.0)));
    }
}
