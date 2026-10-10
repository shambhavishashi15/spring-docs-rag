package io.github.shambhavishashi15.springdocsrag.ask;

// One cited docs section. id matches the [n] marker in the answer text.
public record Citation(int id, String page, String section, String url) {
}
