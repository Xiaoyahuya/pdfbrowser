package dev.pdfbrowser.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotBlank
    @Size(max = 320)
    String login,

    @NotBlank
    @Size(max = 128)
    String password
) {
    
}
