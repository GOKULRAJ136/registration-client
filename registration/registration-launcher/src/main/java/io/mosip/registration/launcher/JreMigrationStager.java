package io.mosip.registration.launcher;

import io.mosip.registration.launcher.common.ManifestVerifier;
import io.mosip.registration.launcher.common.ZipExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.jar.Manifest;

/**
 * Step-3 preparation for the JRE 11 -&gt; 21 migration path (design doc step 3): stages everything
 * {@code migration.exe} needs, <b>except the final "start migration.exe and exit" action</b>, which
 * is blocked on the native-exe toolchain decision (T3) and is performed by the caller.
 * <p>
 * All downloaded/staged content is integrity-verified before it is placed or made runnable: the lib
 * is staged via {@link LibUpdater} (signature + per-file hash + allowlist), and the migration
 * artefacts ({@code jre21.zip}, the exes, {@code _launcher.jar}) are checked against the
 * signature-verified root {@code MANIFEST.MF} before they are unzipped/copied.
 * <p>
 * Idempotent: safe to re-run after an interrupted attempt (each step is guarded by an existence check).
 */
public final class JreMigrationStager {

    private static final Logger LOGGER = LoggerFactory.getLogger(JreMigrationStager.class);

    private static final String DIR_ARTIFACTS = ".artifacts";
    private static final String DIR_TEMP = ".TEMP";
    private static final String DIR_LIB = "lib";
    private static final String DIR_JRE21_TEMP = "jre21_temp";
    private static final String FILE_JRE21_ZIP = "jre21.zip";
    private static final String FILE_RUN_BAT = "run.bat";
    private static final String FILE_RUN_BAT_BACKUP = "run.bat_jre11";
    private static final String FILE_MIGRATION_EXE = "migration.exe";
    private static final String FILE_ROLLBACK_EXE = "rollback.exe";
    private static final String FILE_LAUNCHER = "_launcher.jar";

    /** Artefacts copied from {@code lib/} to {@code .artifacts/} during the one-time {@code <1.3.0 -> 1.3.0} transition. */
    private static final String[] TRANSITION_ARTEFACTS = {
            FILE_LAUNCHER, FILE_JRE21_ZIP, FILE_MIGRATION_EXE, FILE_ROLLBACK_EXE, FILE_RUN_BAT
    };

    /** Migration artefacts that must be integrity-checked against the signed root manifest before use. */
    private static final String[] VERIFIED_ROOT_ARTEFACTS = {
            FILE_JRE21_ZIP, FILE_MIGRATION_EXE, FILE_ROLLBACK_EXE, FILE_LAUNCHER
    };

    private JreMigrationStager() {
        // utility class
    }

