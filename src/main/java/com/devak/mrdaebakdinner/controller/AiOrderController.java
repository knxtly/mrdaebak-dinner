package com.devak.mrdaebakdinner.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/customer")
public class AiOrderController {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    @Value("${openai.api-key}")
    private String openaiApiKey;

    // conversation 저장소
    /********
    {
        "conversation_id": "conv_123",
        "history": [
            { "user": "추천해주세요", "assistant": "무슨 기념일인가요?" },
            { "user": "어머님 생신이에요", "assistant": "축하드립니다. 프렌치 디너 어떠세요?" }
        ]
    }*/
    private static final Map<String, Map<String, Object>> conversationMap = new HashMap<>();

    // API의 BASE_URL
    private static final String BASE_URL = "https://api.openai.com/v1";

    // 대화 API
    @PostMapping("/ai-chat-order")
    public ResponseEntity<?> aiChatOrder(@RequestBody Map<String, String> payload) {
        String userInput = payload.get("userInput");

        // 오류 처리
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            return ResponseEntity.status(500).body(Map.of(
                    "status", "error",
                    "message", "OpenAI API Key가 설정되지 않았습니다."
            ));
        }
        if (userInput == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "사용자 입력이 누락되었습니다."
            ));
        }

        try {
            String conversationId = conversationMap.isEmpty()
                    ? null
                    : conversationMap.keySet().iterator().next();

            // conversationId가 없으면 생성
            if (conversationId == null) {
                conversationId = createConversation().path("id").asText();
                Map<String, Object> convData = new HashMap<>();
                convData.put("conversation_id", conversationId);
                convData.put("history", new ArrayList<Map<String, Object>>());
                conversationMap.put(conversationId, convData);
            }

            // response 생성
            JsonNode responseNode = createResponse(conversationId, userInput); // { "status": ..., "output_text": ... }
            String status = responseNode.path("status").asText();
            String answer = responseNode.path("output_text").asText("");

            // history 업데이트: { user-input + assistant-reply }
            List<Map<String, String>> history =
                    (List<Map<String, String>>) conversationMap.get(conversationId).get("history");
            history.add(Map.of(
                    "user", userInput,
                    "assistant", answer
            ));

            // "DONE"이면 order추출하고 conversation 삭제
            if ("DONE".equalsIgnoreCase(status)) {
                JsonNode extractedOrder = createJsonOrder(conversationId);

                deleteConversation(conversationId);
                conversationMap.remove(conversationId);
                return ResponseEntity.ok(Map.of(
                        "status", "DONE",
                        "message", extractedOrder
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "status", "CONTINUE",
                    "message", answer,
                    "conversation_id", conversationId,
                    "history", history
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "status", "error",
                    "message", "AI 처리 오류" + e.getMessage()
            ));
        }
    }

    private JsonNode createConversation() throws JsonProcessingException {
        String url = BASE_URL + "/conversations";
        String systemPrompt = """
                당신은 레스토랑 주문 챗봇입니다.
                사용자의 대화를 기반으로 주문 정보를 추출하세요.
                디너의 종류와 기본 세트 구성은 다음과 같습니다:
                  - VALENTINE: wine 1, steak 1
                  - FRENCH: coffee_cup 1, wine 1, salad 1, steak 1
                  - ENGLISH: eggscramble 1, bacon 1, bread 1, steak 1
                  - CHAMPAGNE: champagne 1, baguette 4, coffee_pot 1, wine 1, steak 1
                알아야 할 정보:
                  - menu (VALENTINE, FRENCH, ENGLISH, CHAMPAGNE 중 하나)
                  - style (SIMPLE, GRAND, DELUXE 중 하나)
                  - items (wine, steak, coffe_cup, coffee_pot, salad, eggscramble, bacon, bread, baguette, champagne의 각 수량)
                  - delivery_address
                  - card_number
                  - reservation_time
                규칙:
                  - 주문이 완료되지 않았으면 빠진 정보를 유도하세요.
                  - CHAMPAGNE 디너는 SIMPLE 스타일로 주문할 수 없습니다.
                  - 모든 정보가 채워지면 status엔 "DONE" 과 사용자에게 주문을 요약하는 문장을 반환하세요.
                """;

        Map<String, Object> body = Map.of(
                "items", List.of(Map.of("role", "system", "content", systemPrompt))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openaiApiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        return objectMapper.readTree(response.getBody());
    }

    private JsonNode createResponse(String conversationId, String userInput) throws JsonProcessingException {
        String url = BASE_URL + "/responses";

        // 반환될 JSON schema 지정
        Map<String, Object> responseFormat = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "ChatResponseSchema",
                        "schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "status", Map.of("type", "string", "enum", List.of("CONTINUE", "DONE")),
                                        "output_text", Map.of("type", "string")
                                ),
                                "required", List.of("status", "output_text")
                        )
                )
        );

        // 응답 생성
        Map<String, Object> body = Map.of(
                "model", "gpt-4.1-mini",
                "conversation", conversationId,
                "input", userInput,
                "response_format", responseFormat
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openaiApiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        return objectMapper.readTree(response.getBody());
    }

    private void deleteConversation(String conversationId) {
        String url = BASE_URL + "/conversations/" + conversationId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openaiApiKey);

        restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    private JsonNode createJsonOrder(String conversationId) throws JsonProcessingException {
        String url = BASE_URL + "/responses";

        // 반환될 JSON schema 지정
        Map<String, Object> schema = Map.of(
                "name", "AiOrderSchema",
                "schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "menu", Map.of("type", "string", "enum", List.of("VALENTINE", "FRENCH", "ENGLISH", "CHAMPAGNE")),
                                "style", Map.of("type", "string", "enum", List.of("SIMPLE", "GRAND", "DELUXE")),
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "wine", Map.of("type", "integer"),
                                                "steak", Map.of("type", "integer"),
                                                "coffe_cup", Map.of("type", "integer"),
                                                "coffee_pot", Map.of("type", "integer"),
                                                "salad", Map.of("type", "integer"),
                                                "eggscramble", Map.of("type", "integer"),
                                                "bacon", Map.of("type", "integer"),
                                                "bread", Map.of("type", "integer"),
                                                "baguette", Map.of("type", "integer"),
                                                "champagne", Map.of("type", "integer")
                                        ),
                                        "additionalProperties", false
                                ),
                                "deliveryAddress", Map.of("type", "string"),
                                "cardNumber", Map.of("type", "string")
                        ),
                        "required", List.of("menu", "style", "items")
                )
        );

        // 답변(최종 JSON) 생성
        Map<String, Object> body = Map.of(
                "model", "gpt-4.1-mini",
                "conversation", conversationId,
                "input", "이 대화 전체를 기반으로 최종 주문 정보를 JSON Schema에 맞게 출력하세요.",
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", schema
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openaiApiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        return objectMapper.readTree(response.getBody());
    }

}
