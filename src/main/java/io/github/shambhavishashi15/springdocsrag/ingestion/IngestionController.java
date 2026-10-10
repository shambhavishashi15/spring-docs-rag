package io.github.shambhavishashi15.springdocsrag.ingestion;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ingest")
public class IngestionController {

	private final IngestionService ingestionService;

	public IngestionController(IngestionService ingestionService) {
		this.ingestionService = ingestionService;
	}

	// Synchronous: returns once all pages are processed (no API calls for unchanged pages).
	@PostMapping
	public IngestionResult ingest() {
		return ingestionService.ingest();
	}

}