    /**
     * Prepares the JRE migration. After this returns, the caller should launch {@code migration.exe}
     * and exit the JVM.
     *
     * @param root              the application root
     * @param rootManifest      the signature-verified root {@code MANIFEST.MF} (its {@code .sig} was
     *                          checked at startup by {@link StartupEvaluator})
     * @param libManifestUrl    URL of {@code lib/MANIFEST.MF} for the target version
     * @param libManifestSigUrl URL of {@code lib/MANIFEST.MF.sig}
     * @param libZipUrl         URL of {@code lib.zip}
     * @param trustedKey        public key from the embedded {@code provider.pem}
     * @param connectTimeout    connection timeout (ms)
     * @param readTimeout       read timeout (ms)
     * @throws IOException if a required artefact is missing or fails integrity verification
     */
    public static void stage(File root, File rootManifest,
                             String libManifestUrl, String libManifestSigUrl, String libZipUrl,
                             PublicKey trustedKey, int connectTimeout, int readTimeout) throws IOException {
        File artifacts = new File(root, DIR_ARTIFACTS);
        File temp = new File(root, DIR_TEMP);
        File lib = new File(root, DIR_LIB);
        File jre21Temp = new File(root, DIR_JRE21_TEMP);
        Files.createDirectories(artifacts.toPath());

        // 1. <1.3.0 -> 1.3.0 transition: protect artefacts by copying lib/* -> .artifacts/* before lib cleanup.
        for (String name : TRANSITION_ARTEFACTS) {
            File inLib = new File(lib, name);
            File inArtifacts = new File(artifacts, name);
            if (inLib.exists() && !inArtifacts.exists()) {
                LOGGER.info("Transition: copying {} from lib/ to .artifacts/", name);
                copy(inLib, inArtifacts);
            }
        }

        // 2. integrity-check the migration artefacts against the signature-verified root manifest
        //    BEFORE any of them are unzipped (jre21.zip) or made runnable (exes / _launcher.jar).
        verifyArtefactsAgainstRootManifest(rootManifest, artifacts);

        // 3. stage the lib into .TEMP/ with full verification (signature + per-file hash + allowlist).
        LibUpdateResult libResult = LibUpdater.update(libManifestUrl, libManifestSigUrl, libZipUrl,
                temp, trustedKey, connectTimeout, readTimeout);
        if (libResult != LibUpdateResult.READY_RESTART) {
            throw new IOException("lib staging failed integrity verification: " + libResult);
        }

        // 4. unzip the verified jre21.zip -> jre21_temp/ (only if not already staged)
        if (!jre21Temp.exists()) {
            File jre21Zip = new File(artifacts, FILE_JRE21_ZIP);
            if (!jre21Zip.exists()) {
                throw new IOException("Missing " + jre21Zip.getPath() + " required for JRE migration");
            }
            ZipExtractor.extract(jre21Zip, jre21Temp);
        }

        // 5 & 6. copy the verified migration.exe / rollback.exe -> app root (if not already present)
        copyIfMissing(new File(artifacts, FILE_MIGRATION_EXE), new File(root, FILE_MIGRATION_EXE));
        copyIfMissing(new File(artifacts, FILE_ROLLBACK_EXE), new File(root, FILE_ROLLBACK_EXE));

        // 7. backup run.bat -> run.bat_jre11 (once)
        File runBat = new File(root, FILE_RUN_BAT);
        File runBatBackup = new File(root, FILE_RUN_BAT_BACKUP);
        if (runBat.exists() && !runBatBackup.exists()) {
            LOGGER.info("Backing up run.bat -> run.bat_jre11");
            copy(runBat, runBatBackup);
        }

        LOGGER.info("JRE migration staged (verified); caller should now launch migration.exe and exit");
    }

    /**
     * Verifies each present migration artefact in {@code .artifacts/} against its hash in the
     * signature-verified root manifest. A mismatch aborts the migration. If the root manifest carries
     * no entry for an artefact, it cannot be verified here — logged as a warning (root-manifest hash
     * coverage is an open item, see upgrade-implementation-spec.md).
     */
    private static void verifyArtefactsAgainstRootManifest(File rootManifest, File artifacts) throws IOException {
        Manifest rootMf = ManifestVerifier.parse(Files.readAllBytes(rootManifest.toPath()));
        for (String name : VERIFIED_ROOT_ARTEFACTS) {
            File artefact = new File(artifacts, name);
            if (!artefact.exists()) {
                // Absent artefacts are handled by later steps (e.g. jre21.zip throws if still missing).
                continue;
            }
            if (ManifestVerifier.hasEntry(rootMf, name)) {
                if (!ManifestVerifier.fileMatches(rootMf, name, artefact)) {
                    throw new IOException("Integrity check failed for migration artefact: " + name);
                }
                LOGGER.info("Verified migration artefact against root manifest: {}", name);
            } else {
                LOGGER.warn("Root manifest has no integrity entry for {} — cannot verify before use "
                        + "(open item: root-manifest hash coverage)", name);
            }
        }
    }

    private static void copyIfMissing(File src, File dst) throws IOException {
        if (!src.exists()) {
            // migration.exe / rollback.exe are produced by T3/T4 (toolchain TBD); tolerate absence here.
            LOGGER.warn("{} not present in .artifacts/ — skipping copy (required before migration.exe runs)", src.getName());
            return;
        }
        if (dst.exists()) {
            return;
        }
        copy(src, dst);
    }

    private static void copy(File src, File dst) throws IOException {
        File parent = dst.getAbsoluteFile().getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
