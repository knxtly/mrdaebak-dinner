package com.devak.mrdaebakdinner.service;

import com.devak.mrdaebakdinner.dto.*;
import com.devak.mrdaebakdinner.entity.*;
import com.devak.mrdaebakdinner.entity.OrderEntity;
import com.devak.mrdaebakdinner.exception.InsufficientInventoryException;
import com.devak.mrdaebakdinner.mapper.CustomerMapper;
import com.devak.mrdaebakdinner.mapper.OrderMapper;
import com.devak.mrdaebakdinner.repository.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CustomerRepository customerRepository;
    private final ItemRepository itemRepository;
    private final InventoryRepository inventoryRepository;

    /* ============ 내부에서만 쓸 함수 ============ */

    private CustomerEntity findCustomer(String loginId) {
        return customerRepository.findByLoginId(loginId)
                .orElseThrow(() -> new IllegalArgumentException("해당 고객이 없습니다."));
    }

    private OrderEntity findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문이 존재하지 않습니다."));
    }

    /* ============ 주문조회를 위한 함수 ============ */

    public List<OrderHistoryDTO> findOrderHistoryByLoginId(String loginId) {
        // 고객의 id로 해당 고객의 주문만 찾기
        List<OrderEntity> orderEntityList =
                orderRepository.findAllByCustomerId(findCustomer(loginId).getId());

        return orderEntityList.stream()
                .map(OrderMapper::toOrderHistoryDTO)
                .toList();
    }

    public OrderHistoryDTO findOrderHistoryByOrderId(Long orderId) {
        // orderId로 orderEntity 가져오기
        OrderEntity order = findOrder(orderId);
        // OrderHistoryDTO로 변경하여 반환
        return OrderMapper.toOrderHistoryDTO(order);
    }

    public OrderItemDTO findOrderItem(Long orderId) {
        // 한 orderId에 담긴 orderItems 가져오기
        List<OrderItemEntity> orderItems = orderItemRepository.findAllByOrderId(orderId);

        // orderItems를 OrderItemDTO의 형(Map<String, Integer>)으로 변환
        // (key: itemName, value: quantity)
        Map<String, Integer> itemMap = orderItems.stream()
                .collect(Collectors.toMap(
                        oi -> oi.getItem().getName(),
                        OrderItemEntity::getQuantity
                ));

        OrderItemDTO dto = new OrderItemDTO();
        dto.setOrderItems(itemMap);
        return dto;
    }

    /* ============ 주문 함수 ============ */

    @Transactional
    public OrderHistoryDTO placeOrder(OrderDTO orderDTO,
                                      OrderItemDTO orderItemDTO,
                                      CustomerSessionDTO customerSessionDTO) {
        // 주문한 customer 검증
        if (customerSessionDTO == null) {
            throw new IllegalStateException("로그인 세션이 없습니다. 다시 로그인해주세요");
        }
        CustomerEntity customerEntity = customerRepository.findByLoginId(customerSessionDTO.getLoginId())
                .orElseThrow(() -> new IllegalStateException("고객 정보가 없습니다."));

        // CHAMPAGNE + SIMPLE 스타일은 거절
        if (OrderDnrKind.CHAMPAGNE == orderDTO.getDinnerKind()
                && OrderDnrStyle.SIMPLE == orderDTO.getDinnerStyle()) {
            throw new IllegalArgumentException("샴페인 축제 디너와 SIMPLE 스타일은 같이 주문할 수 없습니다.");
        }
        // 사실상 없는 주문 (모든 아이템 0개) 거절
        if (orderItemDTO.getOrderItems()
                .values()
                .stream()
                .mapToInt(Integer::intValue)
                .sum() == 0) {
            throw new IllegalArgumentException("모든 아이템이 0개인 주문을 요청할 수 없습니다.");
        }

        OrderEntity order = OrderMapper.toOrderEntity(orderDTO, customerEntity);    // 주문될 orderEntity 생성
        List<OrderItemEntity> orderItemEntityList = new ArrayList<>();  // OrderItem 조사해서 담을 변수
        List<String> insufficientItems = new ArrayList<>(); // 부족한 재고의 이름을 모은 리스트
        int totalPrice = 0; // 총 가격(totalPrice)을 담을 변수

        // OrderItemDTO 내부 (주문한 item) 반복
        for (Map.Entry<String, Integer> entry : orderItemDTO.getOrderItems().entrySet()) {
            String orderItemName = entry.getKey();
            int quantity = entry.getValue();

            // 없는 item인지 검사
            ItemEntity item = itemRepository.findByName(orderItemName)
                    .orElseThrow(() -> new IllegalArgumentException("없는 item입니다." + orderItemName));

            // item이 재고에 등록됐는지 검사
            InventoryEntity inventoryEntity = inventoryRepository.findByItemId(item.getId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "재고에 등록되지 않은 item입니다." + orderItemName));

            // 부족한 재고 이름은 리스트에 추가
            if (inventoryEntity.getStockQuantity() < quantity) {
                insufficientItems.add(orderItemName + " (요청: " + quantity +
                        ", 보유: " + inventoryEntity.getStockQuantity() + ")\n");
                continue;
            }

            // 재고가 충분하면 quantity만큼 decrease
            inventoryEntity.setStockQuantity(inventoryEntity.getStockQuantity() - quantity);

            // totalPrice += 단가(unitPrice) * quantity
            totalPrice = totalPrice + item.getUnitPrice() * quantity;

            // OrderItemEntity 구성 후 저장
            OrderItemEntity orderItemEntity = new OrderItemEntity();
            // OrderItemId(PK)를 직접 생성해 넣음 -> JPA가 @MapsId 때문에 id를 Long으로 채우려고 하기 때문
            OrderItemId orderItemId = new OrderItemId(order.getId(), item.getId());
            orderItemEntity.setId(orderItemId);
            orderItemEntity.setOrder(order); // 나머지는 그냥 set해도 됨
            orderItemEntity.setItem(item);
            orderItemEntity.setQuantity(quantity);

            orderItemEntityList.add(orderItemEntity);
        }

        // 재고가 부족한 게 있었다면 예외 호출
        if (!insufficientItems.isEmpty()) {
            throw new InsufficientInventoryException("재고가 부족합니다", insufficientItems);
        }

        // 할인 규칙 적용: VIP였다면 totalPrice 10퍼센트 할인
        if (customerEntity.getMembershipLevel().equals("VIP")) {
            totalPrice = ((int) (totalPrice * 0.9)) / 10 * 10;
        }

        order.setTotalPrice(totalPrice); // totalPrice 반영
        orderRepository.save(order); // 주문될 order 테이블에 반영
        orderItemRepository.saveAll(orderItemEntityList); // orderItemEntity 한번에 저장 (bulk save)
        customerEntity.setOrderCount(customerEntity.getOrderCount() + 1); // customerEntity의 orderCount 1 증가
        if (customerEntity.getOrderCount() >= 5)
            customerEntity.setMembershipLevel("VIP"); // orderCount 5 이상이면 VIP로 승격

        return OrderMapper.toOrderHistoryDTO(order);

        // 영속 상태이기 때문에 아래 변경사항은 자동 반영됨
        // customerEntity: orderCount 1증가 + VIP승격
        // inventoryEntity: stockQuantity 주문량만큼 감소
    }

    /* ============ 재주문을 위한 함수 ============ */

    public OrderDTO buildOrderDTO(Long orderId) {
        return OrderMapper.toOrderDTO(findOrder(orderId));
    }

    public OrderItemDTO buildOrderItemDTO(Long orderId) {
        // order_item테이블에서 orderId가 일치하는 레코드 모두 가져와서
        // OrderItemDTO의 형(Map<String, Integer>)으로 변환
        Map<String, Integer> itemMap =
                orderItemRepository.findAllByOrderId(orderId)
                        .stream()
                        .collect(Collectors.toMap(
                                oi -> oi.getItem().getName(), // Key = Item Name
                                OrderItemEntity::getQuantity // Value = quantity
                        ));

        OrderItemDTO dto = new OrderItemDTO();
        dto.setOrderItems(itemMap);
        return dto;
    }

    public CustomerSessionDTO getFreshCustomerSessionDTO(String loginId) {
        CustomerEntity customerEntity = customerRepository.findByLoginId(loginId)
                .orElseThrow(() -> new IllegalArgumentException("해당 고객이 없습니다."));
        return CustomerMapper.toCustomerSessionDTO(customerEntity);
    }

    /* ============ 직원이 조회할 주문 위한 함수 ============ */
    // Chef가 볼 주문 조회
    public List<OrderHistoryDTO> getChefOrders() {
        // 주문 상태가 "ORDERED" 또는 "COOKING"인 주문만 조회
        return orderRepository.findByStatusIn(Arrays.asList(OrderStatus.ORDERED, OrderStatus.COOKING))
                .stream()
                .map(OrderMapper::toOrderHistoryDTO)
                .toList();
    }

    // Delivery가 볼 주문 조회
    public List<OrderHistoryDTO> getDeliveryOrders() {
        // 주문 상태가 "COOKED" 또는 "DELIVERING"인 주문만 조회
        return orderRepository.findByStatusIn(Arrays.asList(OrderStatus.COOKED, OrderStatus.DELIVERING))
                .stream()
                .map(OrderMapper::toOrderHistoryDTO)
                .toList();
    }

    // 요리중인 주문만 조회 (배달직원에게도 보여줄 테이블용)
    public List<OrderHistoryDTO> getCookingOrders() {
        return orderRepository.findByStatusIn(Arrays.asList(OrderStatus.COOKING))
                .stream()
                .map(OrderMapper::toOrderHistoryDTO)
                .toList();
    }

    /* ============ 주문시작/완료, 배달시작/완료 처리 ============ */

    @Transactional
    public void startCooking(Long orderId) {
        OrderEntity oe = findOrder(orderId);
        // Status가 "ORDERED"일 때만 "COOKING"으로 변경
        if (oe.getStatus() == OrderStatus.ORDERED) oe.setStatus(OrderStatus.COOKING);
    }

    @Transactional
    public void completeCooking(Long orderId) {
        OrderEntity oe = findOrder(orderId);
        // Status가 "COOKING"일 때만 "COOKED"으로 변경
        if (oe.getStatus() == OrderStatus.COOKING) oe.setStatus(OrderStatus.COOKED);
    }

    @Transactional
    public void startDelivery(Long orderId) {
        OrderEntity oe = findOrder(orderId);
        // Status가 "COOKED"일 때만 "DELIVERING"으로 변경
        if (oe.getStatus() == OrderStatus.COOKED) oe.setStatus(OrderStatus.DELIVERING);
    }

    @Transactional
    public void completeDelivery(Long orderId) {
        OrderEntity oe = findOrder(orderId);
        // Status가 "DELIVERING"일 때만 "DELIVERED"으로 변경
        if (oe.getStatus() == OrderStatus.DELIVERING) {
            oe.setDeliveryTime(OffsetDateTime.now(ZoneOffset.ofHours(9)));
            oe.setStatus(OrderStatus.DELIVERED);
        }
    }
}
