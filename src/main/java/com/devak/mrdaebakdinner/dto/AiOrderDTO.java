package com.devak.mrdaebakdinner.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiOrderDTO {
    private String menu;                // VALENTINE, FRENCH, ENGLISH, CHAMPAGNE
    private String style;               // SIMPLE, GRAND, DELUXE
    private Map<String, Integer> items; // 주문 아이템과 수량
    private String deliveryAddress;     // 배송 주소
    private String cardNumber;          // 결제 카드 번호
}