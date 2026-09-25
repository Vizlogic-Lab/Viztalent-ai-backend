package com.smartstaff.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Local-disk file storage (per SMARTSTAFF_BACKEND_DESIGN.md §2 — S3/MinIO
 *  is a later concern). Files are stored under a per-job subdirectory with a
 *  UUID-prefixed name so uploads never collide or overwrite each other. */
@Service
public class FileStorageService {

    private final Path root;

    public FileStorageService(@Value("${app.storage.dir}") String storageDir) {
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create storage dir: " + root, e);
        }
    }

    /** Stores a file under storage/{subdir}/, returns the absolute path. */
    public Path store(MultipartFile file, String subdir) throws IOException {
        Path dir = root.resolve(subdir).normalize();
        if (!dir.startsWith(root)) throw new IOException("Invalid subdir");
        Files.createDirectories(dir);
        String safeOriginal = sanitize(file.getOriginalFilename());
        Path target = dir.resolve(UUID.randomUUID() + "_" + safeOriginal);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    public byte[] readAll(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    public long size(Path path) throws IOException {
        return Files.size(path);
    }

    public void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort — an orphaned file on disk isn't worth failing the request over.
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "file";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
