package dev.pdfbrowser.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "app.account")
public class AccountProperties {
    private Path htpasswdFile = Path.of("/etc/nginx/pdfbrowser-auth/htpasswd");
    private int bcryptStrength = 12;

    public Path getHtpasswdFile() { return htpasswdFile; }
    public void setHtpasswdFile(Path htpasswdFile) { this.htpasswdFile = htpasswdFile; }
    public int getBcryptStrength() { return bcryptStrength; }
    public void setBcryptStrength(int bcryptStrength) { this.bcryptStrength = bcryptStrength; }
}
