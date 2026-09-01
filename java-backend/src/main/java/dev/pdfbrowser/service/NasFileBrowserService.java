package dev.pdfbrowser.service;

import dev.pdfbrowser.config.FileBrowserProperties;
import dev.pdfbrowser.exception.FileBrowserException;
import dev.pdfbrowser.model.DirectoryListing;
import dev.pdfbrowser.model.EntryType;
import dev.pdfbrowser.model.FileEntry;
import dev.pdfbrowser.model.MarkdownDocument;
import dev.pdfbrowser.model.SearchResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
public class NasFileBrowserService {
    private static final long DIRECTORY_CACHE_MILLIS = 10_000L;
    private static final long PATH_CACHE_MILLIS = 60_000L;
    private static final int MAX_DIRECTORY_CACHE_ENTRIES = 128;
    private static final int MAX_PATH_CACHE_ENTRIES = 512;
    private static final Comparator<FileEntry> ENTRY_ORDER = Comparator
            .comparing((FileEntry entry) -> entry.type() != EntryType.DIRECTORY)
            .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER);

    private final NasMountService mountService;
    private final FileBrowserProperties properties;
    private final Map<String, CachedMarkdown> markdownCache = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<String, CachedDirectory> directoryCache = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<String, CachedPath> pathCache = new LinkedHashMap<>(64, 0.75f, true);

    private record CachedMarkdown(String content, String etag, long expiresAtMillis) {}
    private record CachedDirectory(DirectoryListing listing, long expiresAtMillis) {}
    private record CachedPath(Path path, long expiresAtMillis) {}

    public NasFileBrowserService(NasMountService mountService, FileBrowserProperties properties) {
        this.mountService = mountService;
        this.properties = properties;
    }

    public DirectoryListing list(String mountId, String logicalPath) {
        Path root = mountService.requireMountedRoot(mountId);
        String cacheKey = mountId + ":" + normalizeLogical(logicalPath);
        long now = System.currentTimeMillis();
        synchronized (directoryCache) {
            CachedDirectory cached = directoryCache.get(cacheKey);
            if (cached != null && cached.expiresAtMillis() > now) return cached.listing();
            if (cached != null) directoryCache.remove(cacheKey);
        }

        Path directory = resolveExisting(root, logicalPath, mountId);
        BasicFileAttributes directoryAttributes = readAttributes(directory, "REMOTE_DIRECTORY_UNREADABLE");
        if (!directoryAttributes.isDirectory()) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "NOT_A_DIRECTORY", "指定路径不是目录");
        }
        List<FileEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.forEach(path -> {
                try {
                    entries.add(toEntry(root, path));
                } catch (FileBrowserException ignored) {
                    // Skip unreadable links and transient remote entries.
                }
            });
        } catch (IOException | UncheckedIOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "REMOTE_DIRECTORY_UNREADABLE",
                    "无法读取远程目录，请检查网络连接", exception);
        }
        entries.sort(ENTRY_ORDER);
        String path = toLogicalPath(root, directory);
        String name = path.isEmpty() ? "" : directory.getFileName().toString();
        DirectoryListing listing = new DirectoryListing(path, name, parentOf(path), List.copyOf(entries));
        synchronized (directoryCache) {
            directoryCache.put(cacheKey, new CachedDirectory(listing, now + DIRECTORY_CACHE_MILLIS));
            trim(directoryCache, MAX_DIRECTORY_CACHE_ENTRIES);
        }
        return listing;
    }

    public SearchResponse search(String mountId, String logicalPath, String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "EMPTY_QUERY", "搜索关键词不能为空");
        }
        Path root = mountService.requireMountedRoot(mountId);
        Path start = resolveExisting(root, logicalPath, mountId);
        if (!readAttributes(start, "REMOTE_SEARCH_FAILED").isDirectory()) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "NOT_A_DIRECTORY", "搜索起点不是目录");
        }
        int limit = Math.max(1, properties.getSearchMaxResults());
        List<FileEntry> matches;
        try (Stream<Path> stream = Files.walk(start, Math.max(1, properties.getSearchMaxDepth()))) {
            matches = stream.skip(1)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                    .map(path -> {
                        try {
                            return toEntry(root, path);
                        } catch (FileBrowserException ignored) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted(ENTRY_ORDER)
                    .limit((long) limit + 1)
                    .toList();
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "REMOTE_SEARCH_FAILED",
                    "远程搜索失败，请缩小搜索目录或检查网络", exception);
        }
        boolean truncated = matches.size() > limit;
        List<FileEntry> result = truncated ? matches.subList(0, limit) : matches;
        return new SearchResponse(query.trim(), toLogicalPath(root, start), List.copyOf(result), truncated);
    }

    public Path requireFile(String mountId, String logicalPath) {
        Path root = mountService.requireMountedRoot(mountId);
        Path file = resolveExisting(root, logicalPath, mountId);
        if (!readAttributes(file, "REMOTE_FILE_UNREADABLE").isRegularFile()) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "NOT_A_FILE", "指定路径不是普通文件");
        }
        return file;
    }

    public MarkdownDocument readMarkdown(String mountId, String logicalPath) {
        Path root = mountService.requireMountedRoot(mountId);
        Path file = resolveExisting(root, logicalPath, mountId);
        BasicFileAttributes attributes = readAttributes(file, "REMOTE_MARKDOWN_UNREADABLE");
        if (!attributes.isRegularFile() || classifyName(file.getFileName().toString()) != EntryType.MARKDOWN) {
            throw new FileBrowserException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "NOT_MARKDOWN",
                    "仅支持读取 .md 或 .markdown 文件");
        }
        String logical = toLogicalPath(root, file);
        String cacheKey = mountId + ":" + logical;
        long now = System.currentTimeMillis();
        synchronized (markdownCache) {
            CachedMarkdown cached = markdownCache.get(cacheKey);
            if (cached != null && cached.expiresAtMillis() > now) {
                return new MarkdownDocument(cached.content(), cached.etag(), true);
            }
            if (cached != null) markdownCache.remove(cacheKey);
        }
        try {
            long size = attributes.size();
            if (size > properties.getMaxMarkdownBytes()) {
                throw new FileBrowserException(HttpStatus.PAYLOAD_TOO_LARGE, "MARKDOWN_TOO_LARGE",
                        "Markdown 文件超过预览大小限制");
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String etag = Long.toHexString(size) + "-" + Long.toHexString(attributes.lastModifiedTime().toMillis());
            int cacheSeconds = Math.max(0, properties.getMarkdownCacheSeconds());
            int cacheEntries = Math.max(0, properties.getMarkdownCacheEntries());
            if (cacheSeconds > 0 && cacheEntries > 0) {
                synchronized (markdownCache) {
                    markdownCache.put(cacheKey, new CachedMarkdown(content, etag, now + cacheSeconds * 1000L));
                    trim(markdownCache, cacheEntries);
                }
            }
            return new MarkdownDocument(content, etag, false);
        } catch (FileBrowserException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "REMOTE_MARKDOWN_UNREADABLE",
                    "无法读取远程存储上的 Markdown 文件", exception);
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

    private FileEntry toEntry(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw forbidden();
        BasicFileAttributes attributes = readAttributes(normalized, "REMOTE_ENTRY_UNREADABLE");
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "REMOTE_ENTRY_UNSUPPORTED",
                    "不支持该远程条目类型");
        }
        EntryType type = attributes.isDirectory()
                ? EntryType.DIRECTORY : classifyName(normalized.getFileName().toString());
        Long size = type == EntryType.DIRECTORY ? null : attributes.size();
        return new FileEntry(
                toLogicalPath(root, normalized), normalized.getFileName().toString(), type, size,
                attributes.lastModifiedTime().toMillis(), type == EntryType.DIRECTORY ? null : mediaType(normalized),
                type == EntryType.PDF || type == EntryType.MARKDOWN);
    }

    private BasicFileAttributes readAttributes(Path path, String code) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, code,
                    "无法读取远程文件信息", exception);
        }
    }

    private Path resolveExisting(Path root, String logicalPath, String mountId) {
        String value = normalizeLogical(logicalPath);
        rejectUnsafeSyntax(value);
        String cacheKey = mountId + ":" + value;
        long now = System.currentTimeMillis();
        synchronized (pathCache) {
            CachedPath cached = pathCache.get(cacheKey);
            if (cached != null && cached.expiresAtMillis() > now) return cached.path();
            if (cached != null) pathCache.remove(cacheKey);
        }
        try {
            Path relative = value.isEmpty() ? Path.of("") : Path.of(value);
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) throw forbidden();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(root)) throw forbidden();
            synchronized (pathCache) {
                pathCache.put(cacheKey, new CachedPath(realCandidate, now + PATH_CACHE_MILLIS));
                trim(pathCache, MAX_PATH_CACHE_ENTRIES);
            }
            return realCandidate;
        } catch (InvalidPathException exception) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "路径格式无效", exception);
        } catch (java.nio.file.NoSuchFileException exception) {
            throw new FileBrowserException(HttpStatus.NOT_FOUND, "NOT_FOUND", "文件或目录不存在");
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "REMOTE_PATH_UNREADABLE",
                    "无法读取远程路径，请检查网络连接", exception);
        }
    }

    private String normalizeLogical(String logicalPath) {
        return logicalPath == null ? "" : logicalPath.trim().replace('\\', '/').replaceAll("/+$", "");
    }

    private String toLogicalPath(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw forbidden();
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    private EntryType classifyName(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return EntryType.PDF;
        if (name.endsWith(".md") || name.endsWith(".markdown")) return EntryType.MARKDOWN;
        return EntryType.OTHER;
    }

    private void rejectUnsafeSyntax(String value) {
        if (value.indexOf('\0') >= 0 || value.startsWith("/") || value.matches("^[A-Za-z]:.*")) throw forbidden();
        for (String segment : value.split("/")) {
            if (segment.equals("..")) throw forbidden();
        }
    }

    private FileBrowserException forbidden() {
        return new FileBrowserException(HttpStatus.FORBIDDEN, "PATH_OUTSIDE_REMOTE",
                "路径不在该远程存储目录内");
    }

    private String parentOf(String path) {
        if (path.isEmpty()) return null;
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private <K, V> void trim(Map<K, V> cache, int maximumSize) {
        while (cache.size() > maximumSize) {
            Iterator<K> iterator = cache.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }
}
