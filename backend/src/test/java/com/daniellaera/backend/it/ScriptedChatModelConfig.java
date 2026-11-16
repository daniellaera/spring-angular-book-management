package com.daniellaera.backend.it;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Replaces the Anthropic model in integration tests (no network, no cost). It behaves like a model that asks for the
 * scripted tool calls: it looks the tools up in the options the ChatClient handed over, runs the real callbacks
 * (so the real BookTools, call budget and database are exercised) and answers with the last tool result.
 * Whether the real model chooses the right tool is covered by the eval set (deploy/ai-eval.sh), not here.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedChatModelConfig {

    public static class ScriptedChatModel implements ChatModel {
        public volatile List<String> script = List.of("count_books");
        public final List<String> toolNamesSeen = new CopyOnWriteArrayList<>();
        public final List<String> results = new CopyOnWriteArrayList<>();
        public volatile String systemPrompt;

        public void reset(String... tools) {
            script = List.of(tools);
            toolNamesSeen.clear();
            results.clear();
        }

        private static String decode(String jsonString) {
            try {
                return // Spring AI JSON-quotes plain strings, but passes through any text that merely starts with a valid
            // JSON value (e.g. "2 books ..."): decode only the quoted form.
            jsonString.startsWith("\"") ? new com.fasterxml.jackson.databind.ObjectMapper().readValue(jsonString, String.class) : jsonString;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<ToolCallback> callbacks = prompt.getOptions() instanceof ToolCallingChatOptions o
                    ? o.getToolCallbacks() : List.of();
            toolNamesSeen.clear();
            callbacks.forEach(c -> toolNamesSeen.add(c.getToolDefinition().name()));
            systemPrompt = prompt.getSystemMessage() != null ? prompt.getSystemMessage().getText() : null;

            List<String> local = new ArrayList<>();
            for (String name : script) {
                ToolCallback cb = callbacks.stream().filter(c -> c.getToolDefinition().name().equals(name))
                        .findFirst().orElseThrow(() -> new IllegalStateException("tool not registered: " + name));
                String args = switch (name) {
                    case "list_by_author" -> "{\"author\":\"cormen\"}";
                    case "list_by_genre" -> "{\"genre\":\"program\"}";
                    case "get_book" -> "{\"id\":1}";
                    case "search_books" -> "{\"query\":\"anything\",\"limit\":5}";
                    default -> "{}";
                };
                local.add(decode(cb.call(args)));
            }
            results.addAll(local);
            String answer = local.isEmpty() ? "no tool" : local.get(local.size() - 1);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    @Bean
    @Primary
    ScriptedChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }
}
