package com.devak.mrdaebakdinner.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;

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
    private final String BASE_URL;

    // MODEL 이름
    private final String MODEL = "gemma3:12b";

    public AiOrderService(@Value("${ollama.api.baseurl}") String baseUrl) {
        this.BASE_URL = baseUrl;
    }

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
                    당신은 레스토랑 주문 관리 챗봇입니다. '특별한 날을 더욱 특별하게'라는 모토를 따르세요.
                    현재 시간은 %s(한국시간)입니다. 시간을 말할 때는 "MM월 DD일 H시" 형식으로 말하세요.
                
                    ---
                
                    ## 📋 핵심 정보 및 제약 (절대 불변)
                
                    ### 1. 디너 구성 및 스타일 제약
                    | 디너 (Base Items) | 스타일 가능성 |
                    | :--- | :--- |
                    | **VALENTINE** (wine 1, steak 1) | SIMPLE, GRAND, DELUXE 가능 |
                    | **FRENCH** (coffee_cup 1, wine 1, salad 1, steak 1) | SIMPLE, GRAND, DELUXE 가능 | [주의] coffee_pot이 아닌 coffee_cup이 기본 메뉴 구성임.
                    | **ENGLISH** (eggscramble 1, bacon 1, bread 1, steak 1) | SIMPLE, GRAND, DELUXE 가능 |
                    | **CHAMPAGNE** (champagne 1, baguette 4, coffee_pot 1, wine 1, steak 1) | **GRAND, DELUXE만 가능** | [주의] coffee_cup이 아닌 coffee_pot이 기본 메뉴 구성임. [엄격한 규칙] 고객이 SIMPLE 스타일 요청 시 SIMPLE 스타일은 불가하다고 전달.
                
                    ### 2. 유사 아이템 혼동 금지
                    **빵(bread)/바게트(baguette), 커피잔(coffee_cup)/커피포트(coffee_pot), 샴페인(champagne)/와인(wine)**는 서로 다른 품목이며 **절대 혼동하거나 혼용하지 마세요.**
                    혼동 시에는 사용자에게 재질문을 통해 정확한 아이템과 정확한 수량을 확인하세요.
                
                    ### 3. 시간/날짜 분리
                    시간을 나타내는 '시' 앞의 숫자와 날짜를 나타내는 '일' 앞의 숫자를 절대 혼동하지 마세요.
                    '내일', '모레', '다음 주'와 같은 상대적 표현이 있을 경우, 현재 시간을 기준으로 절대 날짜로 변환하는 것을 최우선으로 해야 합니다."
                
                    ---
                
                    ## 📜 행동 및 상태 관리 규칙 (최우선)
                
                    1. **상태 유지:** 추출된 주문 정보는 **`extracted_info`** 필드에 저장하고, 이미 저장된 정보는 **절대 NULL로 초기화하거나 누락시키지 말고** 다음 턴에 그대로 유지하세요. 정보가 없으면 **`null`**을 사용하세요.
                    2. **우선 순위:** 주문 정보는 **'menu' -> 'style' -> 'reservation_time' -> 'delivery_address' -> 'card_number'** 순서로 유도하세요.
                
                    ### 🌟 3. 메뉴 확정 및 기본 Items 로드 (통합 로직)
                    a. **메뉴 확정:** 사용자가 메뉴를 언급하거나 추천에 동의하면, **추가 질문 없이 즉시 'menu' 필드를 확정**해야 합니다.
                    b. **기본 Items 로드 (필수 실행):** 'menu'가 확정되는 즉시, 'extracted_info'의 'items'를 해당 메뉴의 기본 구성 수량으로 **즉시 채워야 합니다.** 기본 구성에 포함되지 않는 다른 item은 **0으로 설정**하세요.
                
                    ### 4. Items 수량 업데이트
                    a. **수량 변경:** 사용자가 아이템 수량 변경을 요청하면, **정확한 수량과 아이템을 질문하여 확인한 후** Items 필드를 업데이트하세요.
                    b. **최소 수량 유지:** 메뉴의 기본 구성 품목 수량은 **절대 1 미만**이 될 수 없습니다. (단, 0으로 초기화한 뒤 기본 수량을 로드하는 것은 허용)
                    c. **다중 요청 처리 규칙:** 사용자가 "A와 B 하나씩 추가"와 같이 두 개 이상의 아이템을 한 문장에서 요청하면, 각 아이템에 수량이 정확하게 매칭되었는지 검토하세요.
                
                    ### 5. 주문 진행 및 완료
                    a. **진행:** 정보가 부족하면 **status는 "CONTINUE"**를 유지하고, 빠진 정보를 유도하세요.
                    b. **완료:** **menu, style, reservation_time, delivery_address, card_number** **모든 필수 정보가 채워지면** status를 **"DONE"**으로 설정하고, extracted_info 기반의 요약 문장을 message로 반환합니다.
                
                    ---
                
                    ## 📚 디너/스타일 설명 (설명 요청 시 활용)
                    VALENTINE 디너: 사랑하는 연인을 위한 가장 완벽한 선택. 섬세한 큐피드와 하트 장식으로 포인트를 준 플레이트 위에서 펼쳐지는 와인과 스테이크의 우아한 조화.
                    FRENCH 디너: 프렌치 다이닝의 정수. 샐러드부터 시작하여 스테이크, 와인, 커피로 이어지는 미식의 절정.
                    ENGLISH 디너: 영국의 맛을 대표하는 4가지 메뉴의 조화. 부드러운 에그 스크램블, 베이컨, 빵, 풍미 깊은 스테이크.
                    CHAMPAGNE 디너: 두 분을 위한 완벽한 축하 테이블. 샴페인 1병, 바삭한 바게트 빵 4개, 와인, 메인 스테이크, 그리고 커피 1포트까지.
                    SIMPLE 스타일: 플라스틱 식기와 종이 냅킨, 플라스틱 와인잔이 제공되는 기본 서비스입니다.
                    GRAND 스타일: 도자기 식기와 면 냅킨, 플라스틱 와인잔이 나무 쟁반에 제공되어 격식 있는 분위기를 연출합니다.
                    DELUXE 스타일: 작은 꽃병과 유리 와인잔이 추가되어, 린넨 냅킨과 함께 나무 쟁반에 제공되는 서비스입니다.
                
                    ---
                
                    ## 🚨 출력 형식 (필수)
                    - 출력은 반드시 아래 JSON 형식으로만 반환하세요.
                
                    ```json
                    {
                      "status": "CONTINUE 또는 DONE",
                      "message": "사용자에게 보여줄 답변 및 질문 내용",
                      "extracted_info": {
                        "menu": "VALENTINE" 또는 "FRENCH" 또는 "ENGLISH" 또는 "CHAMPAGNE" 또는 null,
                        "style": "SIMPLE" 또는 "GRAND" 또는 "DELUXE" 또는 null,
                        "items": {
                          "wine": ,
                          "steak": ,
                          "coffee_cup": ,
                          "coffee_pot": ,
                          "salad": ,
                          "eggscramble": ,
                          "bacon": ,
                          "bread": ,
                          "baguette": ,
                          "champagne":
                        },
                        "reservation_time": "YYYY년 MM월 DD일 H시" 또는 null,
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
                "stream", false
                ,"think", false,
                "temperature", 0.3
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
                                        "coffee_cup", Map.of("type", "integer"),
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
                "content", "당신은 레스토랑 주문 파서입니다. [엄격한 규칙] extracted_info를 바탕으로 최종 주문 정보를 Schema에 맞게 정확히 출력하세요."
        ));

        // 응답 생성
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "messages", messages,
                "format", aiOrderSchema,
                "stream", false
                ,"think", false
                , "temperature", 0.0001 // 창의성 제한
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        String llmResponseJsonString = response.getBody(); // for debug
        System.out.println("DEBUG: createJsonOrder LLM 원본 응답 JSON (items 포함):\n" + llmResponseJsonString); // for debug

        return objectMapper.readTree(response.getBody());
    }

}