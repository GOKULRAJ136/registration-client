package io.mosip.registration.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 6 cleanup (design doc): when the manifest versions match, any migration artefacts left over
 * from a completed prior migration are removed before normal startup. Best-effort — failures are
 * logged but never block startup.
 */
public final class MigrationCleaner {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationCleaner.class);

    private static final String[] ARTEFACT_DIRS = {"jre21_temp", ".artifacts"};
    private static final String[] ARTEFACT_FILES = {"run.bat_jre11", "migration.exe", "rollback.exe"};

    private MigrationCleaner() {
        // utility class
    }

    /**
     * Removes the known migration artefacts under {@code baseDir} if present.
     *
     * @param baseDir the application root
     * @return the names of artefacts that were present and successfully removed
     */
    public static List<String> cleanup(File baseDir) {
        List<String> removed = new ArrayList<>();
        for (String name : ARTEFACT_DIRS) {
            File dir = new File(baseDir, name);
            if (dir.exists() && deleteRecursively(dir)) {
                removed.add(name);
            }
        }
        for (String name : ARTEFACT_FILES) {
            File file = new File(baseDir, name);
            if (file.exists()) {
                if (file.delete()) {
                    removed.add(name);
                } else {
                    LOGGER.warn("Could not delete migration artefact {}", file);
                }
            }
        }
        if (!removed.isEmpty()) {
            LOGGER.info("Cleaned up migration artefacts: {}", removed);
        }
        return removed;
    }

    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        boolean deleted = file.delete();
        if (!deleted) {
            LOGGER.warn("Could not delete {}", file);
        }
        return deleted;
    }
}
