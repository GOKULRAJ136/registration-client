package io.mosip.registration.launcher.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Resumable HTTP file download used by both {@code registration-launcher} (Java 11) and
 * {@code registration-services} (Java 21).
 * <p>
 * Compiled at release 11 with no dependency on registration-client / registration-services so it
 * can run under the Java 11 JRE during the Java 11 &rarr; 21 migration. Timeouts are passed in as
 * parameters (rather than read from the services {@code ApplicationContext}) to keep this module
 * dependency-free.
 */
public final class ResumableDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResumableDownloader.class);
    private static final String PART_SUFFIX = ".part";
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;
    private static final int BUFFER_SIZE = 8192;

    private ResumableDownloader() {
        // utility class
    }

    /**
     * Downloads {@code url} into {@code targetDir/fileName} with resume support.
     * <p>
     * The content is staged into a {@code <fileName>.part} file. If a partial file already exists
     * from an interrupted attempt, the download resumes from the last received byte using an HTTP
     * {@code Range} request. On successful completion the part file is atomically moved onto the
     * final file. If the download fails the part file is retained so a subsequent call can resume.
     *
     * @param url            the source URL
     * @param targetDir      the directory the file should be written into (created if missing)
     * @param fileName       the final file name within {@code targetDir}
     * @param connectTimeout connection timeout in milliseconds
     * @param readTimeout    read timeout in milliseconds (0 = infinite)
     * @throws IOException if the download cannot be completed
     */
    public static void download(String url, String targetDir, String fileName,
                                int connectTimeout, int readTimeout) throws IOException {
        LOGGER.info("Resumable download invoked, url : {}, target : {}/{}", url, targetDir, fileName);
        File dir = new File(targetDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File partFile = new File(dir, fileName + PART_SUFFIX);
        File targetFile = new File(dir, fileName);

        // At most two attempts: a resume attempt, and (only on a 416 whose part does not match the
        // server's size) a fresh restart with the stale part discarded.
        boolean allowResume = true;
        for (int attempt = 0; attempt < 2; attempt++) {
            long existing = (allowResume && partFile.exists()) ? partFile.length() : 0L;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(connectTimeout);
                connection.setReadTimeout(readTimeout);
                if (existing > 0) {
                    connection.setRequestProperty("Range", "bytes=" + existing + "-");
                    LOGGER.info("Resuming {} from byte {}", fileName, existing);
                }
                connection.connect();

                int status = connection.getResponseCode();
                boolean append;
                if (status == HttpURLConnection.HTTP_PARTIAL) {            // 206 - server honoured Range
                    append = true;
                } else if (status == HttpURLConnection.HTTP_OK) {          // 200 - server ignored Range, restart
                    append = false;
                    existing = 0L;
                } else if (status == HTTP_RANGE_NOT_SATISFIABLE) {         // 416 - part is at/over server size
                    long total = parseContentRangeTotal(connection.getHeaderField("Content-Range"));
                    if (total >= 0 && partFile.exists() && partFile.length() == total) {
                        LOGGER.info("Range not satisfiable for {} and part matches server size; finalizing", fileName);
                        finalizeDownload(partFile, targetFile);
                        return;
                    }
                    LOGGER.warn("416 for {} but local part ({} bytes) != server total ({}); restarting fresh",
                            fileName, partFile.exists() ? partFile.length() : 0L, total);
                    Files.deleteIfExists(partFile.toPath());
                    allowResume = false;
                    continue; // retry from scratch (no Range)
                } else {
                    throw new IOException("Unexpected HTTP status " + status + " while downloading " + url);
                }

                ensureSpaceForWrite(dir, partFile, connection.getContentLengthLong(), append);

                try (InputStream in = connection.getInputStream();
                     RandomAccessFile out = new RandomAccessFile(partFile, "rw")) {
                    if (append) {
                        out.seek(existing);
                    } else {
                        out.setLength(0);
                    }
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }

                finalizeDownload(partFile, targetFile);
                LOGGER.info("Resumable download completed : {}", fileName);
                return;

            } catch (IOException e) {
                LOGGER.error("Failed to download {} (partial file retained for resume)", url, e);
                throw e;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw new IOException("Failed to download " + url + " after restart");
    }

    /**
     * Disk-space guard for the write about to happen. On a full (200) restart the existing part will be
     * truncated, so its bytes are added back to the available figure. When the content length is
     * unknown (e.g. chunked transfer with no {@code Content-Length}) the pre-check cannot be performed
     * and is skipped with a warning (rather than silently passing a zero requirement).
     */
    private static void ensureSpaceForWrite(File dir, File partFile, long contentLength, boolean append)
            throws IOException {
        if (contentLength < 0) {
            LOGGER.warn("Content-Length unknown for {}; skipping disk-space pre-check", dir.getAbsolutePath());
            return;
        }
        long freeable = append ? 0L : (partFile.exists() ? partFile.length() : 0L);
        long usableSpace = dir.getUsableSpace() + freeable;
        if (contentLength > usableSpace) {
            LOGGER.error("Insufficient disk space at {} : required {} bytes, available {} bytes",
                    dir.getAbsolutePath(), contentLength, usableSpace);
            throw new IOException("Not enough space available to download. Required: " + contentLength
                    + " bytes, Available: " + usableSpace + " bytes");
        }
    }

    /** Parses the total size from a {@code Content-Range: bytes &lt;range&gt;/&lt;total&gt;} header; -1 if unknown. */
    private static long parseContentRangeTotal(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash == contentRange.length() - 1) {
            return -1;
        }
        String total = contentRange.substring(slash + 1).trim();
        if ("*".equals(total)) {
            return -1;
        }
        try {
            return Long.parseLong(total);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Guards against starting a write that cannot fit on the target's partition. Checks the usable
     * space of the actual target directory's partition rather than the file-system root, which is
     * important on Windows where the install drive may differ from {@code C:\}.
     *
     * @param targetDir     the directory the data will be written to
     * @param requiredBytes the number of bytes about to be written
     * @throws IOException if there is insufficient usable space
     */
    public static void ensureSpace(File targetDir, long requiredBytes) throws IOException {
        File dir = (targetDir != null && targetDir.exists()) ? targetDir : new File(".");
        long usableSpace = dir.getUsableSpace();
        if (requiredBytes > usableSpace) {
            LOGGER.error("Insufficient disk space at {} : required {} bytes, available {} bytes",
                    dir.getAbsolutePath(), requiredBytes, usableSpace);
            throw new IOException("Not enough space available to download. Required: " + requiredBytes
                    + " bytes, Available: " + usableSpace + " bytes");
        }
    }

    /**
     * Atomically moves the completed part file onto the target file. Falls back to a non-atomic
     * replace when the underlying file system does not support atomic moves.
     */
    private static void finalizeDownload(File partFile, File targetFile) throws IOException {
        try {
            Files.move(partFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(partFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
