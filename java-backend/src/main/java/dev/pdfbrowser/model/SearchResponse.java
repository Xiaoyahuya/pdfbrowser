package dev.pdfbrowser.model;

import java.util.List;

public record SearchResponse(String query, String path, List<FileEntry> entries, boolean truncated) {}

