package dev.pdfbrowser.service;

import dev.pdfbrowser.config.NasMountProperties;
import dev.pdfbrowser.exception.FileBrowserException;
import dev.pdfbrowser.model.NasMountRequest;
import dev.pdfbrowser.model.NasMountView;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class NasMountService {
    public static final String SMB = "SMB";
    public static final String WEBDAV = "WEBDAV";
    public static final String GOOGLE_DRIVE = "GOOGLE_DRIVE";

    private static final Logger log = LoggerFactory.getLogger(NasMountService.class);
    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern GOOGLE_FOLDER_ID = Pattern.compile("^[A-Za-z0-9_-]*$");
    private static final Set<String> WEBDAV_VENDORS = Set.of(
            "other", "nextcloud", "owncloud", "sharepoint", "fastmail", "rclone");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final NasMountProperties properties;
    private final NasCredentialCipher credentialCipher;
    private final RclonePasswordObscurer passwordObscurer;
    private final SocksTcpRelayManager relayManager;
    private final ObjectMapper objectMapper;
    private final Map<String, StoredNasMount> mounts = new LinkedHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final Set<String> mounting = ConcurrentHashMap.newKeySet();
    private final Map<String, MountCheck> mountChecks = new ConcurrentHashMap<>();
    private Path runtimeDirectory;
    private Path mountsDirectory;
    private Path configsDirectory;
    private Path logsDirectory;
    private Path storeFile;

    public NasMountService(
            NasMountProperties properties,
            NasCredentialCipher credentialCipher,
            RclonePasswordObscurer passwordObscurer,
            SocksTcpRelayManager relayManager,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.credentialCipher = credentialCipher;
        this.passwordObscurer = passwordObscurer;
        this.relayManager = relayManager;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initialize() {
        if (!properties.isEnabled()) return;
        runtimeDirectory = properties.getRuntimeDirectory().toAbsolutePath().normalize();
        mountsDirectory = runtimeDirectory.resolve("mounts");
        configsDirectory = runtimeDirectory.resolve("configs");
        logsDirectory = runtimeDirectory.resolve("logs");
        storeFile = runtimeDirectory.resolve("mounts.json");
        try {
            createPrivateDirectory(runtimeDirectory);
            createPrivateDirectory(mountsDirectory);
            createPrivateDirectory(configsDirectory);
            createPrivateDirectory(logsDirectory);
            if (Files.exists(storeFile)) {
                List<StoredNasMount> stored = objectMapper.readValue(
                        Files.readString(storeFile, StandardCharsets.UTF_8), new TypeReference<>() {});
                for (StoredNasMount mount : stored) mounts.put(mount.id(), mount);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化远程存储配置目录", exception);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreMounts() {
        if (!properties.isEnabled()) return;
        List<StoredNasMount> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(mounts.values());
        }
        for (StoredNasMount mount : snapshot) {
            try {
                if (isMounted(mount.id())) unmount(mount.id());
                startMount(mount);
                errors.remove(mount.id());
            } catch (RuntimeException exception) {
                String message = safeMessage(exception);
                errors.put(mount.id(), message);
                log.warn("远程存储 {} 恢复失败: {}", mount.id(), message);
            }
        }
    }

    public synchronized List<NasMountView> list() {
        ensureEnabled();
        return mounts.values().stream()
                .sorted(Comparator.comparing(StoredNasMount::createdAt))
                .map(this::toView)
                .toList();
    }

    public synchronized NasMountView create(NasMountRequest request) {
        ensureEnabled();
        ValidatedRequest validated = validate(request);
        String id = UUID.randomUUID().toString();
        String encryptedClientSecret = validated.clientSecret().isBlank()
                ? "" : credentialCipher.encrypt(validated.clientSecret());
        StoredNasMount mount = new StoredNasMount(
                id, validated.name(), validated.provider(), validated.host(), validated.share(),
                validated.username(), validated.domain(), validated.useProxy(), validated.url(),
                validated.vendor(), validated.clientId(), validated.rootFolderId(),
                credentialCipher.encrypt(validated.secret()), encryptedClientSecret, Instant.now());
        mounts.put(id, mount);
        try {
            startMount(mount);
            persist();
            errors.remove(id);
            return toView(mount);
        } catch (RuntimeException exception) {
            mounts.remove(id);
            bestEffortUnmount(id);
            errors.remove(id);
            cleanupTransientFiles(id);
            throw exception;
        }
    }

    public synchronized NasMountView connect(String id) {
        ensureEnabled();
        StoredNasMount mount = requireMount(id);
        if (!isMounted(id)) startMount(mount);
        errors.remove(id);
        return toView(mount);
    }

    public synchronized void remove(String id) {
        ensureEnabled();
        StoredNasMount mount = requireMount(id);
        if (isMounted(mount.id())) unmount(mount.id());
        relayManager.stop(id);
        mounts.remove(id);
        errors.remove(id);
        mountChecks.remove(id);
        persist();
        cleanupTransientFiles(id);
    }

    public Path requireMountedRoot(String id) {
        ensureEnabled();
        synchronized (this) {
            requireMount(id);
        }
        if (!isMounted(id)) {
            throw new FileBrowserException(HttpStatus.SERVICE_UNAVAILABLE, "REMOTE_NOT_CONNECTED",
                    "该远程存储当前未连接，请在存储管理中重新连接");
        }
        return mountPath(id).toAbsolutePath().normalize();
    }

    private void startMount(StoredNasMount mount) {
        if (!mounting.add(mount.id())) {
            throw new FileBrowserException(HttpStatus.CONFLICT, "REMOTE_MOUNT_IN_PROGRESS", "远程存储正在连接");
        }
        Path target = mountPath(mount.id());
        Path config = configPath(mount.id());
        boolean mountedSuccessfully = false;
        try {
            if (isMounted(mount.id())) {
                mountedSuccessfully = true;
                return;
            }
            createPrivateDirectory(target);
            String provider = providerOf(mount);
            String secret = credentialCipher.decrypt(mount.encryptedPassword());
            String configSecret = GOOGLE_DRIVE.equals(provider) ? secret : passwordObscurer.obscure(secret);
            secret = null;
            String clientSecret = GOOGLE_DRIVE.equals(provider) && !empty(mount.encryptedClientSecret()).isBlank()
                    ? credentialCipher.decrypt(mount.encryptedClientSecret()) : "";

            String connectionHost = mount.host();
            int connectionPort = 445;
            boolean useHttpProxy = mount.useProxy() && !SMB.equals(provider);
            if (SMB.equals(provider) && mount.useProxy()) {
                connectionPort = relayManager.start(mount.id(), mount.host(), 445);
                connectionHost = "127.0.0.1";
            } else {
                relayManager.stop(mount.id());
            }
            writePrivateFile(config, rcloneConfig(
                    mount, configSecret, clientSecret, connectionHost, connectionPort));
            clientSecret = null;

            int timeout = Math.max(8, properties.getMountTimeoutSeconds());
            List<String> validationCommand = new ArrayList<>(List.of(
                    properties.getRcloneExecutable(), "lsf", remotePath(mount),
                    "--max-depth", "1", "--dirs-only",
                    "--config", config.toString(),
                    "--contimeout", "8s", "--timeout", "20s",
                    "--log-level", "ERROR", "--log-file", logPath(mount.id()).toString()));
            int validationExitCode = run(validationCommand, Duration.ofSeconds(timeout), useHttpProxy);
            if (validationExitCode != 0) {
                String detail = readLogTail(logPath(mount.id()));
                String message = detail.isBlank() ? "无法访问远程存储，请检查地址、凭据和网络" : detail;
                errors.put(mount.id(), message);
                throw new FileBrowserException(HttpStatus.BAD_GATEWAY, "REMOTE_CONNECTION_TEST_FAILED",
                        "远程存储连接验证失败：" + message);
            }

            List<String> command = new ArrayList<>(List.of(
                    properties.getRcloneExecutable(), "mount", remotePath(mount), target.toString(),
                    "--config", config.toString(), "--read-only"));
            if (GOOGLE_DRIVE.equals(provider)) {
                command.add("--drive-skip-gdocs");
                command.add("--vfs-refresh");
            }
            command.addAll(List.of(
                    "--vfs-cache-mode", "off",
                    "--vfs-read-chunk-size", "1M",
                    "--vfs-read-chunk-size-limit", "16M",
                    "--buffer-size", "0",
                    "--dir-cache-time", GOOGLE_DRIVE.equals(provider) ? "24h" : "5m",
                    "--attr-timeout", "1m",
                    "--poll-interval", GOOGLE_DRIVE.equals(provider) ? "1m" : "0",
                    "--contimeout", "8s", "--timeout", "1m",
                    "--daemon-timeout", "20s", "--daemon", "--daemon-wait", "5s",
                    "--umask", "077", "--log-level", "INFO",
                    "--log-file", logPath(mount.id()).toString()));
            int exitCode = run(command, Duration.ofSeconds(timeout + 10L), useHttpProxy);
            invalidateMounted(mount.id());
            if (exitCode != 0 || !isMounted(mount.id())) {
                String detail = readLogTail(logPath(mount.id()));
                String message = detail.isBlank() ? "挂载进程没有成功启动，请检查网络和凭据" : detail;
                errors.put(mount.id(), message);
                throw new FileBrowserException(HttpStatus.BAD_GATEWAY, "REMOTE_MOUNT_FAILED",
                        "远程存储连接失败：" + message);
            }
            mountedSuccessfully = true;
        } catch (FileBrowserException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.UNPROCESSABLE_ENTITY, "REMOTE_MOUNT_IO_ERROR",
                    "远程存储挂载准备失败", exception);
        } finally {
            mounting.remove(mount.id());
            if (!mountedSuccessfully) relayManager.stop(mount.id());
            try {
                Files.deleteIfExists(config);
            } catch (IOException ignored) {
                // The temporary file is mode 0600 and is removed on the next operation.
            }
        }
    }

    private void unmount(String id) {
        int exitCode = run(List.of(
                properties.getFusermountExecutable(), "-u", mountPath(id).toString()),
                Duration.ofSeconds(15), false);
        invalidateMounted(id);
        if (exitCode != 0 && isMounted(id)) {
            exitCode = run(List.of(
                    properties.getFusermountExecutable(), "-uz", mountPath(id).toString()),
                    Duration.ofSeconds(15), false);
            invalidateMounted(id);
        }
        if (exitCode != 0 && isMounted(id)) {
            throw new FileBrowserException(HttpStatus.CONFLICT, "REMOTE_UNMOUNT_FAILED",
                    "远程存储正在被使用，暂时无法卸载");
        }
        relayManager.stop(id);
    }

    private void bestEffortUnmount(String id) {
        try {
            if (mountsDirectory != null && isMounted(id)) unmount(id);
        } catch (RuntimeException ignored) {
            log.warn("远程存储 {} 回滚卸载失败", id);
        } finally {
            relayManager.stop(id);
            invalidateMounted(id);
        }
    }

    private int run(List<String> command, Duration timeout, boolean useHttpProxy) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (useHttpProxy) {
                String proxy = "http://" + properties.getSocksProxyHost() + ":" + properties.getSocksProxyPort();
                builder.environment().put("HTTP_PROXY", proxy);
                builder.environment().put("HTTPS_PROXY", proxy);
                builder.environment().put("http_proxy", proxy);
                builder.environment().put("https_proxy", proxy);
            }
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new FileBrowserException(HttpStatus.GATEWAY_TIMEOUT, "REMOTE_COMMAND_TIMEOUT",
                        "远程存储操作超时，请检查网络");
            }
            return process.exitValue();
        } catch (FileBrowserException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.INTERNAL_SERVER_ERROR, "REMOTE_COMMAND_FAILED",
                    "服务器无法执行远程存储挂载命令", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new FileBrowserException(HttpStatus.SERVICE_UNAVAILABLE, "REMOTE_COMMAND_INTERRUPTED",
                    "远程存储操作被中断", exception);
        }
    }

    private boolean isMounted(String id) {
        if (mountsDirectory == null) return false;
        long now = System.currentTimeMillis();
        MountCheck cached = mountChecks.get(id);
        if (cached != null && cached.expiresAtMillis() > now) return cached.mounted();
        boolean mounted = probeMounted(id);
        mountChecks.put(id, new MountCheck(mounted, now + (mounted ? 2000 : 500)));
        return mounted;
    }

    private boolean probeMounted(String id) {
        Path path = mountPath(id);
        if (!Files.exists(path)) return false;
        try {
            Process process = new ProcessBuilder(properties.getMountpointExecutable(), "-q", path.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void invalidateMounted(String id) {
        mountChecks.remove(id);
    }

    private NasMountView toView(StoredNasMount mount) {
        String status = mounting.contains(mount.id()) ? "MOUNTING"
                : isMounted(mount.id()) ? "CONNECTED"
                : errors.containsKey(mount.id()) ? "ERROR" : "DISCONNECTED";
        return new NasMountView(
                mount.id(), mount.name(), storageKey(mount), providerOf(mount),
                empty(mount.host()), empty(mount.share()), empty(mount.username()), empty(mount.domain()),
                empty(mount.url()), empty(mount.vendor()), empty(mount.clientId()), empty(mount.rootFolderId()),
                status, mount.createdAt(), errors.get(mount.id()), true, mount.useProxy());
    }

    private ValidatedRequest validate(NasMountRequest request) {
        String name = cleanRequired(request.name(), "显示名称");
        if (name.contains("/") || name.contains("\\") || name.contains(":")) {
            invalid("显示名称不能包含 /、\\ 或冒号");
        }
        String provider = normalizeProvider(request.provider());
        return switch (provider) {
            case SMB -> validateSmb(request, name);
            case WEBDAV -> validateWebDav(request, name);
            case GOOGLE_DRIVE -> validateGoogleDrive(request, name);
            default -> throw new IllegalStateException("unreachable");
        };
    }

    private ValidatedRequest validateSmb(NasMountRequest request, String name) {
        String host = validateSmbHost(cleanRequired(request.host(), "NAS IP"));
        String share = cleanRequired(request.share(), "SMB 共享名");
        String username = cleanRequired(request.username(), "账号");
        String password = requiredSecret(request.password(), "密码");
        String domain = cleanOptional(request.domain());
        if (share.equals(".") || share.equals("..") || share.contains("/")
                || share.contains("\\") || share.contains(":")) invalid("SMB 共享名格式无效");
        if (domain.contains("/") || domain.contains("\\")) invalid("域名格式无效");
        return new ValidatedRequest(name, SMB, host, share, username, domain,
                request.useProxy(), "", "", "", "", "", password);
    }

    private ValidatedRequest validateWebDav(NasMountRequest request, String name) {
        String url = validateWebDavUrl(cleanRequired(request.url(), "WebDAV 地址"));
        String username = cleanRequired(request.username(), "账号");
        String password = requiredSecret(request.password(), "密码");
        String vendor = cleanOptional(request.vendor()).toLowerCase(Locale.ROOT);
        if (vendor.isEmpty()) vendor = "other";
        if (!WEBDAV_VENDORS.contains(vendor)) invalid("不支持该 WebDAV 类型");
        return new ValidatedRequest(name, WEBDAV, "", "", username, "",
                request.useProxy(), url, vendor, "", "", "", password);
    }

    private ValidatedRequest validateGoogleDrive(NasMountRequest request, String name) {
        String token = requiredSecret(request.oauthToken(), "Google OAuth Token");
        String compactToken;
        try {
            Map<String, Object> parsed = objectMapper.readValue(token, new TypeReference<>() {});
            Object accessToken = parsed.get("access_token");
            if (!(accessToken instanceof String value) || value.isBlank()) {
                invalid("Google OAuth Token 缺少 access_token");
            }
            compactToken = objectMapper.writeValueAsString(parsed);
        } catch (FileBrowserException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "INVALID_REMOTE_CONFIG",
                    "Google OAuth Token 必须是 rclone authorize drive 输出的 JSON", exception);
        }
        String rootFolderId = cleanOptional(request.rootFolderId());
        if (!GOOGLE_FOLDER_ID.matcher(rootFolderId).matches()) invalid("Google 根目录 ID 格式无效");
        String clientId = cleanOptional(request.clientId());
        String clientSecret = cleanOptional(request.clientSecret());
        if (clientId.isBlank() != clientSecret.isBlank()) {
            invalid("Google Client ID 与 Client Secret 必须同时填写或同时留空");
        }
        return new ValidatedRequest(name, GOOGLE_DRIVE, "", "", "", "",
                request.useProxy(), "", "", clientId, clientSecret, rootFolderId, compactToken);
    }

    private String validateSmbHost(String value) {
        String host = value;
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if ((!IPV4.matcher(host).matches() && !host.contains(":")) || host.contains("%")) {
            invalid("请输入 NAS 的 IPv4 或 IPv6 地址，不接受主机名");
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException exception) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "INVALID_REMOTE_HOST", "NAS IP 地址格式无效");
        }
        rejectUnsafeAddress(address);
        if (!isPrivateAddress(address) && !properties.isAllowPublicHosts()) {
            invalid("出于安全考虑，NAS 默认只允许内网或私有 IP");
        }
        return address.getHostAddress();
    }

    private String validateWebDavUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "INVALID_REMOTE_URL", "WebDAV 地址格式无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            invalid("WebDAV 地址必须是 http(s) URL，且不能把账号密码写在 URL 中");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            boolean allPrivate = true;
            for (InetAddress address : addresses) {
                rejectUnsafeAddress(address);
                allPrivate &= isPrivateAddress(address);
            }
            if (scheme.equals("http") && !allPrivate) invalid("公网 WebDAV 必须使用 HTTPS");
        } catch (UnknownHostException exception) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "INVALID_REMOTE_HOST", "WebDAV 主机无法解析");
        }
        return uri.toASCIIString().replaceAll("/+$", "");
    }

    private void rejectUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            invalid("该地址不能用作远程存储地址");
        }
    }

    private boolean isPrivateAddress(InetAddress address) {
        return address.isSiteLocalAddress() || isCarrierGradeNat(address) || isUniqueLocalIpv6(address);
    }

    private boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100 && ((bytes[1] & 0xc0) == 0x40);
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc);
    }

    private String normalizeProvider(String value) {
        String provider = value == null || value.isBlank() ? SMB : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of(SMB, WEBDAV, GOOGLE_DRIVE).contains(provider)) invalid("不支持该存储类型");
        return provider;
    }

    private String providerOf(StoredNasMount mount) {
        return mount.provider() == null || mount.provider().isBlank()
                ? SMB : mount.provider().toUpperCase(Locale.ROOT);
    }

    private String cleanRequired(String value, String field) {
        String result = cleanOptional(value);
        if (result.isEmpty()) invalid(field + "不能为空");
        return result;
    }

    private String cleanOptional(String value) {
        String result = value == null ? "" : value.trim();
        if (CONTROL.matcher(result).find()) invalid("配置中包含不可用的控制字符");
        return result;
    }

    private String requiredSecret(String value, String field) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) invalid(field + "不能为空");
        return result;
    }

    private void invalid(String message) {
        throw new FileBrowserException(HttpStatus.BAD_REQUEST, "INVALID_REMOTE_CONFIG", message);
    }

    private StoredNasMount requireMount(String id) {
        if (id == null || !id.matches("^[0-9a-f-]{36}$")) {
            throw new FileBrowserException(HttpStatus.NOT_FOUND, "REMOTE_MOUNT_NOT_FOUND", "远程存储不存在");
        }
        StoredNasMount mount = mounts.get(id);
        if (mount == null) {
            throw new FileBrowserException(HttpStatus.NOT_FOUND, "REMOTE_MOUNT_NOT_FOUND", "远程存储不存在");
        }
        return mount;
    }

    private void persist() {
        try {
            Path temporary = runtimeDirectory.resolve("mounts.json.tmp");
            writePrivateFile(temporary, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new ArrayList<>(mounts.values())));
            try {
                Files.move(temporary, storeFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
            setPermissions(storeFile, FILE_PERMISSIONS);
        } catch (IOException exception) {
            throw new FileBrowserException(HttpStatus.INTERNAL_SERVER_ERROR, "REMOTE_CONFIG_SAVE_FAILED",
                    "远程存储配置保存失败", exception);
        }
    }

    private String rcloneConfig(
            StoredNasMount mount,
            String secret,
            String clientSecret,
            String connectionHost,
            int connectionPort) {
        return switch (providerOf(mount)) {
            case SMB -> "[remote]\n"
                    + "type = smb\n"
                    + "host = " + connectionHost + "\n"
                    + "user = " + mount.username() + "\n"
                    + "pass = " + secret + "\n"
                    + "domain = " + empty(mount.domain()) + "\n"
                    + "port = " + connectionPort + "\n"
                    // The proxy route drops idle SMB sessions at about one minute. Retire pooled
                    // connections earlier so the next directory click creates a clean session.
                    + "idle_timeout = 15s\n";
            case WEBDAV -> "[remote]\n"
                    + "type = webdav\n"
                    + "url = " + mount.url() + "\n"
                    + "vendor = " + mount.vendor() + "\n"
                    + "user = " + mount.username() + "\n"
                    + "pass = " + secret + "\n";
            case GOOGLE_DRIVE -> "[remote]\n"
                    + "type = drive\n"
                    + "scope = drive.readonly\n"
                    + (empty(mount.clientId()).isBlank() ? "" : "client_id = " + mount.clientId() + "\n")
                    + (clientSecret.isBlank() ? "" : "client_secret = " + clientSecret + "\n")
                    + "token = " + secret + "\n"
                    + (empty(mount.rootFolderId()).isBlank()
                    ? "" : "root_folder_id = " + mount.rootFolderId() + "\n");
            default -> throw new IllegalStateException("Unsupported provider");
        };
    }

    private String remotePath(StoredNasMount mount) {
        return SMB.equals(providerOf(mount)) ? "remote:" + mount.share() : "remote:";
    }

    private String readLogTail(Path path) {
        if (!Files.exists(path)) return "";
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(8192, size);
            channel.position(size - length);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { /* read tail */ }
            String content = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            String[] lines = content.split("\\R");
            String last = lines.length == 0 ? "" : lines[lines.length - 1].trim();
            return last.replaceAll("(?i)(pass(?:word)?|token|secret)\\s*[=:]\\s*\\S+", "$1=<redacted>");
        } catch (IOException exception) {
            return "";
        }
    }

    private String storageKey(StoredNasMount mount) {
        String prefix = switch (providerOf(mount)) {
            case SMB -> "NAS";
            case WEBDAV -> "WebDAV";
            case GOOGLE_DRIVE -> "GoogleDrive";
            default -> "Remote";
        };
        return prefix + "-" + mount.name() + "-" + mount.id().substring(0, 8);
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }

    private void cleanupTransientFiles(String id) {
        try {
            Files.deleteIfExists(mountPath(id));
            Files.deleteIfExists(configPath(id));
            Files.deleteIfExists(logPath(id));
        } catch (IOException exception) {
            log.warn("远程存储 {} 的临时文件清理不完整", id);
        }
    }

    private Path mountPath(String id) { return mountsDirectory.resolve(id).normalize(); }
    private Path configPath(String id) { return configsDirectory.resolve(id + ".conf").normalize(); }
    private Path logPath(String id) { return logsDirectory.resolve(id + ".log").normalize(); }

    private void createPrivateDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        setPermissions(path, DIRECTORY_PERMISSIONS);
    }

    private void writePrivateFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        setPermissions(path, FILE_PERMISSIONS);
    }

    private void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Runtime target is Linux; tests may run elsewhere.
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "未知挂载错误" : message;
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new FileBrowserException(HttpStatus.SERVICE_UNAVAILABLE, "REMOTE_MOUNTS_DISABLED",
                    "远程存储挂载功能未启用");
        }
    }

    private record MountCheck(boolean mounted, long expiresAtMillis) {}

    private record StoredNasMount(
            String id,
            String name,
            String provider,
            String host,
            String share,
            String username,
            String domain,
            boolean useProxy,
            String url,
            String vendor,
            String clientId,
            String rootFolderId,
            String encryptedPassword,
            String encryptedClientSecret,
            Instant createdAt
    ) {}

    private record ValidatedRequest(
            String name,
            String provider,
            String host,
            String share,
            String username,
            String domain,
            boolean useProxy,
            String url,
            String vendor,
            String clientId,
            String clientSecret,
            String rootFolderId,
            String secret
    ) {}
}
