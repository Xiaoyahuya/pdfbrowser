package dev.pdfbrowser.model;

public record FileEntry(
        String path,
        String name,
        EntryType type,
        Long size,
        long lastModified,
        String mimeType,
        boolean previewable
) {}

