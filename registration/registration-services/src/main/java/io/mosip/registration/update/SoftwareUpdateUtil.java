package io.mosip.registration.update;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.HMACUtils2;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.launcher.common.ResumableDownloader;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class SoftwareUpdateUtil {

    private static final Logger LOGGER = AppConfig.getLogger(SoftwareUpdateUtil.class);
    private static final String CONNECTION_TIMEOUT = "mosip.registration.sw.file.download.connection.timeout";
    private static final String READ_TIMEOUT = "mosip.registration.sw.file.download.read.timeout";
    private static final String libFolder = "lib/";
    private static final String UNKNOWN_JARS = ".UNKNOWN_JARS";
    private static final String TEMP_DIRECTORY = ".TEMP";
    private static final int DEFAULT_CONNECTION_TIMEOUT = 50000;
    private static final int DEFAULT_READ_TIMEOUT = 0;

    protected static boolean deleteUnknownJars(Manifest localManifest) throws IOException {
        StringBuilder builder = new StringBuilder();
        File dir = new File(libFolder);
        Objects.requireNonNull(dir.listFiles(), "No files found in libs");
        File[] libraries = dir.listFiles();
        Map<String, Attributes> entries = localManifest.getEntries();
        for (File file : libraries) {
            if(!entries.containsKey(file.getName())) {
                LOGGER.error("Unknown file found {}", file.getName());
                deleteFile(file.getCanonicalPath());
                builder.append(file.getName());
                builder.append("\n");
            }
        }

        byte[] bytes =  builder.toString().trim().getBytes(StandardCharsets.UTF_8);
        if(bytes.length > 0) {
            LOGGER.error("Writing the unknown jar names");
            FileUtils.writeByteArrayToFile(new File(UNKNOWN_JARS), bytes);
            return true;
        }
        return false;
    }

    protected static boolean validateJarChecksum(File file, Attributes entryAttributes) {
        try {
            if(entryAttributes != null) {
                String checkSum = HMACUtils2.digestAsPlainText(Files.readAllBytes(file.toPath()));
                String manifestCheckSum = entryAttributes.getValue(Attributes.Name.CONTENT_TYPE);
                return manifestCheckSum.equals(checkSum);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to check the file {} validity", file.getName(), e);
        }
        return false;
    }

    protected static void download(String url, String fileName) throws RegBaseCheckedException {
        LOGGER.info("invoking url : {}", url);
        try {
            File tempDir = new File(TEMP_DIRECTORY);
            if(!tempDir.exists()) { tempDir.mkdirs(); }

            Integer connectionTimeout = ApplicationContext.getIntValueFromApplicationMap(CONNECTION_TIMEOUT);
            if(connectionTimeout == null) { connectionTimeout = 50000; }
            Integer readTimeout = ApplicationContext.getIntValueFromApplicationMap(READ_TIMEOUT);
            if(readTimeout == null) { readTimeout = 0; }

            URL fileUrl = new URL(url);
            FileUtils.copyURLToFile(fileUrl, new File(TEMP_DIRECTORY + File.separator + fileName),
                    connectionTimeout, readTimeout);
            return;

        } catch (IOException e) {
            LOGGER.error("Failed to download {}", url, e);
        }
        throw new RegBaseCheckedException("REG-BUILD-005", "Failed to download " + url);
    }

    protected static InputStream download(String url) throws RegBaseCheckedException {
        LOGGER.info("invoking url : {}", url);
        try {
            Integer connectionTimeout = ApplicationContext.getIntValueFromApplicationMap(CONNECTION_TIMEOUT);
            if(connectionTimeout == null) { connectionTimeout = 50000; }
            Integer readTimeout = ApplicationContext.getIntValueFromApplicationMap(READ_TIMEOUT);
            if(readTimeout == null) { readTimeout = 0; }

            final URLConnection connection = new URL(url).openConnection();
            connection.setConnectTimeout(connectionTimeout);
            connection.setReadTimeout(readTimeout);
            return connection.getInputStream();

        } catch (IOException e) {
            LOGGER.error("Failed to download {}", url, e);
        }
        throw new RegBaseCheckedException("REG-BUILD-005", "Failed to download " + url);
    }

    /**
     * Downloads {@code url} into {@code targetDir/fileName} with resume support.
     * <p>
     * Delegates to {@link ResumableDownloader} (in the Java 11 {@code registration-launcher-common}
     * module shared with {@code _launcher.jar}), supplying the configured timeouts and translating
     * the low-level {@link IOException} into the service-layer {@link RegBaseCheckedException}.
     *
     * @param url       the source URL
     * @param targetDir the directory the file should be written into (created if missing)
     * @param fileName  the final file name within {@code targetDir}
     * @throws RegBaseCheckedException if the download cannot be completed
     */
    protected static void downloadResumable(String url, String targetDir, String fileName) throws RegBaseCheckedException {
        try {
            ResumableDownloader.download(url, targetDir, fileName,
                    getTimeout(CONNECTION_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT),
                    getTimeout(READ_TIMEOUT, DEFAULT_READ_TIMEOUT));
        } catch (IOException e) {
            throw new RegBaseCheckedException("REG-BUILD-005", "Failed to download " + url);
        }
    }

    /**
     * Guards against starting a download that cannot fit on the target's partition. Delegates to
     * {@link ResumableDownloader#ensureSpace(File, long)} and translates the low-level
     * {@link IOException} into the service-layer {@link RegBaseCheckedException}.
     *
     * @param targetDir     the directory the download will be written to
     * @param requiredBytes the number of bytes about to be written
     * @throws RegBaseCheckedException if there is insufficient usable space
     */
    protected static void ensureSpace(File targetDir, long requiredBytes) throws RegBaseCheckedException {
        try {
            ResumableDownloader.ensureSpace(targetDir, requiredBytes);
        } catch (IOException e) {
            throw new RegBaseCheckedException("REG-BUILD-004", e.getMessage());
        }
    }

    private static int getTimeout(String key, int defaultValue) {
        try {
            Integer value = ApplicationContext.getIntValueFromApplicationMap(key);
            return value == null ? defaultValue : value;
        } catch (RuntimeException e) {
            // e.g. a non-numeric configured value -> NumberFormatException; fall back to the default
            LOGGER.warn("Invalid value for {}, using default {} : {}", key, defaultValue, e.getMessage());
            return defaultValue;
        }
    }

    protected static boolean deleteFile(String filePath) {
        LOGGER.info("Deleting file {}", filePath);
        Path path = Path.of(filePath);
        try {
            FileUtils.forceDelete(path.toFile());
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to delete file {}", path, e);
            deleteFileOnExit(filePath);
        }
        return false;
    }

    protected static void deleteFileOnExit(String filePath) {
        LOGGER.info("Deleting file {}", filePath);
        Path path = Path.of(filePath);
        try {
            FileUtils.forceDeleteOnExit(path.toFile());
        } catch (Exception e) {
            LOGGER.error("Failed to delete file on exit {}", path, e);
        }
    }

    protected static void clearTempDirectory() {
        try {
            File dir = new File(TEMP_DIRECTORY);
            if(dir.exists()) {
                FileUtils.cleanDirectory(dir);
                if (dir.delete()) 
                	LOGGER.info("Deleted temp file");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to clean and delete temp", e);
        }
    }
}
