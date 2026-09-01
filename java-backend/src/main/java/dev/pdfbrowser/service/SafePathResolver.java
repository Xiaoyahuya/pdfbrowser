package dev.pdfbrowser.service;

import dev.pdfbrowser.config.FileBrowserProperties;
import dev.pdfbrowser.exception.FileBrowserException;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SafePathResolver {
    private static final long CACHE_MILLIS = 60_000L;
    private static final int MAX_CACHE_ENTRIES = 1024;
    private final Path configuredRoot;
    private final Map<String, CachedPath> pathCache = new LinkedHashMap<>(64, 0.75f, true);
    private Path realRoot;

    private record CachedPath(Path path, long expiresAtMillis) {}

    public SafePathResolver(FileBrowserProperties properties) {
        this.configuredRoot = properties.getRoot().toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialize() {
        try {
            Files.createDirectories(configuredRoot);
            realRoot = configuredRoot.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化文件根目录: " + configuredRoot, exception);
        }
    }

    public Path resolveExisting(String logicalPath) {
        String value = normalize(logicalPath);
        rejectUnsafeSyntax(value);
        long now = System.currentTimeMillis();
        synchronized (pathCache) {
            CachedPath cached = pathCache.get(value);
            if (cached != null && cached.expiresAtMillis() > now) return cached.path();
            if (cached != null) pathCache.remove(value);
        }
        try {
            Path relative = value.isEmpty() ? Path.of("") : Path.of(value);
            Path candidate = realRoot.resolve(relative).normalize();
            if (!candidate.startsWith(realRoot)) throw forbidden();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) throw forbidden();
            synchronized (pathCache) {
                pathCache.put(value, new CachedPath(realCandidate, now + CACHE_MILLIS));
                while (pathCache.size() > MAX_CACHE_ENTRIES) {
                    Iterator<String> iterator = pathCache.keySet().iterator();
                    if (!iterator.hasNext()) break;
                    iterator.next();
                    iterator.remove();
                }
            }
            return realCandidate;
        } catch (InvalidPathException exception) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "路径格式无效", exception);
        } catch (java.nio.file.NoSuchFileException exception) {
            throw new FileBrowserException(HttpStatus.NOT_FOUND, "NOT_FOUND", "文件或目录不存在");
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "PATH_UNREADABLE",
                    "无法读取指定路径", exception);
        }
    }

    public String toLogicalPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(realRoot)) throw forbidden();
        return realRoot.relativize(normalized).toString().replace('\\', '/');
    }

    public void invalidate(String logicalPath) {
        String value = normalize(logicalPath);
        synchronized (pathCache) {
            pathCache.remove(value);
        }
    }

    private String normalize(String logicalPath) {
        return logicalPath == null ? "" : logicalPath.trim().replace('\\', '/').replaceAll("/+$", "");
    }

    private void rejectUnsafeSyntax(String value) {
        if (value.indexOf('\0') >= 0 || value.startsWith("/") || value.matches("^[A-Za-z]:.*")) throw forbidden();
        for (String segment : value.split("/")) {
            if (segment.equals("..")) throw forbidden();
        }
    }

    private FileBrowserException forbidden() {
        return new FileBrowserException(HttpStatus.FORBIDDEN, "PATH_OUTSIDE_ROOT",
                "路径不在允许的文件根目录内");
    }
}
