package com.unfurl.fabric.studio;

public record StudioAssetContent(byte[] bytes, String mediaType, String sha256) {
    public StudioAssetContent {
        bytes = bytes == null ? new byte[0] : bytes.clone();
        mediaType = mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
        sha256 = sha256 == null ? "" : sha256;
    }
}
