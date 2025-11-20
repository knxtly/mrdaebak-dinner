package com.devak.mrdaebakdinner.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private static final Map<String, List<Map<String, String>>> conversationMap = new HashMap<>();

    // API의 BASE_URL
    private static final String BASE_URL = "https://api.openai.com/v1";

    // 대화 API
    @PostMapping("/ai-chat-order")
    public ResponseEntity<?> aiChatOrder(@RequestBody Map<String, String> payload) {
        String userInput = payload.get("userInput");

        // 오류 처리
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            return ResponseEntity.status(500).body(Map.of(
                    "status", "ERROR",
                    "message", "OpenAI API Key가 설정되지 않았습니다."
            ));
        }
        if (userInput == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "사용자 입력이 누락되었습니다."
            ));
        }
        try {
            // conversation 찾기
            String conversationId = conversationMap.isEmpty()
                    ? null
                    : conversationMap.keySet().iterator().next();

            // conversationId가 없으면 생성
            if (conversationId == null) {
                conversationId = createConversation().path("id").asText();
                conversationMap.put(conversationId, new ArrayList<>());
            }

            /*
             * response 생성
             * model response 객체의 구조
             * https://platform.openai.com/docs/api-reference/responses/create
             */
            JsonNode responseNode = createResponse(conversationId, userInput);
            JsonNode contentNode = responseNode.path("output").get(0).path("content").get(0);
            String type = contentNode.path("type").asText(); // "refusal" or "output_text"

            // AI가 답변을 거부
            if ("refusal".equals(type)) { // "type": "refusal" 일 때
                return ResponseEntity.status(500).body(Map.of(
                        "status", "ERROR",
                        "message", "AI가 답변을 거부했습니다."
                ));
            } else if ("output_text".equals(type)) {
                // "type": "output_text" 일 때
                String jsonText = contentNode.path("text").asText(); // json답변 (String) status, message가지고있음
                String status = objectMapper.readTree(jsonText).get("status").asText();
                String message = objectMapper.readTree(jsonText).get("message").asText();

                // history 업데이트: { user-input + assistant-reply }
                List<Map<String, String>> history = conversationMap.get(conversationId);
                history.add(Map.of(
                        "user", userInput,
                        "assistant", message
                ));

                // "DONE"을 반환했을 때
                if ("DONE".equalsIgnoreCase(status)) {
                    // order 추출
                    String jsonOrder = createJsonOrder(conversationId)
                            .path("output").get(0)
                            .path("content").get(0)
                            .path("text").asText();
                    // jsonOrder로부터 menu, style, items, deliveryAddress, cardNumber, reservationTime 파싱
                    JsonNode order = objectMapper.readTree(jsonOrder);
                    String menu = order.path("menu").asText();
                    String style = order.path("style").asText();
                    String items = order.path("items").toString();
                    String deliveryAddress = order.path("deliveryAddress").asText();
                    String cardNumber = order.path("cardNumber").asText();
                    String reservationTime = order.path("reservationTime").asText();

                    // conversation 삭제
                    deleteConversation(conversationId);
                    conversationMap.remove(conversationId);

                    return ResponseEntity.ok(Map.of(
                            "status", "DONE",
                            "menu", menu,
                            "style", style,
                            "items", objectMapper.readTree(items),
                            "deliveryAddress", deliveryAddress,
                            "cardNumber", cardNumber,
                            "reservationTime", reservationTime,
                            "message", message
                    ));
                }

                // "CONTINUE"를 반환했을 때 message(와 history) 반환
                if ("CONTINUE".equalsIgnoreCase(status)) {
                    return ResponseEntity.ok(Map.of(
                            "status", "CONTINUE",
                            "message", message
                    ));
                }
            }
        }
        catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "status", "ERROR",
                    "message", "AI처리 오류: " + e.getMessage()
            ));
        }
        return ResponseEntity.status(500).body(Map.of(
                "status", "ERROR",
                "message", "오류"
        ));
    }

    private JsonNode createConversation() throws JsonProcessingException {
        String url = BASE_URL + "/conversations";
        String nowTime = OffsetDateTime.now(ZoneOffset.ofHours(9)).toString();
        String systemPrompt = """
                당신은 레스토랑 주문 챗봇입니다.
                사용자의 대화를 기반으로 주문 정보를 추출하세요.
                현재 시간은 %s(한국시간)입니다. 시간을 말할 때는 "N월 N일 N시" 형식으로 말하세요.
                디너의 종류와 기본 세트 구성은 다음과 같습니다:
                  - VALENTINE: wine 1, steak 1
                  - FRENCH: coffee_cup 1, wine 1, salad 1, steak 1
                  - ENGLISH: eggscramble 1, bacon 1, bread 1, steak 1
                  - CHAMPAGNE: champagne 1, baguette 4, coffee_pot 1, wine 1, steak 1
                
                알아야 할 정보:
                  - 디너 종류 = menu (VALENTINE, FRENCH, ENGLISH, CHAMPAGNE 중 하나)
                  - 서빙 스타일 = style (SIMPLE, GRAND, DELUXE 중 하나)
                  - 메뉴 구성 = items (wine, steak, coffe_cup, coffee_pot, salad, eggscramble, bacon, bread, baguette, champagne의 각 수량)
                  - 배달 주소 = delivery_address
                  - 카드번호 = card_number
                  - 예약 시간 = reservation_time
                
                규칙:
                  - CHAMPAGNE 디너는 SIMPLE 스타일로 주문할 수 없습니다.
                  - 주문이 완료되지 않았으면 빠진 정보를 유도하세요.
                  - 항상 주문에 변경사항이 없는지 사용자에게 되물어보세요. 전체 대화에서 적어도 한 번은 물어봐야 합니다.
                  - 정보가 아직 채워지지 않았다면 status에 "CONTINUE"와 함께 "message"에 대화를 이어가세요.
                  - 모든 정보가 채워지면 status엔 "DONE"과 함께 "message"에 주문을 요약하는 문장을 반환하세요.
                    디너 종류, 예약시간, 서빙 스타일, 변경사항, 배달주소, 카드번호가 포함되어야 합니다.
                
                대화 예시:
                고객: 맛있는 디너 추천해주세요.
                시스템: 무슨 기념일인가요?
                고객: 내일이 어머님 생신이에요 / 모레가 어머님 생신이에요
                시스템: 정말 축하드려요. 프렌치 디너 또는 샴페인 축제 디너는 어떠세요?
                고객: 샴페인 축제 디너 좋겠어요.
                시스템: 샴페인 축제 디너 알겠습니다. 그리고 서빙은 디럭스 스타일 어떨까요?
                고객: 네, 디럭스 스타일 좋아요.
                시스템: 네, OOO 고객님, 디너는 샴페인 축제 디너, 서빙은 디럭스 스타일로 주문하셨습니다.
                고객: 그리고 바케트빵을 6개로, 샴페인을 2병으로 변경해요.
                시스템: 네, OOO 고객님, 디너는 샴페인 축제 디너, 서빙은 디럭스 스타일, 바케트빵 6개, 샴페인 2병 주문하셨습니다.
                고객: 맞아요.
                시스템: 추가로 필요하신 것 있으세요?
                고객: 없어요.
                시스템: 12월2일/12월3일에 주문하신대로 보내드리겠습니다. 감사합니다.
                """.formatted(nowTime);

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

        // 응답 생성
        Map<String, Object> body = Map.of(
                "model", "gpt-4.1-mini",
                "conversation", conversationId,
                "input", userInput,
                "temperature", 0.2,
                "text", Map.of(
                        "format", Map.of(
                                "type", "json_schema",
                                "name", "responseSchema",
                                "schema", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "status", Map.of("type", "string", "enum", List.of("CONTINUE", "DONE")),
                                                "message", Map.of("type", "string")
                                        ),
                                        "required", List.of("status", "message"),
                                        "additionalProperties", false
                                ),
                                "strict", true
                        )
                )
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
        Map<String, Object> aiOrderSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "menu", Map.of(
                                "type", "string",
                                "enum", List.of("VALENTINE", "FRENCH", "ENGLISH", "CHAMPAGNE")
                        ),
                        "style", Map.of(
                                "type", "string",
                                "enum", List.of("SIMPLE", "GRAND", "DELUXE")
                        ),
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
                                "required", List.of(
                                        "wine", "steak", "coffe_cup", "coffee_pot", "salad",
                                        "eggscramble", "bacon", "bread", "baguette", "champagne"
                                ),
                                "strict", true,
                                "additionalProperties", false
                        ),
                        "deliveryAddress", Map.of("type", "string"),
                        "cardNumber", Map.of("type", "string"),
                        "reservationTime", Map.of("type", "string")
                ),
                "required", List.of(
                        "menu", "style", "items",
                        "deliveryAddress", "cardNumber", "reservationTime"
                ),
                "strict", true,
                "additionalProperties", false
        );

        // 응답 생성
        Map<String, Object> body = Map.of(
                "model", "gpt-4.1-mini",
                "conversation", conversationId,
                "input", List.of(
                        Map.of(
                                "role", "system",
                                "content", "당신은 레스토랑 주문 파서입니다. 이전 대화 전체를 바탕으로 최종 주문 정보를 Schema에 맞게 정확히 출력하세요."
                        )
                ),
                "temperature", 0.2,
                "text", Map.of(
                        "format", Map.of(
                                "type", "json_schema",
                                "name", "aiOrderSchema",
                                "schema", aiOrderSchema,
                                "strict", true
                        )
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
