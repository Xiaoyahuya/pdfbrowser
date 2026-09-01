package dev.pdfbrowser.model;

import java.time.Instant;

public record NasMountView(
        String id,
        String name,
        String storageKey,
        String provider,
        String host,
        String share,
        String username,
        String domain,
        String url,
        String vendor,
        String clientId,
        String rootFolderId,
        String status,
        Instant createdAt,
        String lastError,
        boolean readOnly,
        boolean useProxy
) {}
