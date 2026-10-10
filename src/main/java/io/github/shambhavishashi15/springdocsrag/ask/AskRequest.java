package io.github.shambhavishashi15.springdocsrag.ask;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The size cap keeps a single request from sending an arbitrarily long prompt to Gemini.
public record AskRequest(@NotBlank @Size(max = 1000) String question) {
}
