package dev.pdfbrowser.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NasMountRequest(
        @NotBlank @Size(max = 40) String name,
        @Size(max = 32) String provider,
        @Size(max = 64) String host,
        @Size(max = 128) String share,
        @Size(max = 128) String username,
        @Size(max = 512) String password,
        @Size(max = 128) String domain,
        boolean useProxy,
        @Size(max = 2048) String url,
        @Size(max = 32) String vendor,
        @Size(max = 32768) String oauthToken,
        @Size(max = 256) String clientId,
        @Size(max = 512) String clientSecret,
        @Size(max = 256) String rootFolderId
) {}
