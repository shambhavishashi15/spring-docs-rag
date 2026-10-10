package io.github.shambhavishashi15.springdocsrag.ask;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ask")
public class AskController {

	private final AskService askService;

	public AskController(AskService askService) {
		this.askService = askService;
	}

	@PostMapping
	public AskResponse ask(@Valid @RequestBody AskRequest request) {
		return askService.ask(request.question());
	}

}
