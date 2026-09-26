package com.holdlatch.service;

import com.holdlatch.dto.SettlementDtos.OrderItemView;
import com.holdlatch.dto.SettlementDtos.OrderView;
import com.holdlatch.exception.NotFoundException;
import com.holdlatch.model.persistence.BookingOrderRecord;
import com.holdlatch.repository.PersistentBookingItemRepository;
import com.holdlatch.repository.PersistentBookingRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {

    private final PersistentBookingRepository orders;
    private final PersistentBookingItemRepository items;

    OrderQueryService(PersistentBookingRepository orders, PersistentBookingItemRepository items) {
        this.orders = orders;
        this.items = items;
    }

    @Transactional(readOnly = true)
    public List<OrderView> list(UUID userId, int page, int size) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size)).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public OrderView get(UUID userId, UUID orderId) {
        // Someone else's order looks exactly like a missing one.
        return orders.findById(orderId).filter(o -> o.getUserId().equals(userId)).map(this::view)
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "Order not found."));
    }

    private OrderView view(BookingOrderRecord order) {
        List<OrderItemView> lines = items.findByOrderId(order.getId()).stream()
                .map(i -> new OrderItemView(i.getSectionId(), i.getSeatId(), i.getQuantity(), i.getUnitPriceCents()))
                .toList();
        return new OrderView(order.getId(), order.getEventId(), order.getStatus(), order.getTotalCents(), order.getCurrency(),
                order.getCreatedAt(), lines);
    }
}
