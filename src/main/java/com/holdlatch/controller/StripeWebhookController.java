package com.holdlatch.controller;

import com.holdlatch.service.PaymentReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Stripe's notifications. There is no login here: the request is
 * trusted only if its signature verifies. The body is taken as the raw string
 * because the signature is computed over the exact bytes Stripe sent.
 */
@RestController
@RequestMapping("/api/webhooks")
public class StripeWebhookController {

    private final PaymentReconciliationService reconciliation;

    public StripeWebhookController(PaymentReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/stripe")
    public ResponseEntity<String> receive(@RequestBody String payload,
                                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        reconciliation.handleWebhook(payload, signature);
        return ResponseEntity.ok("received");
    }
}
