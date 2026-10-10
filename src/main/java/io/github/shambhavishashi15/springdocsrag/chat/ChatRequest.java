package io.github.shambhavishashi15.springdocsrag.chat;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String message) {
}
