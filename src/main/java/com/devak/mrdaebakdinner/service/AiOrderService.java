package com.devak.mrdaebakdinner.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiOrderService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // conversation 저장소
    // { userId: [ {role:"system", content:...}, {role:"user", content:...}, {role:"assistant", content:...} ] }
    private static final Map<String, List<Map<String, String>>> conversationMap = new HashMap<>();

    // API의 BASE_URL
    private final String BASE_URL = "http://host.docker.internal:11434";

    // MODEL 이름
    private final String MODEL = "gemma2:9b";

    // Controller에 의해 호출
    public Map<String, Object> processUserMessage(String userInput, String userId) throws JsonProcessingException {
        String s = """
                Error: 서버 오류: 
                Cannot invoke "com.fasterxml.jackson.databind.JsonNode.asText()" 
                because the return value of "com.fasterxml.jackson.databind.JsonNode.get(String)" is null
                """;
        // conversation 찾기
        List<Map<String, String>> history = conversationMap.get(userId);

        // 기록 없으면 시스템 프롬프트 history에 넣으면서 만들기
        if (history == null) {
            history = new ArrayList<>();
            String nowTime = OffsetDateTime.now(ZoneOffset.ofHours(9))
                    .format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 H시"));
            // processUserMessage 메서드 내부에 추가해야 할 핵심 코드:
            conversationMap.put(userId, history);
            String SYSTEM_PROMPT = """
                    당신은 레스토랑 주문 관리 챗봇입니다. 현재 시간은 %s(한국시간)입니다. 시간을 말할 때는 "N월 N일 N시" 형식으로 말하세요.
                    
                    ## 📋 디너 메뉴 및 구성
                      - VALENTINE (wine 1, steak 1)
                      - FRENCH (coffee_cup 1, wine 1, salad 1, steak 1)
                      - ENGLISH (eggscramble 1, bacon 1, bread 1, steak 1)
                      - CHAMPAGNE (champagne 1, baguette 4, coffee_pot 1, wine 1, steak 1)
                    
                    ## 🚨 최우선 행동 규칙: 상태 유지 및 출력 형식
                    1. **출력 의무:** 당신은 항상 아래 **출력 형식에 맞는 JSON 객체만** 반환해야 합니다. JSON 밖에는 어떤 추가적인 텍스트도 넣지 마세요.
                    2. **정보 저장:** 이전 대화에서 추출된 주문 정보는 **`extracted_info`** 필드에 저장하고, 정보가 아직 채워지지 않았다면 해당 필드는 반드시 **`null`** 값으로 유지하세요.
                    3. **상태 유지(최우선 명령):** extracted_info에 이미 저장된 정보는 절대 NULL로 초기화하거나 누락시키지 말고 다음 턴에 그대로 유지해야 합니다. 오직 사용자 요청에 의해 명시적으로 변경되거나 새롭게 채워지는 필드만 수정하세요.
                    
                    ## 🔍 필수 정보 필드 (extracted_info에 저장)
                    - **menu** (string): 디너 종류 (VALENTINE, FRENCH, ENGLISH, CHAMPAGNE 중 하나)
                    - **style** (string): 서빙 스타일 (SIMPLE, GRAND, DELUXE 중 하나. *CHAMPAGNE은 SIMPLE 불가*)
                    - **items** (object): 메뉴 구성 (각 항목의 수량. 기본 구성 외 변경 사항만 기록)
                    - **reservation_time** (string): 예약 시간 (YYYY년 M월 D일 H시)
                    - **delivery_address** (string): 배달 주소
                    - **card_number** (string): 카드번호
                    
                    ## 💬 대화 진행 규칙
                    1. 항상 한국어로 답변 및 질문하세요.
                    2. **우선 순위:** 주문 정보는 'menu' -> 'style' -> 'reservation_time' 순서로 유도하세요. 
                    3. **추천 및 확정:** 메뉴 혹은 스타일을 추천했을 경우, 추천 근거를 설명하며, 사용자가 동의하면 **즉시 해당 메뉴나 스타일로 'menu'나 'style'필드를 확정**하고 다음 정보 유도로 넘어갑니다.
                    4. **변경사항 확인:** 전체 대화 과정 중 적어도 한 번은 주문에 변경사항이 없는지 사용자에게 물어보세요.
                    5. **주문 완료:** 모든 필수 정보가 채워지면 **status를 "DONE"으로 설정**하고, message에 주문 요약 문장을 반환하세요.
                    6. **주문 진행:** 정보가 부족하면 **status는 "CONTINUE"**를 유지하고, message에 빠진 정보를 유도하는 대화 내용을 반환하세요.
                    
                    ## 🚨 출력 형식 (필수)
                      - 출력은 반드시 아래 JSON 형식으로만 반환하세요.
                        {
                          "status": "CONTINUE 또는 DONE",
                          "message": "사용자에게 보여줄 답변 및 질문 내용",
                          "extracted_info": {
                            "menu": "VALENTINE" 또는 "FRENCH" 또는 "ENGLISH" 또는 "CHAMPAGNE",
                            "style": "SIMPLE" 또는 "GRAND" 또는 "DELUXE" ,
                            "items": {
                              "wine": 0,
                              "steak": 0,
                              "coffe_cup": 0,
                              "coffee_pot": 0,
                              "salad": 0,
                              "eggscramble": 0,
                              "bacon": 0,
                              "bread": 0,
                              "baguette": 0,
                              "champagne": 0
                            },
                            "reservation_time": "YYYY년 M월 D일 H시" 또는 null,
                            "delivery_address": "배달 주소" 또는 null,
                            "card_number": "카드 번호" 또는 null
                          }
                        }
                """.formatted(nowTime);
            history.add(Map.of(
                    "role", "system",
                    "content", SYSTEM_PROMPT
            ));
        }

        /*
         * response 생성
         * model response 객체의 구조
         * https://docs.ollama.com/api/chat
         */
        JsonNode responseNode = createResponse(history, userInput);
        String jsonText = responseNode.path("message").path("content").asText();
        System.out.println("의심구간: jsonText = " + jsonText); // for debug
        JsonNode parsedJson = objectMapper.readTree(jsonText);
        String status = parsedJson.get("status").asText();
        System.out.println("의심구간: status = " + status); // for debug
        String message = parsedJson.get("message").asText();
        System.out.println("의심구간: message = " + message); // for debug

        System.out.println("파싱됨: jsonText =" + jsonText); // for debug
        System.out.println("파싱됨: status =" + status); // for debug
        System.out.println("파싱됨: message =" + message); // for debug

        // history 업데이트: { user-input + assistant-reply }
        history.add(Map.of(
                "role", "user",
                "content", userInput));
        history.add(Map.of(
                "role", "assistant",
                "content", jsonText));

        // "DONE"을 반환했을 때 -> 지금까지 대화 바탕 주문 추출
        if ("DONE".equalsIgnoreCase(status)) {
            // order 추출
            String jsonOrder = createJsonOrder(history).path("message").path("content").asText();
            // jsonOrder로부터 menu, style, items, deliveryAddress, cardNumber, reservationTime 파싱
            JsonNode order = objectMapper.readTree(jsonOrder);
            String menu = order.path("menu").asText();
            String style = order.path("style").asText();
            String items = order.path("items").toString();
            String deliveryAddress = order.path("deliveryAddress").asText();
            String cardNumber = order.path("cardNumber").asText();
            String reservationTime = order.path("reservationTime").asText();

            // conversation 삭제
            conversationMap.remove(userId);

            return Map.of(
                    "status", "DONE",
                    "menu", menu,
                    "style", style,
                    "items", objectMapper.readTree(items),
                    "deliveryAddress", deliveryAddress,
                    "cardNumber", cardNumber,
                    "reservationTime", reservationTime,
                    "message", message
            );
        }

        // "CONTINUE"를 반환했을 때 message(와 history) 반환
        if ("CONTINUE".equalsIgnoreCase(status)) {
            return Map.of(
                    "status", "CONTINUE",
                    "message", message
            );
        }

        return Map.of(
                "status", "ERROR",
                "message", "JSON Format깨짐(알 수 없는 status)"
        );
    }

    private JsonNode createResponse(List<Map<String, String>> history, String userInput) throws JsonProcessingException {
        String url = BASE_URL + "/api/chat";
        System.out.println("createResponse()진입"); // for debug
        // history를 복사하고 새로운 user 메시지를 추가
        List<Map<String, String>> messages = new ArrayList<>(history);
        messages.add(Map.of(
                "role", "user",
                "content", userInput
        ));

        // 응답 생성
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "messages", messages,
                "format", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "status", Map.of("type", "string", "enum", List.of("CONTINUE", "DONE")),
                                "message", Map.of("type", "string"),
                                "extracted_info", Map.of("type", "object")
                        ),
                        "required", List.of("status", "message", "extracted_info"),
                        "additionalProperties", false
                ),
                "stream", false,
                "options", Map.of(
                        "temperature", 0.1
                )
//                ,"think", false
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        System.out.println("\n\nHttpEntity 구성" + entity); // for debug
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        System.out.println("\n\nresponse 반환됨. createResponse()끝" + response); // for debug
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode createJsonOrder(List<Map<String, String>> history) throws JsonProcessingException {
        String url = BASE_URL + "/api/chat";

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
                "additionalProperties", false
        );

        // history를 복사하고 새로운 system 메시지를 추가
        List<Map<String, String>> messages = new ArrayList<>(history);
        messages.add(Map.of(
                "role", "system",
                "content", "당신은 레스토랑 주문 파서입니다. 이전 대화 전체를 바탕으로 최종 주문 정보를 Schema에 맞게 정확히 출력하세요."
        ));

        // 응답 생성
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "messages", messages,
                "format", aiOrderSchema,
                "stream", false
//                ,"think", false
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        return objectMapper.readTree(response.getBody());
    }

}
