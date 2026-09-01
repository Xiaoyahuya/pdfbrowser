package dev.pdfbrowser.service;

import dev.pdfbrowser.config.FileBrowserProperties;
import dev.pdfbrowser.exception.FileBrowserException;
import dev.pdfbrowser.model.DirectoryListing;
import dev.pdfbrowser.model.EntryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileBrowserServiceTest {
    @TempDir Path root;
    private FileBrowserService service;
    private FileBrowserProperties properties;

    @BeforeEach void setUp() {
        properties = new FileBrowserProperties();
        properties.setRoot(root);
        SafePathResolver resolver = new SafePathResolver(properties);
        resolver.initialize();
        service = new FileBrowserService(resolver, properties);
    }

    @Test void listsDirectoriesBeforePreviewableFiles() throws IOException {
        Files.createDirectory(root.resolve("docs"));
        Files.write(root.resolve("paper.pdf"), "%PDF".getBytes());
        Files.writeString(root.resolve("README.md"), "# Hello");
        Files.writeString(root.resolve("data.txt"), "text");
        DirectoryListing listing = service.list("");
        assertThat(listing.entries()).extracting(entry -> entry.type())
                .containsExactly(EntryType.DIRECTORY, EntryType.OTHER, EntryType.PDF, EntryType.MARKDOWN);
        assertThat(listing.entries()).filteredOn(entry -> entry.type() == EntryType.PDF)
                .allMatch(entry -> entry.previewable() && "application/pdf".equals(entry.mimeType()));
    }

    @Test void readsUtf8Markdown() throws IOException {
        Files.writeString(root.resolve("说明.md"), "# 标题\n\n正文");
        assertThat(service.readMarkdown("说明.md").content()).contains("标题", "正文");
    }

    @Test void rejectsMarkdownOverConfiguredLimit() throws IOException {
        properties.setMaxMarkdownBytes(4);
        Files.writeString(root.resolve("large.md"), "12345");
        assertThatThrownBy(() -> service.readMarkdown("large.md"))
                .isInstanceOf(FileBrowserException.class).hasMessageContaining("大小限制");
    }

    @Test void cachesMarkdownBetweenReads() throws IOException {
        Files.writeString(root.resolve("cached.md"), "# 第一次读取");
        assertThat(service.readMarkdown("cached.md").cacheHit()).isFalse();
        assertThat(service.readMarkdown("cached.md").cacheHit()).isTrue();
    }

    @Test void uploadsIntoWritableDirectoryWithoutOverwriting() throws IOException {
        properties.setReadOnly(false);
        byte[] content = "# uploaded".getBytes();
        var entry = service.upload("", "uploaded.md", content.length, new ByteArrayInputStream(content));

        assertThat(entry.name()).isEqualTo("uploaded.md");
        assertThat(Files.readString(root.resolve("uploaded.md"))).isEqualTo("# uploaded");
        assertThatThrownBy(() -> service.upload("", "uploaded.md", content.length, new ByteArrayInputStream(content)))
                .isInstanceOf(FileBrowserException.class).hasMessageContaining("已经存在");
    }
}
