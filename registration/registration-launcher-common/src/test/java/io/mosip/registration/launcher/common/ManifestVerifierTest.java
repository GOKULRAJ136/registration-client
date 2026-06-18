package io.mosip.registration.launcher.common;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ManifestVerifierTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File libDir;
    private byte[] jarContent;

    @Before
    public void setUp() throws Exception {
        libDir = folder.newFolder("lib");
        jarContent = "some-jar-bytes".getBytes();
        Files.write(new File(libDir, "foo.jar").toPath(), jarContent);
    }

    private File writeManifest(String version, String entryName, String entryHash) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, version);
        if (entryName != null) {
            Attributes attrs = new Attributes();
            attrs.put(Attributes.Name.CONTENT_TYPE, entryHash);
            manifest.getEntries().put(entryName, attrs);
        }
        File file = folder.newFile("MANIFEST-" + version + "-" + System.nanoTime() + ".MF");
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            manifest.write(out);
        }
        return file;
    }

    @Test
    public void getVersion_returnsManifestVersion() throws Exception {
        File manifest = writeManifest("1.3.0", null, null);
        assertEquals("1.3.0", ManifestVerifier.getVersion(manifest));
    }

    @Test
    public void versionsMatch_sameVersion_true() throws Exception {
        File a = writeManifest("1.3.0", null, null);
        File b = writeManifest("1.3.0", null, null);
        assertTrue(ManifestVerifier.versionsMatch(a, b));
    }

    @Test
    public void versionsMatch_differentVersion_false() throws Exception {
        File a = writeManifest("1.3.0", null, null);
        File b = writeManifest("1.4.0", null, null);
        assertFalse(ManifestVerifier.versionsMatch(a, b));
    }

    @Test
    public void findMismatchedFiles_allValid_returnsEmpty() throws Exception {
        File manifest = writeManifest("1.3.0", "foo.jar", HashUtil.sha256Hex(jarContent));
        assertTrue(ManifestVerifier.findMismatchedFiles(manifest, libDir).isEmpty());
    }

    @Test
    public void findMismatchedFiles_tamperedFile_reportsEntry() throws Exception {
        File manifest = writeManifest("1.3.0", "foo.jar", HashUtil.sha256Hex("different".getBytes()));
        List<String> mismatched = ManifestVerifier.findMismatchedFiles(manifest, libDir);
        assertEquals(1, mismatched.size());
        assertTrue(mismatched.contains("foo.jar"));
    }

    @Test
    public void findMismatchedFiles_missingFile_reportsEntry() throws Exception {
        File manifest = writeManifest("1.3.0", "missing.jar", HashUtil.sha256Hex(jarContent));
        List<String> mismatched = ManifestVerifier.findMismatchedFiles(manifest, libDir);
        assertTrue(mismatched.contains("missing.jar"));
    }
}
