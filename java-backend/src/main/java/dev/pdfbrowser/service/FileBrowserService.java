package dev.pdfbrowser.service;

import dev.pdfbrowser.config.FileBrowserProperties;
import dev.pdfbrowser.exception.FileBrowserException;
import dev.pdfbrowser.model.DirectoryListing;
import dev.pdfbrowser.model.EntryType;
import dev.pdfbrowser.model.FileEntry;
import dev.pdfbrowser.model.MarkdownDocument;
import dev.pdfbrowser.model.SearchResponse;
import dev.pdfbrowser.model.StorageCapabilities;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Service
public class FileBrowserService {
    private static final long DIRECTORY_CACHE_MILLIS = 10_000L;
    private static final int MAX_DIRECTORY_CACHE_ENTRIES = 128;
    private static final Comparator<FileEntry> ENTRY_ORDER = Comparator
            .comparing((FileEntry entry) -> entry.type() != EntryType.DIRECTORY)
            .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER);

    private final SafePathResolver pathResolver;
    private final FileBrowserProperties properties;
    private final Map<String, CachedMarkdown> markdownCache = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<String, CachedDirectory> directoryCache = new LinkedHashMap<>(32, 0.75f, true);

    private record CachedMarkdown(String content, String etag, long expiresAtMillis) {}
    private record CachedDirectory(DirectoryListing listing, long expiresAtMillis) {}

    public FileBrowserService(SafePathResolver pathResolver, FileBrowserProperties properties) {
        this.pathResolver = pathResolver;
        this.properties = properties;
    }

    public DirectoryListing list(String logicalPath) {
        Path directory = pathResolver.resolveExisting(logicalPath);
        String cacheKey = pathResolver.toLogicalPath(directory);
        long now = System.currentTimeMillis();
        synchronized (directoryCache) {
            CachedDirectory cached = directoryCache.get(cacheKey);
            if (cached != null && cached.expiresAtMillis() > now) return cached.listing();
            if (cached != null) directoryCache.remove(cacheKey);
        }

        BasicFileAttributes directoryAttributes = readAttributes(directory, "DIRECTORY_UNREADABLE");
        if (!directoryAttributes.isDirectory()) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "NOT_A_DIRECTORY", "指定路径不是目录");
        }
        List<FileEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.forEach(path -> {
                try {
                    entries.add(toEntry(path));
                } catch (FileBrowserException ignored) {
                    // Skip unreadable, unsupported, or escaping links.
                }
            });
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "DIRECTORY_UNREADABLE",
                    "无法读取目录", exception);
        }
        entries.sort(ENTRY_ORDER);
        String path = pathResolver.toLogicalPath(directory);
        DirectoryListing listing = new DirectoryListing(
                path, directory.getFileName().toString(), parentOf(path), List.copyOf(entries));
        synchronized (directoryCache) {
            directoryCache.put(cacheKey, new CachedDirectory(listing, now + DIRECTORY_CACHE_MILLIS));
            trim(directoryCache, MAX_DIRECTORY_CACHE_ENTRIES);
        }
        return listing;
    }

    public SearchResponse search(String logicalPath, String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "EMPTY_QUERY", "搜索关键词不能为空");
        }
        Path start = pathResolver.resolveExisting(logicalPath);
        if (!readAttributes(start, "SEARCH_FAILED").isDirectory()) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "NOT_A_DIRECTORY", "搜索起点不是目录");
        }
        int limit = Math.max(1, properties.getSearchMaxResults());
        List<FileEntry> matches;
        try (Stream<Path> stream = Files.walk(start, Math.max(1, properties.getSearchMaxDepth()))) {
            matches = stream.skip(1)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                    .map(path -> {
                        try {
                            return toEntry(path);
                        } catch (FileBrowserException ignored) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted(ENTRY_ORDER)
                    .limit((long) limit + 1)
                    .toList();
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "SEARCH_FAILED",
                    "搜索目录失败", exception);
        }
        boolean truncated = matches.size() > limit;
        List<FileEntry> result = truncated ? matches.subList(0, limit) : matches;
        return new SearchResponse(query.trim(), pathResolver.toLogicalPath(start), List.copyOf(result), truncated);
    }

    public Path requireFile(String logicalPath) {
        Path file = pathResolver.resolveExisting(logicalPath);
        if (!readAttributes(file, "FILE_UNREADABLE").isRegularFile()) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "NOT_A_FILE", "指定路径不是普通文件");
        }
        return file;
    }

    public StorageCapabilities capabilities() {
        boolean writable = !properties.isReadOnly() && Files.isWritable(properties.getRoot());
        return new StorageCapabilities(writable, Math.max(0, properties.getMaxUploadBytes()));
    }

    public FileEntry upload(String logicalDirectory, String originalFilename, long declaredSize, InputStream input) {
        if (!capabilities().writable()) {
            throw new FileBrowserException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_READ_ONLY",
                    "当前 Google Drive 只有只读授权，暂时不能上传");
        }
        long maxBytes = Math.max(0, properties.getMaxUploadBytes());
        if (declaredSize < 0 || declaredSize > maxBytes) {
            throw new FileBrowserException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE",
                    "上传文件超过大小限制");
        }
        String filename = originalFilename == null ? "" : originalFilename.trim();
        if (filename.isEmpty() || filename.equals(".") || filename.equals("..")
                || filename.contains("/") || filename.contains("\\")) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "文件名无效");
        }
        Path directory = pathResolver.resolveExisting(logicalDirectory);
        if (!readAttributes(directory, "DIRECTORY_UNREADABLE").isDirectory()) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "NOT_A_DIRECTORY", "上传目标不是目录");
        }
        Path target = directory.resolve(filename).normalize();
        if (!target.getParent().equals(directory) || Files.exists(target)) {
            throw new FileBrowserException(HttpStatus.CONFLICT, "FILE_EXISTS", "同名文件已经存在");
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, ".pdfbrowser-upload-", ".part");
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(temporary) > maxBytes) {
                throw new FileBrowserException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE",
                        "上传文件超过大小限制");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            }
            temporary = null;
            clearDirectoryCache();
            pathResolver.invalidate(pathResolver.toLogicalPath(target));
            return toEntry(target);
        } catch (FileBrowserException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "UPLOAD_FAILED",
                    "文件上传失败", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            }
        }
    }

    public MarkdownDocument readMarkdown(String logicalPath) {
        Path file = requireFile(logicalPath);
        if (classifyName(file.getFileName().toString()) != EntryType.MARKDOWN) {
            throw new FileBrowserException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "NOT_MARKDOWN",
                    "仅支持读取 .md 或 .markdown 文件");
        }
        String cacheKey = pathResolver.toLogicalPath(file);
        long now = System.currentTimeMillis();
        synchronized (markdownCache) {
            CachedMarkdown cached = markdownCache.get(cacheKey);
            if (cached != null && cached.expiresAtMillis() > now) {
                return new MarkdownDocument(cached.content(), cached.etag(), true);
            }
            if (cached != null) markdownCache.remove(cacheKey);
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            long size = attributes.size();
            if (size > properties.getMaxMarkdownBytes()) {
                throw new FileBrowserException(HttpStatus.PAYLOAD_TOO_LARGE, "MARKDOWN_TOO_LARGE",
                        "Markdown 文件超过预览大小限制");
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String etag = Long.toHexString(size) + "-"
                    + Long.toHexString(attributes.lastModifiedTime().toMillis());
            int cacheSeconds = Math.max(0, properties.getMarkdownCacheSeconds());
            int cacheEntries = Math.max(0, properties.getMarkdownCacheEntries());
            if (cacheSeconds > 0 && cacheEntries > 0) {
                synchronized (markdownCache) {
                    markdownCache.put(cacheKey, new CachedMarkdown(
                            content, etag, now + cacheSeconds * 1000L));
                    trim(markdownCache, cacheEntries);
                }
            }
            return new MarkdownDocument(content, etag, false);
        } catch (FileBrowserException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "MARKDOWN_UNREADABLE",
                    "无法读取 Markdown 文件", exception);
        }
    }

    public EntryType classify(Path path) {
        if (Files.isDirectory(path)) return EntryType.DIRECTORY;
        return classifyName(path.getFileName().toString());
    }

    public String mediaType(Path path) {
        EntryType type = classifyName(path.getFileName().toString());
        if (type == EntryType.PDF) return "application/pdf";
        if (type == EntryType.MARKDOWN) return "text/markdown;charset=UTF-8";
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".txt") || name.endsWith(".log")) return "text/plain;charset=UTF-8";
        return "application/octet-stream";
    }

    private FileEntry toEntry(Path path) {
        BasicFileAttributes attributes = readAttributes(path, "ENTRY_UNREADABLE");
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "ENTRY_UNSUPPORTED",
                    "不支持该文件条目类型");
        }
        EntryType type = attributes.isDirectory()
                ? EntryType.DIRECTORY : classifyName(path.getFileName().toString());
        Long size = type == EntryType.DIRECTORY ? null : attributes.size();
        return new FileEntry(
                pathResolver.toLogicalPath(path), path.getFileName().toString(), type, size,
                attributes.lastModifiedTime().toMillis(), type == EntryType.DIRECTORY ? null : mediaType(path),
                type == EntryType.PDF || type == EntryType.MARKDOWN);
    }

    private BasicFileAttributes readAttributes(Path path, String code) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, code,
                    "无法读取文件信息", exception);
        }
    }

    private EntryType classifyName(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return EntryType.PDF;
        if (name.endsWith(".md") || name.endsWith(".markdown")) return EntryType.MARKDOWN;
        return EntryType.OTHER;
    }

    private void clearDirectoryCache() {
        synchronized (directoryCache) {
            directoryCache.clear();
        }
    }

    private <K, V> void trim(Map<K, V> cache, int maximumSize) {
        while (cache.size() > maximumSize) {
            Iterator<K> iterator = cache.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private String parentOf(String path) {
        if (path.isEmpty()) return null;
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }
}
