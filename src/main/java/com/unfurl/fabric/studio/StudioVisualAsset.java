package com.unfurl.fabric.studio;

public record StudioVisualAsset(
        String assetId,
        String path,
        String mediaType,
        String sha256,
        String url,
        String status,
        String warning
) {
    public StudioVisualAsset {
        assetId = assetId == null ? "" : assetId;
        path = path == null ? "" : path;
        mediaType = mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
        sha256 = sha256 == null ? "" : sha256;
        url = url == null ? "" : url;
        status = status == null || status.isBlank() ? "FALLBACK_REQUIRED" : status;
        warning = warning == null ? "" : warning;
    }
}
