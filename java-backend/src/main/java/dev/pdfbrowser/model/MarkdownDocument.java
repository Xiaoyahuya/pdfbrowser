package dev.pdfbrowser.model;

public record MarkdownDocument(String content, String etag, boolean cacheHit) {}
