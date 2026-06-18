package io.mosip.registration.launcher.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Reads {@code MANIFEST.MF} files and performs the version-comparison and per-file integrity checks
 * the launcher needs (design steps 2 and 6). The per-file hash stored under {@code Content-Type}
 * is validated with {@link HashUtil} so it matches what the build wrote.
 * <p>
 * Pure JDK + slf4j; safe to run under the Java 11 JRE in {@code _launcher.jar}.
 */
public final class ManifestVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManifestVerifier.class);

    private ManifestVerifier() {
        // utility class
    }

    /** Parses a manifest from raw bytes (e.g. the signature-verified manifest, kept in memory). */
    public static Manifest parse(byte[] manifestBytes) throws IOException {
        try (InputStream in = new ByteArrayInputStream(manifestBytes)) {
            return new Manifest(in);
        }
    }

    /**
     * @return the {@code Manifest-Version} main attribute of the given manifest file, or {@code null}
     *         if absent.
     * @throws IOException if the manifest cannot be read
     */
    public static String getVersion(File manifestFile) throws IOException {
        return read(manifestFile).getMainAttributes().getValue(Attributes.Name.MANIFEST_VERSION);
    }

    /**
     * @return {@code true} only if both manifests exist and declare the same non-null
     *         {@code Manifest-Version}.
     * @throws IOException if either manifest cannot be read
     */
    public static boolean versionsMatch(File manifestA, File manifestB) throws IOException {
        String a = getVersion(manifestA);
        String b = getVersion(manifestB);
        return a != null && a.equals(b);
    }

    /**
     * Validates every entry listed in {@code manifestFile} against the corresponding file in
     * {@code baseDir}. See {@link #findMismatchedFiles(Manifest, File)}.
     */
    public static List<String> findMismatchedFiles(File manifestFile, File baseDir) throws IOException {
        return findMismatchedFiles(read(manifestFile), baseDir);
    }

    /**
     * Validates every entry listed in {@code manifest} against the corresponding file in
     * {@code baseDir}. Prefer this overload with the <b>signature-verified</b> manifest (parsed via
     * {@link #parse(byte[])}) so the check cannot be subverted by a manifest unpacked from the
     * archive being verified.
     *
     * @return the list of entry names that are missing or whose hash does not match (empty when all
     *         entries verify)
     */
    public static List<String> findMismatchedFiles(Manifest manifest, File baseDir) throws IOException {
        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<String, Attributes> entry : manifest.getEntries().entrySet()) {
            String name = entry.getKey();
            String expectedHash = entry.getValue().getValue(Attributes.Name.CONTENT_TYPE);
            File file = new File(baseDir, name);
            if (!file.exists()) {
                LOGGER.warn("Manifest entry {} has no corresponding file in {}", name, baseDir);
                mismatched.add(name);
                continue;
            }
            if (expectedHash == null || !expectedHash.equals(HashUtil.sha256Hex(file))) {
                LOGGER.warn("Hash mismatch for {}", name);
                mismatched.add(name);
            }
        }
        return mismatched;
    }

    /**
     * Returns the names of files directly under {@code baseDir} that are <b>not</b> listed in
     * {@code manifest} and are not in {@code ignore}. The signed manifest is treated as an
     * allowlist: anything extracted that the manifest does not enumerate is rejected, so a tampered
     * archive cannot smuggle in extra files.
     *
     * @param ignore control files legitimately present in {@code baseDir} but not manifest entries
     *               (e.g. {@code MANIFEST.MF}, {@code MANIFEST.MF.sig}, {@code lib.zip})
     */
    public static List<String> findUnexpectedFiles(Manifest manifest, File baseDir, Set<String> ignore) {
        List<String> unexpected = new ArrayList<>();
        File[] files = baseDir.listFiles();
        if (files == null) {
            return unexpected;
        }
        Set<String> entries = manifest.getEntries().keySet();
        for (File file : files) {
            if (file.isDirectory()) {
                continue;
            }
            String name = file.getName();
            if (ignore.contains(name) || entries.contains(name)) {
                continue;
            }
            LOGGER.warn("Unexpected file not listed in manifest: {}", name);
            unexpected.add(name);
        }
        return unexpected;
    }

    /** @return {@code true} if {@code manifest} lists {@code entryName}. */
    public static boolean hasEntry(Manifest manifest, String entryName) {
        return manifest.getAttributes(entryName) != null;
    }

    /**
     * @return {@code true} only if {@code manifest} lists {@code entryName} with a {@code Content-Type}
     *         hash that matches {@code file}'s SHA-256.
     */
    public static boolean fileMatches(Manifest manifest, String entryName, File file) throws IOException {
        Attributes attrs = manifest.getAttributes(entryName);
        if (attrs == null) {
            return false;
        }
        String expectedHash = attrs.getValue(Attributes.Name.CONTENT_TYPE);
        return expectedHash != null && file.exists() && expectedHash.equals(HashUtil.sha256Hex(file));
    }

    private static Manifest read(File manifestFile) throws IOException {
        try (InputStream in = Files.newInputStream(manifestFile.toPath())) {
            return new Manifest(in);
        }
    }
}
