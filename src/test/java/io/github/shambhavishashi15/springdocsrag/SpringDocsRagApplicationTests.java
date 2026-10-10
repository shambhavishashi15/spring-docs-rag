package io.github.shambhavishashi15.springdocsrag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// Dummy key: the OpenAI auto-configuration needs a credential to start, but tests never call the real Gemini API.
@SpringBootTest(properties = "spring.ai.openai.api-key=test-dummy-key")
@Import(TestcontainersConfiguration.class)
class SpringDocsRagApplicationTests {

	@Test
	void contextLoads() {
	}

}
