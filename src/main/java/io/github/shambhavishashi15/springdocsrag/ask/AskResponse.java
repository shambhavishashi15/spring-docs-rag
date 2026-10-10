package io.github.shambhavishashi15.springdocsrag.ask;

import java.util.List;

// answered is false when the docs don't contain the answer; citations then is empty.
public record AskResponse(String answer, boolean answered, List<Citation> citations) {
}
