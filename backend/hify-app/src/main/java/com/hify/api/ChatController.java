package com.hify.api;

import com.hify.domain.ChatMessage;
import com.hify.domain.Conversation;
import com.hify.infra.ChatMessageRepository;
import com.hify.infra.ConversationRepository;
import com.hify.runtime.ToolDefinition;
import com.hify.runtime.ToolRuntime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ToolRuntime tools;

    public ChatController(ConversationRepository conversations, ChatMessageRepository messages,
                          ToolRuntime tools) {
        this.conversations = conversations;
        this.messages = messages;
        this.tools = tools;
    }

    @GetMapping("/conversations/{id}")
    public ConversationView conversation(@PathVariable String id) {
        Conversation conversation = conversations.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
        return new ConversationView(conversation,
                messages.findByConversationIdOrderByCreatedAtAsc(id));
    }

    @GetMapping("/tools")
    public List<ToolDefinition> tools() {
        return tools.definitions(Set.of("current_time", "calculator"));
    }
    public record ConversationView(Conversation conversation, List<ChatMessage> messages) {}
}
