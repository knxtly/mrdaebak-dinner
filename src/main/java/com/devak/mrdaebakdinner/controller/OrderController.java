package com.devak.mrdaebakdinner.controller;

import com.devak.mrdaebakdinner.dto.*;
import com.devak.mrdaebakdinner.exception.InsufficientInventoryException;
import com.devak.mrdaebakdinner.service.OrderService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /* ============ Take order ============ */

    // 주문 페이지 GET 요청
    @GetMapping("/customer/orders/new")
    public String showOrderPage(@ModelAttribute OrderDTO orderDTO,
                                @ModelAttribute OrderItemDTO orderItemDTO) {
        return "customer/order";
    }

    // 주문 reset 요청
    @GetMapping("/customer/orders/new/reset")
    public String resetOrder(Model model) {
        model.addAttribute("orderDTO", new OrderDTO());
        model.addAttribute("orderItemDTO", new OrderItemDTO());
        return "customer/order";
    }

    // 주문 요청
    @PostMapping("/customer/orders/new")
    public String takeOrder(@Valid @ModelAttribute OrderDTO orderDTO,
                            BindingResult bindingResult,
                            @ModelAttribute OrderItemDTO orderItemDTO,
                            Model model,
                            RedirectAttributes redirectAttributes,
                            @SessionAttribute("loggedInCustomer") CustomerSessionDTO customerSessionDTO) {

        if (bindingResult.hasErrors()) {
            StringBuilder orderErrMsg = new StringBuilder();

            // 원하는 출력 순서
            List<String> fieldOrder = List.of("dinnerKind", "dinnerStyle", "deliveryAddress", "cardNumber");

            // fieldOrder 순서대로 정렬. 없는 필드면 맨 뒤로
            List<FieldError> sortedErrors = bindingResult.getFieldErrors().stream()
                    .sorted(Comparator.comparingInt(
                            e -> !fieldOrder.contains(e.getField()) ? Integer.MAX_VALUE : fieldOrder.indexOf(e.getField())
                    ))
                    .toList();

            for (FieldError fieldError : sortedErrors) {
                orderErrMsg.append(fieldError.getDefaultMessage()).append("<br>");
            }

            model.addAttribute("orderErrMsg", orderErrMsg.toString().trim());
            return "customer/order";
        }

        try {
            // 주문처리
            OrderHistoryDTO placedOrder = orderService.placeOrder(orderDTO, orderItemDTO, customerSessionDTO);
            // 결제
            makePayment();

            redirectAttributes.addFlashAttribute("placedOrder", placedOrder);
            return "redirect:/customer/orders/success";
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("loginErrMsg", e.getMessage());
            return "redirect:/customer/login";
        } catch (IllegalArgumentException e) {
            model.addAttribute("orderErrMsg", e.getMessage());
            return "customer/order";
        } catch (InsufficientInventoryException e) {
            model.addAttribute("itemErrMsg", e.getMessage());
            model.addAttribute("insufficientItems", e.getInsufficientItems());
            return "customer/order";
        }
    }

    private void makePayment() {
        return;
    }

    @GetMapping("/customer/orders/success")
    public String showOrderSuccess() {
        return "customer/order-success";
    }

    /* ============ Order History ============ */

    // 이전주문기록 조회 요청
    @GetMapping("/customer/orders/history")
    public String showCustomerOrderHistory(@SessionAttribute("loggedInCustomer") CustomerSessionDTO customerSessionDTO,
                                           Model model) {
        // 고객의 loginId로 order목록을 찾아서 보여주는 로직
        List<OrderHistoryDTO> orderList =
                orderService.findOrderHistoryByLoginId(customerSessionDTO.getLoginId());
        // "orderList"라는 속성으로 전달
        model.addAttribute("orderList", orderList);
        return "customer/order-history";
    }

    /* ============ Reorder ============ */

    // 재주문 요청
    @GetMapping("/customer/order/reorder/{orderId}")
    public String takeReorder(@PathVariable Long orderId,
                              Model model,
                              @SessionAttribute("loggedInCustomer") CustomerSessionDTO customerSessionDTO) {
        // 주문정보(OrderDTO, OrderItemDTO)를 order.html로 보냄
        model.addAttribute("orderDTO", orderService.buildOrderDTO(orderId));
        model.addAttribute("orderItemDTO", orderService.buildOrderItemDTO(orderId));
        return "customer/order";
    }

    /* ============ order Detail ============ */

    // 주문 상세 요청
    @GetMapping("/orders/detail/{orderId}")
    public String showOrderDetail(@PathVariable Long orderId,
                                  HttpSession session,
                                  Model model) {
        // orderId로부터 OrderHistoryDTO 불러오기
        OrderHistoryDTO order = orderService.findOrderHistoryByOrderId(orderId);
        OrderItemDTO orderItem = orderService.findOrderItem(orderId);

        // 세션에서 사용자 확인
        CustomerSessionDTO customer = (CustomerSessionDTO) session.getAttribute("loggedInCustomer");
        StaffSessionDTO staff = (StaffSessionDTO) session.getAttribute("loggedInStaff");

        // 고객인 경우
        if (customer != null) {
            // 자기 주문인지 체크
            if (!order.getCustomerLoginId().equals(customer.getLoginId())) {
                throw new IllegalArgumentException("다른 고객의 주문은 조회할 수 없습니다.");
            }
            model.addAttribute("order", order);
            model.addAttribute("orderItem", orderItem);
            return "customer/order-detail-customer"; // 고객용 뷰
        }

        // 직원인 경우
        if (staff != null) {
            String staffPos = staff.getPosition();
            if ("chef".equals(staffPos) || "delivery".equals(staffPos)) {
                model.addAttribute("order", order);
                model.addAttribute("orderItem", orderItem);
                return "staff/order-detail-staff"; // 직원용 뷰
            }
        }

        // 세션없음
        return "redirect:/";
    }

}
