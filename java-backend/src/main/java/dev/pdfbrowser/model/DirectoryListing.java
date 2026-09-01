package dev.pdfbrowser.model;

import java.util.List;

public record DirectoryListing(String path, String name, String parent, List<FileEntry> entries) {}

