package com.holdlatch.controller;

import com.holdlatch.dto.SettlementDtos.CheckoutRequest;
import com.holdlatch.dto.SettlementDtos.CheckoutResponse;
import com.holdlatch.dto.SettlementDtos.OrderView;
import com.holdlatch.dto.SettlementDtos.PaymentStatusResponse;
import com.holdlatch.security.Principals;
import com.holdlatch.service.CheckoutService;
import com.holdlatch.service.IdempotencyService;
import com.holdlatch.service.OrderQueryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BookingSettlementController {

    private static final int MAX_PAGE_SIZE = 50;

    private final CheckoutService checkout;
    private final OrderQueryService orders;

    public BookingSettlementController(CheckoutService checkout, OrderQueryService orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    /** Starts payment for a hold. Safe to retry with the same Idempotency-Key: the same answer comes back and nothing is charged twice. */
    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> startCheckout(@AuthenticationPrincipal Jwt jwt,
                                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                          @Valid @RequestBody CheckoutRequest request) {
        IdempotencyService.Result<CheckoutResponse> result = checkout.checkout(Principals.userId(jwt), idempotencyKey, request.holdToken());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/checkout/{paymentId}")
    public PaymentStatusResponse paymentStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        return checkout.status(Principals.userId(jwt), paymentId);
    }

    @GetMapping("/orders")
    public List<OrderView> myOrders(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return orders.list(Principals.userId(jwt), Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    @GetMapping("/orders/{orderId}")
    public OrderView order(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return orders.get(Principals.userId(jwt), orderId);
    }
}
