package com.SocialService.Communities.Services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
@EnableScheduling // ✅ Tells Spring to run background tasks automatically
@RequiredArgsConstructor
@Slf4j
public class GrandpaAiBackgroundEngine {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RestTemplate restTemplate = new RestTemplate(); // Standard Java HTTP Client

    private static final String QUEUE_NAME = "ai:queue:grandpa_engine";
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";

    private static final String SYSTEM_PROMPT =
            "You are a 100-year-old, street-smart grandfather who has lived everywhere, seen every scam, and can read human behavior instantly. " +
                    "Your life purpose is to protect good-hearted, innocent people from betrayal, scams, manipulative tourist guides, drivers, locals, and even deceptive friends or family members. " +
                    "Analyze the following message sent in a small group chat. If you detect manipulation, an active setup, an emotional trap, or an imminent scam, predict exactly what will happen and give direct, unfiltered strategic advice. " +
                    "If appropriate, generate a psychological testing question (like an elite behavioral manager) to help the user test the intentions of the person they are interacting with. " +
                    "Keep your response protective, incredibly wise, and deeply realistic.";

    // 🕒 Runs automatically every 2 seconds in the background
    @Scheduled(fixedDelay = 2000)
    public void pollAndProcessAiQueue() {
        try {
            // Right-pop an item off the Redis list queue (Non-blocking check)
            Object packedPayload = redisTemplate.opsForList().rightPop(QUEUE_NAME);

            if (packedPayload instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rawData = (Map<String, Object>) packedPayload;

                String roomId = (String) rawData.get("roomId");
                String user = (String) rawData.get("senderUsername");
                String textContent = (String) rawData.get("text");

                log.info("👴 Grandpa Java Engine analyzing incoming message from room [{}] by user ({})", roomId, user);

                // Build the HTTP request body for your local Ollama AI
                Map<String, Object> ollamaRequest = new HashMap<>();
                ollamaRequest.put("model", "llama3"); // Ensure you ran 'ollama run llama3' on your PC
                ollamaRequest.put("prompt", SYSTEM_PROMPT + "\n\nContext: User '" + user + "' said: '" + textContent + "'.");
                ollamaRequest.put("stream", false);

                // Fire the post request straight into Ollama running on your local machine
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.postForObject(OLLAMA_URL, ollamaRequest, Map.class);

                if (response != null && response.containsKey("response")) {
                    String aiInsight = (String) response.get("response");

                    log.info("============== 👴 GRANDPA AI REAL-TIME INSIGHT ==============");
                    log.info(aiInsight);
                    log.info("=============================================================");

                    // Future roadmap point: You can call your NotificationController right here
                    // to send this warning straight back to the chat group members!
                }
            }
        } catch (Exception e) {
            log.error("Error running native Java Grandpa AI background broker: ", e);
        }
    }
}