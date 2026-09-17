package dev.pdfbrowser;

import dev.pdfbrowser.config.AccountProperties;
import dev.pdfbrowser.config.FileBrowserProperties;
import dev.pdfbrowser.config.VerificationProperties;
import dev.pdfbrowser.config.NasMountProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    AccountProperties.class, 
    FileBrowserProperties.class, 
    NasMountProperties.class, 
    VerificationProperties.class
})
public class PdfBrowserApplication {
    public static void main(String[] args) {
        SpringApplication.run(PdfBrowserApplication.class, args);
    }
}
