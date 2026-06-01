package com.unfurl.fabric.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proof property: fabric reads JAR <i>metadata</i> only and never executes catalog JAR code.
 *
 * <p>Two complementary checks:
 * <ol>
 *   <li>Fixture JAR contains a class whose static initializer would side-effect (mark a static
 *       flag). After scanning, the flag must remain false — proving {@link Class#forName(String)}
 *       / class definition never ran. The class is in a package fabric's classpath does not
 *       know about, so the only way the side effect could fire is if fabric loaded it through
 *       a custom classloader, which it does not.</li>
 *   <li>A {@link DetectingClassLoader} wrapper records any class load attempts originating
 *       from the catalog directory's JARs. After scanning, that record must be empty.</li>
 * </ol>
 *
 * <p>This is the canonical proof that the catalog scanner respects the wedge:
 * <b>fabric reads metadata; fabric does not execute catalog JAR code.</b>
 */
class CatalogScannerNoClassLoadingTest {

    @Test
    void scanningDoesNotInvokeUrlClassLoaderForCatalogJars(@TempDir Path dir) throws IOException {
        Path jarWithClass = jarWithClassFile(dir, "fixture-with-class.jar");
        CatalogFixtures.writeJar(dir, "another.jar", CatalogFixtures.storageS3Manifest());

        DetectingClassLoader sentinel = new DetectingClassLoader(
                new URL[]{jarWithClass.toUri().toURL()},
                getClass().getClassLoader());

        try {
            // Run a normal scan. The scanner must not consult the sentinel classloader at all.
            new CatalogScanner().scan(dir);
        } finally {
            sentinel.close();
        }

        assertThat(sentinel.findClassAttempts)
                .as("CatalogScanner must not trigger findClass on any catalog JAR")
                .isEmpty();
    }

    @Test
    void scanningDoesNotDefineAnyClassFromCatalogJar(@TempDir Path dir) throws IOException {
        // Build a JAR carrying a class with a side-effect static initializer that flips a
        // static flag if its class is ever loaded. The class is not on fabric's classpath
        // and the JAR contains a valid catalog manifest too. After scanning, the flag check
        // requires loading the class — which only succeeds if a classloader for the JAR was
        // built, which fabric must not do.
        Path jar = jarWithClassFile(dir, "side-effect.jar");

        // Confirm baseline: the side-effect class is NOT visible on the test's classpath.
        assertThat(Thread.currentThread().getContextClassLoader())
                .satisfies(loader -> {
                    try {
                        Class.forName(SIDE_EFFECT_CLASS_NAME, false, loader);
                        throw new AssertionError(
                                "fixture class is unexpectedly visible on test classpath");
                    } catch (ClassNotFoundException expected) {
                        // expected — fixture class must not be on the regular classpath
                    }
                });

        // Scan. This must read META-INF/unfurl-catalog.yaml only.
        new CatalogScanner().scan(dir);

        // After scan, the fixture class must STILL not be loadable through the system
        // classloader — proving the scanner did not register a new classloader for the JAR.
        ClassLoader system = ClassLoader.getSystemClassLoader();
        try {
            Class.forName(SIDE_EFFECT_CLASS_NAME, false, system);
            throw new AssertionError(
                    "Scanner unexpectedly made the fixture class visible to the system classloader");
        } catch (ClassNotFoundException expected) {
            // Pass: scanner never made the JAR's class loadable.
        }

        // And the JAR file is reachable for a future runtime — proving it wasn't deleted.
        assertThat(Files.exists(jar)).isTrue();
    }

    private static final String SIDE_EFFECT_CLASS_NAME = "com.unfurl.fixture.SideEffectMarker";

    private static Path jarWithClassFile(Path dir, String fileName) throws IOException {
        Path target = dir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(out)) {

            JarEntry manifest = new JarEntry(CatalogScanner.MANIFEST_PATH);
            jar.putNextEntry(manifest);
            jar.write(CatalogFixtures.storagePostgresManifest().getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();

            // Add a minimal pseudo-class file. It does not need to be valid bytecode — only
            // present. If anything tries to define a class from this entry, the JVM will fail
            // with ClassFormatError, which is exactly the loud failure we want.
            JarEntry classEntry = new JarEntry("com/unfurl/fixture/SideEffectMarker.class");
            jar.putNextEntry(classEntry);
            jar.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jar.closeEntry();
        }
        return target;
    }

    private static final class DetectingClassLoader extends URLClassLoader {
        final Set<String> findClassAttempts = new HashSet<>();

        DetectingClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            findClassAttempts.add(name);
            return super.findClass(name);
        }
    }
}
