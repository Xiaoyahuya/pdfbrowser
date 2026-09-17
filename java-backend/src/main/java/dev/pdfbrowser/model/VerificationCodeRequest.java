package dev.pdfbrowser.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerificationCodeRequest(
        @NotBlank @Email @Size(max = 320) String email,

        @NotBlank @Pattern(regexp = "REGISTER") String purpose) {

}
