package io.mosip.registration.controller;

import io.mosip.registration.launcher.JreMigrationStager;
import io.mosip.registration.launcher.JreVersionDetector;
import io.mosip.registration.launcher.LauncherConfig;
import io.mosip.registration.launcher.LauncherDialogs;
import io.mosip.registration.launcher.LibUpdateResult;
import io.mosip.registration.launcher.LibUpdater;
import io.mosip.registration.launcher.MigrationCleaner;
import io.mosip.registration.launcher.NormalStartup;
import io.mosip.registration.launcher.StartupAction;
import io.mosip.registration.launcher.StartupEvaluator;
import io.mosip.registration.launcher.common.ManifestVerifier;
import io.mosip.registration.launcher.common.ResumableDownloader;
import io.mosip.registration.launcher.common.SignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.PublicKey;

/**
 * Entry point of {@code _launcher.jar} — the sole entry point of the Registration Client from 1.3.0
 * onwards (the {@code main()} in {@code registration-client.jar} is removed in T6).
 * <p>
 * Kept under the {@code io.mosip.registration.controller} package with the same FQN as the legacy
 * client entry point so the existing {@code run.bat} resolves it from {@code _launcher.jar} during
 * the {@code <1.3.0 -> 1.3.0} transition (classpath order: {@code _launcher.jar} sorts first).
 * <p>
 * <b>Constraint:</b> the migration code paths run under the Java 11 JRE and MUST NOT load any class
 * from {@code registration-client.jar} or other Java 21 jars. Normal startup (the Java 21 path) must
 * invoke {@code ClientApplication} reflectively, never via a compile-time import.
 */
public class Initialization {

    private static final Logger LOGGER = LoggerFactory.getLogger(Initialization.class);

    private static final File ROOT_MANIFEST = new File("MANIFEST.MF");
    private static final File ROOT_SIGNATURE = new File("MANIFEST.MF.sig");
    private static final File LIB_MANIFEST = new File("lib/MANIFEST.MF");
    private static final File TEMP_DIR = new File(".TEMP");
    private static final File APP_ROOT = new File(".");
    private static final File CONFIG_FILE = new File("mosip-application.properties");
    private static final String TRUSTED_CERT = "provider.pem";

    private static final int CONNECT_TIMEOUT = 50000;
    private static final int READ_TIMEOUT = 0;

