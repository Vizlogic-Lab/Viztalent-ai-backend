package com.smartstaff.dto.response;

import java.time.Instant;

/** See Jobs.jsx's JobDetailModal resume rows: r.filename, r.size_bytes,
 *  r.uploaded_at, r.download_url (relative — frontend prefixes API_BASE). */
public record ResumeSummaryResponse(
        String filename,
        long size_bytes,
        Instant uploaded_at,
        String download_url
) {}
