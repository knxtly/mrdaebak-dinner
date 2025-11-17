package com.devak.mrdaebakdinner.controller;

import com.devak.mrdaebakdinner.dto.CustomerSessionDTO;
import com.devak.mrdaebakdinner.service.AiOrderService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/customer")
public class AiOrderController {

    private final AiOrderService aiOrderService;

    @PostMapping("/ai-chat-order")
    public ResponseEntity<?> chatWithAi(@RequestBody Map<String, String> request,
                                        HttpSession session) {
        try {
            String userInput = request.get("userInput");
            if (userInput == null || userInput.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "ERROR",
                        "message", "사용자 입력이 누락되었습니다."
                ));
            }
            CustomerSessionDTO sessionDTO = (CustomerSessionDTO) session.getAttribute("loggedInCustomer");
            if (sessionDTO == null) {
                return ResponseEntity.status(401).body(Map.of(
                        "status", "ERROR",
                        "message", "로그인 정보 없음"
                ));
            }
            String userId = sessionDTO.getLoginId();
            Map<String, Object> result = aiOrderService.processUserMessage(userInput, userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "status", "ERROR",
                    "message", "서버 오류: " + e.getMessage()
            ));
        }
    }
}
