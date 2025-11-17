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
    private final String MODEL = "qwen3:0.6b";

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
            String SYSTEM_PROMPT = """
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
                      - 전체 대화에서 적어도 한 번은 주문에 변경사항이 없는지 물어보세요.
                      - 정보가 아직 채워지지 않았다면 status에 "CONTINUE"와 함께 "message"에 대화를 이어가세요.
                      - 모든 정보가 채워지면 status엔 "DONE"과 함께 "message"에 주문을 요약하는 문장을 반환하세요.
                        디너 종류, 예약시간, 서빙 스타일, 변경사항, 배달주소, 카드번호가 포함되어야 합니다.
                      - 출력은 반드시 아래 JSON 형식으로만 반환하세요.
                        {
                          "status": "CONTINUE or DONE",
                          "message": "..."
                        }
                    
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
        String status = objectMapper.readTree(jsonText).get("status").asText();
        System.out.println("의심구간: status = " + status); // for debug
        String message = objectMapper.readTree(jsonText).get("message").asText();
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
                "content", message));

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
                                "message", Map.of("type", "string")
                        ),
                        "required", List.of("status", "message"),
                        "additionalProperties", false
                ),
                "stream", false
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
