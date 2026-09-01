package dev.pdfbrowser.web;

import dev.pdfbrowser.exception.FileBrowserException;
import dev.pdfbrowser.model.DirectoryListing;
import dev.pdfbrowser.model.EntryType;
import dev.pdfbrowser.model.MarkdownDocument;
import dev.pdfbrowser.model.NasMountRequest;
import dev.pdfbrowser.model.NasMountView;
import dev.pdfbrowser.model.SearchResponse;
import dev.pdfbrowser.service.NasFileBrowserService;
import dev.pdfbrowser.service.NasMountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/nas")
public class NasController {
    private static final String ACTION_HEADER = "X-PDFBrowser-Action";
    private final NasMountService mountService;
    private final NasFileBrowserService fileService;

    public NasController(NasMountService mountService, NasFileBrowserService fileService) {
        this.mountService = mountService;
        this.fileService = fileService;
    }

    @GetMapping("/mounts")
    public ResponseEntity<List<NasMountView>> mounts() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mountService.list());
    }

    @PostMapping("/mounts")
    public ResponseEntity<NasMountView> create(
            @Valid @RequestBody NasMountRequest request,
            @RequestHeader(value = ACTION_HEADER, required = false) String action,
            HttpServletRequest servletRequest) {
        requireSecureMutation(action, servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(mountService.create(request));
    }

    @PostMapping("/mounts/{id}/connect")
    public ResponseEntity<NasMountView> connect(
            @PathVariable String id,
            @RequestHeader(value = ACTION_HEADER, required = false) String action,
            HttpServletRequest servletRequest) {
        requireSecureMutation(action, servletRequest);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mountService.connect(id));
    }

    @DeleteMapping("/mounts/{id}")
    public ResponseEntity<Void> remove(
            @PathVariable String id,
            @RequestHeader(value = ACTION_HEADER, required = false) String action,
            HttpServletRequest servletRequest) {
        requireSecureMutation(action, servletRequest);
        mountService.remove(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mounts/{id}/files")
    public DirectoryListing listFiles(
            @PathVariable String id,
            @RequestParam(defaultValue = "") String path) {
        return fileService.list(id, path);
    }

    @GetMapping("/mounts/{id}/files/search")
    public SearchResponse search(
            @PathVariable String id,
            @RequestParam(defaultValue = "") String path,
            @RequestParam String q) {
        return fileService.search(id, path, q);
    }

    @GetMapping(value = "/mounts/{id}/files/markdown", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> markdown(@PathVariable String id, @RequestParam String path) {
        MarkdownDocument document = fileService.readMarkdown(id, path);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .eTag(document.etag())
                .header("X-PDFBrowser-Cache", document.cacheHit() ? "HIT" : "MISS")
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(document.content());
    }

    @GetMapping("/mounts/{id}/files/raw")
    public ResponseEntity<StreamingResponseBody> raw(
            @PathVariable String id,
            @RequestParam String path,
            @RequestParam(defaultValue = "false") boolean download,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        Path file = fileService.requireFile(id, path);
        EntryType type = fileService.classify(file);
        String detectedMediaType = fileService.mediaType(file);
        boolean inlineImage = detectedMediaType.toLowerCase(Locale.ROOT).startsWith("image/");
        if (!download && type != EntryType.PDF && !inlineImage) {
            throw new FileBrowserException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INLINE_PREVIEW_UNSUPPORTED",
                    "该文件类型不支持内联预览");
        }

        long fileSize;
        long lastModified;
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            fileSize = attributes.size();
            lastModified = attributes.lastModifiedTime().toMillis();
        } catch (java.io.IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "REMOTE_FILE_UNREADABLE",
                    "无法读取远程文件", exception);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(detectedMediaType));
        headers.setContentDisposition(download
                ? ContentDisposition.attachment().filename(file.getFileName().toString(), StandardCharsets.UTF_8).build()
                : ContentDisposition.inline().filename(file.getFileName().toString(), StandardCharsets.UTF_8).build());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setCacheControl("private, max-age=300");
        headers.setETag("\"" + Long.toHexString(fileSize) + "-" + Long.toHexString(lastModified) + "\"");
        headers.setLastModified(lastModified);

        if (rangeHeader == null || rangeHeader.isBlank()) {
            headers.setContentLength(fileSize);
            StreamingResponseBody fullBody = outputStream -> {
                try (var inputStream = Files.newInputStream(file, StandardOpenOption.READ)) {
                    inputStream.transferTo(outputStream);
                }
            };
            return ResponseEntity.ok().headers(headers).body(fullBody);
        }

        List<HttpRange> ranges;
        try {
            ranges = HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException exception) {
            throw invalidRange();
        }
        if (ranges.size() != 1) throw invalidRange();

        long start;
        long end;
        try {
            start = ranges.get(0).getRangeStart(fileSize);
            end = ranges.get(0).getRangeEnd(fileSize);
        } catch (Exception exception) {
            throw invalidRange();
        }
        if (fileSize <= 0 || start < 0 || end < start || end >= fileSize) throw invalidRange();

        long rangeLength = end - start + 1;
        headers.setContentLength(rangeLength);
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileSize);
        StreamingResponseBody body = outputStream -> {
            try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                channel.position(start);
                ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
                long remaining = rangeLength;
                while (remaining > 0) {
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), remaining));
                    int read = channel.read(buffer);
                    if (read < 0) break;
                    outputStream.write(buffer.array(), 0, read);
                    remaining -= read;
                }
            }
        };
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers).body(body);
    }

    private void requireSecureMutation(String action, HttpServletRequest request) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        boolean https = request.isSecure() || "https".equalsIgnoreCase(forwardedProto);
        if (!https) {
            throw new FileBrowserException(HttpStatus.UPGRADE_REQUIRED, "HTTPS_REQUIRED",
                    "为保护远程存储凭据，只能通过 HTTPS 添加、连接或卸载存储");
        }
        if (!"nas-mount".equals(action)) {
            throw new FileBrowserException(HttpStatus.FORBIDDEN, "REMOTE_ACTION_HEADER_REQUIRED",
                    "远程存储操作缺少安全请求标记");
        }
    }

    private FileBrowserException invalidRange() {
        return new FileBrowserException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                "INVALID_RANGE", "Range 请求无效或超出远程文件范围");
    }
}
