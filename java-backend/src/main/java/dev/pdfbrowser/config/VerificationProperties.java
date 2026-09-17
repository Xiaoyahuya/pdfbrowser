package dev.pdfbrowser.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.auth.verification")
public class VerificationProperties {

    @Min(1)
    private int codeValiditySeconds = 600;

    @Min(1)
    private int resendIntervalSeconds = 60;

    @Min(1)
    private int maxAttempts = 5;

    @Valid
    private RateLimit rateLimit = new RateLimit();

    public int getCodeValiditySeconds() {
        return codeValiditySeconds;
    }

    public void setCodeValiditySeconds(int codeValiditySeconds) {
        this.codeValiditySeconds = codeValiditySeconds;
    }

    public int getResendIntervalSeconds() {
        return resendIntervalSeconds;
    }

    public void setResendIntervalSeconds(int resendIntervalSeconds) {
        this.resendIntervalSeconds = resendIntervalSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public static class RateLimit {

        @Min(1)
        private int windowSeconds = 3600;

        @Min(1)
        private int maxPerEmail = 5;

        @Min(1)
        private int maxPerIp = 20;

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getMaxPerEmail() {
            return maxPerEmail;
        }

        public void setMaxPerEmail(int maxPerEmail) {
            this.maxPerEmail = maxPerEmail;
        }

        public int getMaxPerIp() {
            return maxPerIp;
        }

        public void setMaxPerIp(int maxPerIp) {
            this.maxPerIp = maxPerIp;
        }
    }
}