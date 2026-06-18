package io.mosip.registration.launcher;

import io.mosip.registration.launcher.common.ManifestVerifier;
import io.mosip.registration.launcher.common.SignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.PublicKey;

/**
 * Step 2 of the launcher (design doc): on every startup, verify the detached signature of the root
 * {@code ./MANIFEST.MF} and compare its version against {@code lib/MANIFEST.MF}, then decide what to
 * do next. This class holds only the <b>pure decision</b> — downloading a missing signature, showing
 * dialogs, exiting the JVM and the actual migrate/update/startup work are side effects handled by the
 * {@code Initialization} entry point based on the returned {@link StartupAction}.
 */
public final class StartupEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger(StartupEvaluator.class);
    private static final int JAVA_11 = 11;
    private static final int JAVA_21 = 21;

    private StartupEvaluator() {
        // utility class
    }

    /**
     * Evaluates the startup state.
     *
     * @param rootManifest     the orchestration manifest {@code ./MANIFEST.MF}
     * @param rootSignature    its detached signature {@code ./MANIFEST.MF.sig} (may be absent)
     * @param libManifest      the per-file manifest {@code lib/MANIFEST.MF} (may be absent pre-1.3.0)
     * @param trustedKey       the public key from the embedded {@code provider.pem}
     * @param jreMajorVersion  the running JRE major version (see {@link JreVersionDetector})
     * @return the action the entry point must take next
     * @throws IOException if {@code rootManifest} cannot be read
     */
    public static StartupAction evaluate(File rootManifest, File rootSignature, File libManifest,
                                         PublicKey trustedKey, int jreMajorVersion) throws IOException {
        // --- signature gate (Cases B / C) ---
        if (rootSignature == null || !rootSignature.exists()) {
            LOGGER.info("Root MANIFEST.MF.sig not present");
            return StartupAction.SIGNATURE_MISSING;
        }
        byte[] manifestBytes = Files.readAllBytes(rootManifest.toPath());
        byte[] signatureBytes = Files.readAllBytes(rootSignature.toPath());
        if (!SignatureVerifier.verify(manifestBytes, signatureBytes, trustedKey)) {
            LOGGER.error("Root MANIFEST.MF signature is INVALID — aborting startup (possible tamper/MITM)");
            return StartupAction.ABORT_INVALID_SIGNATURE;
        }

        // --- version comparison (a missing lib manifest, e.g. pre-1.3.0, counts as 'differs') ---
        boolean versionsMatch = libManifest != null && libManifest.exists()
                && ManifestVerifier.versionsMatch(rootManifest, libManifest);
        if (versionsMatch) {
            LOGGER.info("Root and lib manifest versions match — normal startup");
            return StartupAction.NORMAL_STARTUP;
        }

        LOGGER.info("Manifest versions differ — migration required (JRE major version {})", jreMajorVersion);
        if (jreMajorVersion == JAVA_11) {
            return StartupAction.MIGRATE_JRE;
        }
        if (jreMajorVersion >= JAVA_21) {
            return StartupAction.UPDATE_LIB;
        }
        LOGGER.error("Unsupported JRE major version {} for upgrade (expected 11 or >= 21)", jreMajorVersion);
        return StartupAction.ABORT_UNSUPPORTED_JRE;
    }
}
