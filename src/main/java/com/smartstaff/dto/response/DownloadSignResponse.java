package com.smartstaff.dto.response;

/**
 * Response from the download signing endpoint.
 *
 * @param url The signed download URL (includes exp and sig parameters)
 */
public record DownloadSignResponse(
        String url
) {}
