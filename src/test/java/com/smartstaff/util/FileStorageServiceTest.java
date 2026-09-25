package com.smartstaff.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageServiceTest {

    @Test
    @DisplayName("sha256Hex matches the well-known digest of 'abc'")
    void sha256() {
        assertThat(FileStorageService.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("stores under the requested subdirectory, and never overwrites an earlier upload of the same name")
    void storesWithoutCollisions(@TempDir Path root) throws IOException {
        FileStorageService storage = new FileStorageService(root.toString());
        var file = new MockMultipartFile("file", "resume.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        Path a = storage.store(file, "job-1");
        Path b = storage.store(file, "job-1");

        assertThat(a).isNotEqualTo(b).exists();
        assertThat(a.getParent().getFileName().toString()).isEqualTo("job-1");
        assertThat(Files.readString(a)).isEqualTo("hello");
    }

    @Test
    @DisplayName("hostile filenames are sanitised — no path separators survive into the stored name")
    void sanitisesFilenames(@TempDir Path root) throws IOException {
        FileStorageService storage = new FileStorageService(root.toString());
        var file = new MockMultipartFile("file", "../../etc/passwd", "text/plain", "x".getBytes(StandardCharsets.UTF_8));

        Path stored = storage.store(file, "job-1");

        assertThat(stored.normalize()).startsWith(root.toAbsolutePath().normalize());
        assertThat(stored.getFileName().toString()).doesNotContain("/").doesNotContain("\\");
    }

    @Test
    @DisplayName("a subdirectory that escapes the storage root is refused")
    void refusesPathTraversalInSubdir(@TempDir Path root) {
        FileStorageService storage = new FileStorageService(root.toString());
        var file = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> storage.store(file, "../outside")).isInstanceOf(IOException.class);
    }
}
