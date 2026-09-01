package dev.pdfbrowser.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "app.nas")
public class NasMountProperties {
    private boolean enabled = true;
    private Path runtimeDirectory = Path.of(System.getProperty("user.home"), "pdfbrowser-nas");
    private String credentialKey = "";
    private String rcloneExecutable = "/usr/bin/rclone";
    private String fusermountExecutable = "/usr/bin/fusermount3";
    private String mountpointExecutable = "/usr/bin/mountpoint";
    private boolean allowPublicHosts = false;
    private int mountTimeoutSeconds = 20;
    private String socksProxyHost = "127.0.0.1";
    private int socksProxyPort = 7897;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path getRuntimeDirectory() { return runtimeDirectory; }
    public void setRuntimeDirectory(Path runtimeDirectory) { this.runtimeDirectory = runtimeDirectory; }
    public String getCredentialKey() { return credentialKey; }
    public void setCredentialKey(String credentialKey) { this.credentialKey = credentialKey; }
    public String getRcloneExecutable() { return rcloneExecutable; }
    public void setRcloneExecutable(String rcloneExecutable) { this.rcloneExecutable = rcloneExecutable; }
    public String getFusermountExecutable() { return fusermountExecutable; }
    public void setFusermountExecutable(String fusermountExecutable) { this.fusermountExecutable = fusermountExecutable; }
    public String getMountpointExecutable() { return mountpointExecutable; }
    public void setMountpointExecutable(String mountpointExecutable) { this.mountpointExecutable = mountpointExecutable; }
    public boolean isAllowPublicHosts() { return allowPublicHosts; }
    public void setAllowPublicHosts(boolean allowPublicHosts) { this.allowPublicHosts = allowPublicHosts; }
    public int getMountTimeoutSeconds() { return mountTimeoutSeconds; }
    public void setMountTimeoutSeconds(int mountTimeoutSeconds) { this.mountTimeoutSeconds = mountTimeoutSeconds; }
    public String getSocksProxyHost() { return socksProxyHost; }
    public void setSocksProxyHost(String socksProxyHost) { this.socksProxyHost = socksProxyHost; }
    public int getSocksProxyPort() { return socksProxyPort; }
    public void setSocksProxyPort(int socksProxyPort) { this.socksProxyPort = socksProxyPort; }
}
