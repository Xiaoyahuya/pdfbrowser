package dev.pdfbrowser.service;

import dev.pdfbrowser.config.FileBrowserProperties;
import dev.pdfbrowser.exception.FileBrowserException;
import dev.pdfbrowser.web.FileBrowserController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileBrowserControllerTest {
    @TempDir Path root;
    private FileBrowserController controller;
    private byte[] pdf;

    @BeforeEach
    void setUp() throws Exception {
        FileBrowserProperties properties = new FileBrowserProperties();
        properties.setRoot(root);
        SafePathResolver resolver = new SafePathResolver(properties);
        resolver.initialize();
        controller = new FileBrowserController(new FileBrowserService(resolver, properties));

        pdf = new byte[512];
        byte[] signature = "%PDF-1.4\n".getBytes();
        System.arraycopy(signature, 0, pdf, 0, signature.length);
        for (int index = signature.length; index < pdf.length; index++) {
            pdf[index] = (byte) (index & 0xff);
        }
        Files.write(root.resolve("sample.pdf"), pdf);
        Files.writeString(root.resolve("sample.md"), "# Cache me");
    }

    @Test
    void streamsSingleByteRangeWithRequiredHeaders() throws Exception {
        ResponseEntity<?> response = controller.raw("sample.pdf", false, "bytes=10-19");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(10);
        assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes 10-19/512");
        assertThat(response.getBody()).isInstanceOf(StreamingResponseBody.class);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ((StreamingResponseBody) response.getBody()).writeTo(output);
        assertThat(output.toByteArray()).containsExactly(java.util.Arrays.copyOfRange(pdf, 10, 20));
    }

    @Test
    void rejectsMultipleRangesExplicitly() {
        assertThatThrownBy(() -> controller.raw("sample.pdf", false, "bytes=0-9,20-29"))
                .isInstanceOfSatisfying(FileBrowserException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
                    assertThat(exception.code()).isEqualTo("MULTIPLE_RANGES_UNSUPPORTED");
                });
    }

    @Test
    void marksRepeatedMarkdownReadsAsCached() {
        ResponseEntity<String> first = controller.markdown("sample.md");
        ResponseEntity<String> second = controller.markdown("sample.md");

        assertThat(first.getHeaders().getFirst("X-PDFBrowser-Cache")).isEqualTo("MISS");
        assertThat(second.getHeaders().getFirst("X-PDFBrowser-Cache")).isEqualTo("HIT");
        assertThat(second.getHeaders().getCacheControl()).contains("max-age=300", "private");
    }

    @Test
    void reportsReadOnlyStorageCapabilities() {
        assertThat(controller.capabilities().writable()).isFalse();
        assertThat(controller.capabilities().maxUploadBytes()).isPositive();
    }
}
