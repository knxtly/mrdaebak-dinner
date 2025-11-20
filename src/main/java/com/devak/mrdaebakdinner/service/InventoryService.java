package com.devak.mrdaebakdinner.service;

import com.devak.mrdaebakdinner.dto.InventoryDTO;
import com.devak.mrdaebakdinner.entity.InventoryEntity;
import com.devak.mrdaebakdinner.mapper.InventoryMapper;
import com.devak.mrdaebakdinner.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public List<InventoryDTO> findAllInventory() {
        return inventoryRepository.findAllByOrderByItemIdAsc()
                .stream()
                .map(InventoryMapper::toInventoryDTO)
                .toList();
    }

    public void increaseCount(Long itemId, int amount) {
        InventoryEntity inventoryEntity = inventoryRepository.findByItemId(itemId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 item입니다."));
        inventoryEntity.setStockQuantity(inventoryEntity.getStockQuantity() + amount);
        inventoryRepository.save(inventoryEntity);
    }

    public void decreaseCount(Long itemId, int amount) {
        InventoryEntity inventoryEntity = inventoryRepository.findByItemId(itemId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 item입니다."));
        inventoryEntity.setStockQuantity(Math.max(0, inventoryEntity.getStockQuantity() - amount));
        inventoryRepository.save(inventoryEntity);
    }
}
