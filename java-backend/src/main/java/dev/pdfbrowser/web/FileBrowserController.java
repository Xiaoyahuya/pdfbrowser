package dev.pdfbrowser.web;

import dev.pdfbrowser.exception.FileBrowserException;
import dev.pdfbrowser.model.DirectoryListing;
import dev.pdfbrowser.model.EntryType;
import dev.pdfbrowser.model.MarkdownDocument;
import dev.pdfbrowser.model.SearchResponse;
import dev.pdfbrowser.model.StorageCapabilities;
import dev.pdfbrowser.service.FileBrowserService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
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
@RequestMapping("/api/files")
public class FileBrowserController {
    private final FileBrowserService fileBrowserService;

    public FileBrowserController(FileBrowserService fileBrowserService) {
        this.fileBrowserService = fileBrowserService;
    }

    @GetMapping
    public DirectoryListing list(@RequestParam(defaultValue = "") String path) {
        return fileBrowserService.list(path);
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam(defaultValue = "") String path, @RequestParam String q) {
        return fileBrowserService.search(path, q);
    }

    @GetMapping("/capabilities")
    public StorageCapabilities capabilities() {
        return fileBrowserService.capabilities();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public dev.pdfbrowser.model.FileEntry upload(
            @RequestParam String path,
            @RequestPart("file") MultipartFile file) throws java.io.IOException {
        return fileBrowserService.upload(path, file.getOriginalFilename(), file.getSize(), file.getInputStream());
    }

    @GetMapping(value = "/markdown", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> markdown(@RequestParam String path) {
        MarkdownDocument document = fileBrowserService.readMarkdown(path);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .eTag(document.etag())
                .header("X-PDFBrowser-Cache", document.cacheHit() ? "HIT" : "MISS")
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(document.content());
    }

    @GetMapping("/raw")
    public ResponseEntity<StreamingResponseBody> raw(
            @RequestParam String path,
            @RequestParam(defaultValue = "false") boolean download,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        Path file = fileBrowserService.requireFile(path);
        EntryType type = fileBrowserService.classify(file);
        String detectedMediaType = fileBrowserService.mediaType(file);
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
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "FILE_UNREADABLE", "无法读取文件", exception);
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
            throw invalidRange("Range 请求无效", exception);
        }
        if (ranges.size() != 1) {
            throw new FileBrowserException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                    "MULTIPLE_RANGES_UNSUPPORTED", "一次请求仅支持一个 Range 区间");
        }

        long start;
        long end;
        try {
            start = ranges.get(0).getRangeStart(fileSize);
            end = ranges.get(0).getRangeEnd(fileSize);
        } catch (Exception exception) {
            throw invalidRange("Range 请求超出文件范围", exception);
        }
        if (fileSize <= 0 || start < 0 || end < start || end >= fileSize) {
            throw invalidRange("Range 请求超出文件范围", null);
        }

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

    private FileBrowserException invalidRange(String message, Exception cause) {
        return cause == null
                ? new FileBrowserException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "INVALID_RANGE", message)
                : new FileBrowserException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "INVALID_RANGE", message, cause);
    }
}
