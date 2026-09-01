package dev.pdfbrowser.service;

import dev.pdfbrowser.config.FileBrowserProperties;
import dev.pdfbrowser.exception.FileBrowserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafePathResolverTest {
    @TempDir Path root;
    private SafePathResolver resolver;

    @BeforeEach void setUp() {
        FileBrowserProperties properties = new FileBrowserProperties();
        properties.setRoot(root);
        resolver = new SafePathResolver(properties);
        resolver.initialize();
    }

    @Test void resolvesRelativePathInsideRoot() throws IOException {
        Path file = Files.writeString(root.resolve("notes.md"), "# Notes");
        assertThat(resolver.resolveExisting("notes.md")).isEqualTo(file.toRealPath());
        assertThat(resolver.toLogicalPath(file)).isEqualTo("notes.md");
    }

    @Test void rejectsParentTraversal() {
        assertThatThrownBy(() -> resolver.resolveExisting("../secret.pdf"))
                .isInstanceOf(FileBrowserException.class).hasMessageContaining("根目录");
    }

    @Test void rejectsAbsolutePath() {
        assertThatThrownBy(() -> resolver.resolveExisting(root.resolve("secret.pdf").toString()))
                .isInstanceOf(FileBrowserException.class).hasMessageContaining("根目录");
    }
}