    public static void main(String[] args) {
        int jreMajor = JreVersionDetector.currentMajorVersion();
        LOGGER.info("_launcher.jar starting on JRE major version {}", jreMajor);

        try {
            PublicKey trustedKey = loadTrustedKey();
            StartupAction action = StartupEvaluator.evaluate(
                    ROOT_MANIFEST, ROOT_SIGNATURE, LIB_MANIFEST, trustedKey, jreMajor);
            handle(action, jreMajor, args, trustedKey);
        } catch (Exception e) {
            LOGGER.error("Launcher startup failed", e);
            LauncherDialogs.error("Startup failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void handle(StartupAction action, int jreMajor, String[] args, PublicKey trustedKey) {
        switch (action) {
            case SIGNATURE_MISSING:
                handleSignatureMissing(jreMajor, args, trustedKey);
                break;
            case ABORT_INVALID_SIGNATURE:
                // Case B: do not re-download anything (possible MITM); inform operator and exit.
                LauncherDialogs.error("Security alert: MANIFEST.MF signature invalid. Startup aborted.");
                System.exit(1);
                break;
            case NORMAL_STARTUP:
                LOGGER.info("Versions match — cleaning up migration artefacts and starting normally (step 6)");
                MigrationCleaner.cleanup(APP_ROOT);
                try {
                    NormalStartup.launch(args);
                } catch (ReflectiveOperationException e) {
                    LOGGER.error("Failed to launch ClientApplication", e);
                    LauncherDialogs.error("Failed to start the application: " + e.getMessage());
                    System.exit(1);
                }
                break;
            case MIGRATE_JRE:
                handleJreMigration(trustedKey);
                break;
            case UPDATE_LIB:
                handleLibUpdate(jreMajor, trustedKey);
                break;
            case ABORT_UNSUPPORTED_JRE:
                LOGGER.error("Unsupported JRE major version {} — cannot upgrade", jreMajor);
                LauncherDialogs.error("Unsupported Java runtime (version " + jreMajor
                        + "). Please reinstall the Registration Client.");
                System.exit(1);
                break;
            default:
                LOGGER.error("Unhandled startup action: {}", action);
                break;
        }
    }

    /** Case C: download the missing root {@code MANIFEST.MF.sig}, then re-evaluate once. */
    private static void handleSignatureMissing(int jreMajor, String[] args, PublicKey trustedKey) {
        try {
            LauncherConfig config = LauncherConfig.load(CONFIG_FILE);
            String version = requireVersion();
            LOGGER.info("Downloading missing MANIFEST.MF.sig for version {}", version);
            ResumableDownloader.download(config.rootManifestSigUrl(version),
                    APP_ROOT.getPath(), ROOT_SIGNATURE.getName(), CONNECT_TIMEOUT, READ_TIMEOUT);

            StartupAction action = StartupEvaluator.evaluate(
                    ROOT_MANIFEST, ROOT_SIGNATURE, LIB_MANIFEST, trustedKey, jreMajor);
            if (action == StartupAction.SIGNATURE_MISSING) {
                LauncherDialogs.error("Unable to obtain MANIFEST.MF signature. Startup aborted.");
                System.exit(1);
            } else {
                handle(action, jreMajor, args, trustedKey);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to download/verify MANIFEST.MF.sig", e);
            LauncherDialogs.error("Failed to obtain MANIFEST.MF signature: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Step 3: prepare the JRE migration, then launch {@code migration.exe} (launch blocked on T3). */
    private static void handleJreMigration(PublicKey trustedKey) {
        LOGGER.info("Version change on JRE 11 — preparing JRE migration (step 3)");
        try {
            LauncherConfig config = LauncherConfig.load(CONFIG_FILE);
            String version = requireVersion();
            JreMigrationStager.stage(APP_ROOT, ROOT_MANIFEST,
                    config.libManifestUrl(version), config.libManifestSigUrl(version), config.libZipUrl(version),
                    trustedKey, CONNECT_TIMEOUT, READ_TIMEOUT);
            // TODO(T3): launch migration.exe (native; toolchain reopened) and System.exit(0).
            LOGGER.warn("JRE migration staged; launching migration.exe is blocked on T3 (native-exe toolchain TBD)");
        } catch (Exception e) {
            LOGGER.error("Failed to prepare JRE migration", e);
            LauncherDialogs.error("Failed to prepare the update: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Step 5: download + verify + stage the new lib into {@code .TEMP/}, then prompt for restart. */
    private static void handleLibUpdate(int jreMajor, PublicKey trustedKey) {
        LOGGER.info("Version change on JRE {} — lib-only update path (step 5)", jreMajor);
        try {
            LauncherConfig config = LauncherConfig.load(CONFIG_FILE);
            String version = requireVersion();
            LibUpdateResult result = LibUpdater.update(
                    config.libManifestUrl(version), config.libManifestSigUrl(version), config.libZipUrl(version),
                    TEMP_DIR, trustedKey, CONNECT_TIMEOUT, READ_TIMEOUT);
            switch (result) {
                case READY_RESTART:
                    LauncherDialogs.info("Update ready. Please restart the application.");
                    System.exit(0);
                    break;
                case ABORT_INVALID_SIGNATURE:
                    LauncherDialogs.error("Security alert: lib signature invalid. Update aborted.");
                    System.exit(1);
                    break;
                case VERIFY_FAILED:
                    LauncherDialogs.error("Update integrity check failed. Please retry.");
                    System.exit(1);
                    break;
                default:
                    LOGGER.error("Unhandled lib update result: {}", result);
                    break;
            }
        } catch (Exception e) {
            LOGGER.error("Lib update failed", e);
            LauncherDialogs.error("Update failed: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Resolves the target version from the root manifest, failing clearly if it is absent/blank. */
    private static String requireVersion() throws IOException {
        String version = ManifestVerifier.getVersion(ROOT_MANIFEST);
        if (version == null || version.trim().isEmpty()) {
            throw new IOException(ROOT_MANIFEST.getName() + " is missing the Manifest-Version attribute");
        }
        return version;
    }

    private static PublicKey loadTrustedKey() throws Exception {
        try (InputStream cert = Initialization.class.getClassLoader().getResourceAsStream(TRUSTED_CERT)) {
            if (cert == null) {
                throw new IllegalStateException(TRUSTED_CERT + " not found on the classpath");
            }
            return SignatureVerifier.loadPublicKeyFromCertificate(cert);
        }
    }
}
