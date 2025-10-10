package com.devak.mrdaebakdinner.controller;

import com.devak.mrdaebakdinner.dto.*;
import com.devak.mrdaebakdinner.exception.IncorrectPasswordException;
import com.devak.mrdaebakdinner.service.InventoryService;
import com.devak.mrdaebakdinner.service.OrderService;
import com.devak.mrdaebakdinner.service.StaffService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Objects;

@Controller
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;
    private final InventoryService inventoryService;
    private final OrderService orderService;

    /* ============ Auth ============ */

    // Staff 기본화면 (로그인화면)
    @GetMapping("/staff")
    public String showStaffInterface(HttpSession session) {
        Object staff = session.getAttribute("loggedInStaff");
        if (staff != null) { // 이미 staff session이 있으면 바로 role에 맞는 화면으로
            return "redirect:/staff/" + ((StaffSessionDTO) staff).getPosition();
        }
        return "staff/staff";
    }

    // Staff login 요청 (PW만 입력)
    @PostMapping("/staff/login")
    public String loginStaff(@Valid @ModelAttribute StaffLoginDTO staffLoginDTO,
                             BindingResult bindingResult,
                             Model model,
                             RedirectAttributes redirectAttributes,
                             HttpSession session) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("loginErrMsg",
                    bindingResult.getFieldError("password").getDefaultMessage()
            );
            return "staff/staff";
        }

        try {
            // Customer 세션 있으면 삭제
            if (session.getAttribute("loggedInCustomer") != null) {
                session.removeAttribute("loggedInCustomer");
            }
            // 로그인 시도
            StaffSessionDTO staffSessionDTO = staffService.login(staffLoginDTO.getPassword());
            session.setAttribute("loggedInStaff", staffSessionDTO);
            return "redirect:/staff/chef";
        } catch (IncorrectPasswordException e) { // 로그인 실패
            model.addAttribute("loginErrMsg", e.getMessage());
            return "staff/staff";
        }
    }

    // 로그아웃 요청
    @GetMapping("/staff/logout")
    public String staffLogout(HttpSession session) {
        session.removeAttribute("loggedInStaff");
        return "redirect:/staff";
    }

    /* ============ Chef ============ */

    @GetMapping("/staff/chef")
    public String showChefPage(HttpSession session, Model model) {
        // loggedInStaff 세션이 있으면 value를 "chef"로 설정
        if (session.getAttribute("loggedInStaff") != null) {
            session.setAttribute("loggedInStaff", new StaffSessionDTO("chef"));
        }

        // 주문 상태가 'ORDERED' 또는 '요리중'인 주문 조회
        List<OrderHistoryDTO> chefOrderHistoryList = orderService.getChefOrders();
        model.addAttribute("chefOrderList", chefOrderHistoryList);

        return "staff/chef";
    }

    @PostMapping("/staff/chef/start")
    public String setStatusToCooking(@RequestParam Long orderId) {
        orderService.startCooking(orderId);
        return "redirect:/staff/chef";
    }

    @PostMapping("/staff/chef/complete")
    public String setStatusToCooked(@RequestParam Long orderId) {
        orderService.completeCooking(orderId);
        return "redirect:/staff/chef";
    }

    /* ============ Delivery ============ */

    @GetMapping("/staff/delivery")
    public String showDeliveryPage(HttpSession session, Model model) {
        // loggedInStaff 세션이 있으면 value를 "delivery"로 설정
        if (session.getAttribute("loggedInStaff") != null) {
            session.setAttribute("loggedInStaff", new StaffSessionDTO("delivery"));
        }

        // 주문 상태가 '배달대기' 또는 '배달중'인 주문 조회
        model.addAttribute("deliveryOrderList", orderService.getDeliveryOrders());
        // "요리중" 주문 조회
        model.addAttribute("cookingOrderList", orderService.getCookingOrders());

        return "staff/delivery";
    }

    @PostMapping("/staff/delivery/start")
    public String setStatusToDelivering(@RequestParam Long orderId) {
        orderService.startDelivery(orderId);
        return "redirect:/staff/delivery";
    }

    @PostMapping("/staff/delivery/complete")
    public String setStatusToDelivered(@RequestParam Long orderId) {
        orderService.completeDelivery(orderId);
        return "redirect:/staff/delivery";
    }

    /* ============ Inventory ============ */

    @GetMapping("/staff/inventory")
    public String showInventory(Model model) {
        model.addAttribute("inventoryList", inventoryService.findAllInventory());
        return "staff/inventory";
    }

    @PostMapping("/staff/inventory/increase")
    public String increaseStock(@RequestParam Long itemId,
                                @RequestParam int amount) {
        inventoryService.increaseCount(itemId, amount);
        return "redirect:/staff/inventory";
    }

    @PostMapping("/staff/inventory/decrease")
    public String decreaseStock(@RequestParam Long itemId,
                                @RequestParam int amount) {
        inventoryService.decreaseCount(itemId, amount);
        return "redirect:/staff/inventory";
    }
}
