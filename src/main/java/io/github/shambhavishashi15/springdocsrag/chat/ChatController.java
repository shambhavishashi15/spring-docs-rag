package io.github.shambhavishashi15.springdocsrag.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

	private final ChatClient chatClient;

	// Spring AI auto-configures a ChatClient.Builder backed by the OpenAI (here: Gemini) ChatModel.
	public ChatController(ChatClient.Builder chatClientBuilder) {
		this.chatClient = chatClientBuilder.build();
	}

	@PostMapping
	public ChatReply chat(@RequestBody ChatRequest request) {
		String reply = chatClient.prompt()
				.user(request.message())
				.call()
				.content();
		return new ChatReply(reply);
	}

}
