/*package com.SocialService.Communities.Services;

import com.SocialService.Communities.Models.Post;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiConciergeServiceImpl {

    private final SocialService socialService;
    private final ChatModel chatModel;

    public String consultAgent(String userQuery, String city, Long userId) {
        log.info("AI Concierge activated by traveler {} for city zone: {}", userId, city);

        // 1. Fetch live community knowledge maps from your Redis-backed feeds
        Page<Post> localDiscoveryFeed = socialService.getCityFeed(city, null, PageRequest.of(0, 15));

        // 2. Format the real local posts into a structured text database context
        String localKnowledgeContext = localDiscoveryFeed.getContent().stream()
                .map(post -> String.format("- [%s] Title: %s | Content: %s (Community Score Rating: %d)",
                        post.getCategory(), post.getTitle(), post.getContent(), post.getScore()))
                .collect(Collectors.joining("\n"));

        // 3. Build the protective system prompt instructions
        String systemInstructions = """
            You are the Ultimate AI Travel Concierge & City Survival Guide for young newcomers, expats, and travelers.
            Your job is to provide highly practical, budget-conscious, and safety-focused guidance.
            
            You must prioritize using the real-world 'Local Knowledge Context' provided below, which contains real, highly-vetted community updates from locals.
            If the context contains warnings about scams, danger zones, or bad landlords, emphasize them immediately to protect the traveler.
            
            Current City Context: {city}
            Local Knowledge Context:
            {context}
            
            Be witty, clear, supportive, and direct. Do not recommend places known to be dangerous or highly expensive unless explicitly requested.
            """;

        // 4. Create the multi-message prompt payload manually for ChatModel compatibility
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemInstructions);
        Message systemMessage = systemPromptTemplate.createMessage(Map.of(
                "city", city,
                "context", localKnowledgeContext.isEmpty() ? "No specific local warnings recorded yet." : localKnowledgeContext
        ));

        UserMessage userMessage = new UserMessage(userQuery);
        Prompt finalPrompt = new Prompt(List.of(systemMessage, userMessage));

        // 5. Execute call directly via the ChatModel engine
        try {
            return chatModel.call(finalPrompt).getResult().getOutput().getContent();
        } catch (Exception e) {
            log.error("AI service completion engine failure fallback triggered: ", e);
            return "Hey traveler! My AI mapping grid is experiencing high-orbit interference, but based on local rules: stay alert, avoid unverified taxi lines, and check back in a few moments!";
        }
    }
}*/
package com.SocialService.Communities.Services;

import com.SocialService.Communities.Repositories.SocialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiConciergeServiceImpl {

    // ✅ Temporarily commented out to bypass corporate firewall bean dependency crashes
    // private final ChatModel chatModel;
    private final SocialService socialService;

    public String consultAgent(String userQuery, String city, Long userId) {
        log.info("AI Concierge temporary local bypass active for traveler: {}", userId);
        return "Hey traveler! The AI Concierge module is currently in offline hibernation mode while we optimize local storage sync profiles. Check your local offline guides in the meantime!";
    }
}