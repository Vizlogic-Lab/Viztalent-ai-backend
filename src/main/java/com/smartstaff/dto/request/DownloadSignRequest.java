package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to sign a download URL.
 *
 * @param path The download path to sign (e.g., "/api/jd/123/download")
 */
public record DownloadSignRequest(
        @NotBlank(message = "path is required")
        String path
) {}
