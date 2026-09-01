package dev.pdfbrowser.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "app.files")
public class FileBrowserProperties {
    private Path root = Path.of(System.getProperty("user.home"), "pdfbrowser-files");
    private long maxMarkdownBytes = 2L * 1024 * 1024;
    private int markdownCacheEntries = 32;
    private int markdownCacheSeconds = 300;
    private boolean readOnly = true;
    private long maxUploadBytes = 100L * 1024 * 1024;
    private int searchMaxDepth = 8;
    private int searchMaxResults = 200;

    public Path getRoot() { return root; }
    public void setRoot(Path root) { this.root = root; }
    public long getMaxMarkdownBytes() { return maxMarkdownBytes; }
    public void setMaxMarkdownBytes(long maxMarkdownBytes) { this.maxMarkdownBytes = maxMarkdownBytes; }
    public int getMarkdownCacheEntries() { return markdownCacheEntries; }
    public void setMarkdownCacheEntries(int markdownCacheEntries) { this.markdownCacheEntries = markdownCacheEntries; }
    public int getMarkdownCacheSeconds() { return markdownCacheSeconds; }
    public void setMarkdownCacheSeconds(int markdownCacheSeconds) { this.markdownCacheSeconds = markdownCacheSeconds; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
    public int getSearchMaxDepth() { return searchMaxDepth; }
    public void setSearchMaxDepth(int searchMaxDepth) { this.searchMaxDepth = searchMaxDepth; }
    public int getSearchMaxResults() { return searchMaxResults; }
    public void setSearchMaxResults(int searchMaxResults) { this.searchMaxResults = searchMaxResults; }
}
