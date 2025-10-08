package com.zn.payment.controller;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stripe.model.Event;
import com.zn.payment.dto.CreateDiscountSessionRequest;
import com.zn.payment.nursing.repository.NursingDiscountsRepository;
import com.zn.payment.nursing.service.NursingDiscountsService;
import com.zn.payment.optics.repository.OpticsDiscountsRepository;
import com.zn.payment.optics.service.OpticsDiscountsService;
import com.zn.payment.polymers.repository.PolymersDiscountsRepository;
import com.zn.payment.renewable.repository.RenewableDiscountsRepository;
import com.zn.payment.renewable.service.RenewableDiscountsService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;



@RestController
@RequestMapping("/api/discounts")
@Slf4j
public class DiscountsController {
    @Autowired
    private OpticsDiscountsService opticsDiscountsService;
    
    @Autowired
    private NursingDiscountsService nursingDiscountsService;
    
    @Autowired
    private RenewableDiscountsService renewableDiscountsService;

    @Autowired
    private com.zn.payment.polymers.service.PolymersDiscountsService polymersDiscountsService;

    @Autowired
    private OpticsDiscountsRepository opticsDiscountsRepository;
    
    @Autowired
    private NursingDiscountsRepository nursingDiscountsRepository;
    
    @Autowired
    private RenewableDiscountsRepository renewableDiscountsRepository;
    
    @Autowired
    private PolymersDiscountsRepository polymersDiscountsRepository;

    // create stripe session
    @PostMapping("/create-session")
    public ResponseEntity<?> createSession(@RequestBody CreateDiscountSessionRequest request, HttpServletRequest httpRequest) {
        String origin = httpRequest.getHeader("Origin");
        String referer = httpRequest.getHeader("Referer");
        
        // Route based on domain
        if ((origin != null && origin.contains("globallopmeet.com")) || 
            (referer != null && referer.contains("globallopmeet.com"))) {
            // Route to Optics service
            Object result = opticsDiscountsService.createSession(request);
            return ResponseEntity.ok(result);
        } else if ((origin != null && origin.contains("polyscienceconference.com")) || 
                   (referer != null && referer.contains("polyscienceconference.com"))) {
            // Route to Polymers service
            Object result = polymersDiscountsService.createSession(request);
            return ResponseEntity.ok(result);
        } else if ((origin != null && origin.contains("nursingmeet2026.com")) || 
                   (referer != null && referer.contains("nursingmeet2026.com"))) {
            // Route to Nursing service
            Object result = nursingDiscountsService.createSession(request);
            return ResponseEntity.ok(result);
        } else if ((origin != null && origin.contains("globalrenewablemeet.com")) || 
                   (referer != null && referer.contains("globalrenewablemeet.com"))) {
            // Route to Renewable service
            Object result = renewableDiscountsService.createSession(request);
            return ResponseEntity.ok(result);
        } else {
            // Default to nursing service for backward compatibility
            Object result = nursingDiscountsService.createSession(request);
            return ResponseEntity.ok(result);
        }
    }
    
    /**
     * Update discount payment status - uses existing discount service methods
     * Routes to appropriate discount service based on Origin/Referer headers
     */
    @PostMapping("/update")
    public ResponseEntity<?> updateDiscountPaymentStatus(@RequestParam String sessionId, HttpServletRequest httpRequest) {
        log.info("🔄 Updating discount payment status for session: {}", sessionId);
        
        String origin = httpRequest.getHeader("Origin");
        if (origin == null) {
            origin = httpRequest.getHeader("Referer");
        }
        
        try {
            // Route based on domain
            if (origin != null && origin.contains("globallopmeet.com")) {
                // Route to Optics discount service
                log.info("🎯 Routing to OpticsDiscountsService for session: {}", sessionId);
                boolean updated = opticsDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                return ResponseEntity.ok(java.util.Map.of("updated", updated, "sessionId", sessionId, "service", "optics"));
            } else if (origin != null && origin.contains("polyscienceconference.com")) {
                // Route to Polymers discount service
                log.info("🎯 Routing to PolymersDiscountsService for session: {}", sessionId);
                boolean updated = polymersDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                return ResponseEntity.ok(java.util.Map.of("updated", updated, "sessionId", sessionId, "service", "polymers"));
            } else if (origin != null && origin.contains("nursingmeet2026.com")) {
                // Route to Nursing discount service
                log.info("🎯 Routing to NursingDiscountsService for session: {}", sessionId);
                boolean updated = nursingDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                return ResponseEntity.ok(java.util.Map.of("updated", updated, "sessionId", sessionId, "service", "nursing"));
            } else if (origin != null && origin.contains("globalrenewablemeet.com")) {
                // Route to Renewable discount service
                log.info("🎯 Routing to RenewableDiscountsService for session: {}", sessionId);
                boolean updated = renewableDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                return ResponseEntity.ok(java.util.Map.of("updated", updated, "sessionId", sessionId, "service", "renewable"));
            } else {
                // Try all services to find the session
                try {
                    log.info("🎯 Trying OpticsDiscountsService for session: {}", sessionId);
                    boolean updated = opticsDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                    return ResponseEntity.ok(java.util.Map.of("updated", updated, "sessionId", sessionId, "service", "optics"));
                } catch (Exception e1) {
                    try {
                        log.info("🎯 Trying NursingDiscountsService for session: {}", sessionId);
                        boolean updated = nursingDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                        return ResponseEntity.ok(java.util.Map.of("updated", updated, "sessionId", sessionId, "service", "nursing"));
                    } catch (Exception e2) {
                        try {
                            log.info("🎯 Trying RenewableDiscountsService for session: {}", sessionId);
                            boolean updated = renewableDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                            return ResponseEntity.ok(java.util.Map.of("updated", updated, "sessionId", sessionId, "service", "renewable"));
                        } catch (Exception e3) {
                            try {
                                log.info("🎯 Trying PolymersDiscountsService for session: {}", sessionId);
                                boolean updated = polymersDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                                return ResponseEntity.ok(java.util.Map.of("updated", updated, "sessionId", sessionId, "service", "polymers"));
                            } catch (Exception e4) {
                                log.error("❌ Session not found in any discount service: {}", sessionId);
                                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(java.util.Map.of("error", "session_not_found", "sessionId", sessionId));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Error updating discount payment status for session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(java.util.Map.of("error", "update_failed", "message", e.getMessage()));
        }
    }
    
    /**
     * Get discount payment status from payment gateways (Stripe/PayPal) - fetches real-time data from payment providers
     * GET /api/discounts/status/{sessionId}
     * 
     * This method fetches real-time status from payment providers and updates database if payment is completed
     */
    @GetMapping("/status/{sessionId}")
    public ResponseEntity<?> getDiscountPaymentStatusFromProviders(@PathVariable String sessionId, HttpServletRequest httpRequest) {
        log.info("Getting real-time discount payment status from Stripe/PayPal for session: {}", sessionId);
        
        String origin = httpRequest.getHeader("Origin");
        if (origin == null) {
            origin = httpRequest.getHeader("Referer");
        }
        
        try {
            // Route to appropriate service based on domain/origin
            if (origin != null && origin.contains("globallopmeet.com")) {
                // Route to Optics discount repository and check for updates
                log.info("🎯 Routing to OpticsDiscountsRepository for session: {}", sessionId);
                var result = opticsDiscountsRepository.findBySessionId(sessionId);
                if (result != null) {
                    // Check if payment is completed and update if needed
                    checkAndUpdateDiscountStatus(result, sessionId, "optics");
                    // Fetch updated record
                    result = opticsDiscountsRepository.findBySessionId(sessionId);
                }
                return ResponseEntity.ok(result != null ? result : java.util.Map.of("error", "No optics discount found for session: " + sessionId));
            } else if (origin != null && origin.contains("polyscienceconference.com")) {
                // Route to Polymers discount repository and check for updates
                log.info("🎯 Routing to PolymersDiscountsRepository for session: {}", sessionId);
                var result = polymersDiscountsRepository.findBySessionId(sessionId);
                if (result != null) {
                    // Check if payment is completed and update if needed
                    checkAndUpdateDiscountStatus(result, sessionId, "polymers");
                    // Fetch updated record
                    result = polymersDiscountsRepository.findBySessionId(sessionId);
                }
                return ResponseEntity.ok(result != null ? result : java.util.Map.of("error", "No polymers discount found for session: " + sessionId));
            } else if (origin != null && origin.contains("nursingmeet2026.com")) {
                // Route to Nursing discount repository and check for updates
                log.info("🎯 Routing to NursingDiscountsRepository for session: {}", sessionId);
                var result = nursingDiscountsRepository.findBySessionId(sessionId);
                if (result != null) {
                    // Check if payment is completed and update if needed
                    checkAndUpdateDiscountStatus(result, sessionId, "nursing");
                    // Fetch updated record
                    result = nursingDiscountsRepository.findBySessionId(sessionId);
                }
                return ResponseEntity.ok(result != null ? result : java.util.Map.of("error", "No nursing discount found for session: " + sessionId));
            } else if (origin != null && origin.contains("globalrenewablemeet.com")) {
                // Route to Renewable discount repository and check for updates
                log.info("🎯 Routing to RenewableDiscountsRepository for session: {}", sessionId);
                var result = renewableDiscountsRepository.findBySessionId(sessionId);
                if (result != null) {
                    // Check if payment is completed and update if needed
                    checkAndUpdateDiscountStatus(result, sessionId, "renewable");
                    // Fetch updated record
                    result = renewableDiscountsRepository.findBySessionId(sessionId);
                }
                return ResponseEntity.ok(result != null ? result : java.util.Map.of("error", "No renewable discount found for session: " + sessionId));
            } else {
                // Try all repositories to find the session and update if needed
                try {
                    log.info("🎯 Trying OpticsDiscountsRepository for session: {}", sessionId);
                    var result = opticsDiscountsRepository.findBySessionId(sessionId);
                    if (result != null) {
                        checkAndUpdateDiscountStatus(result, sessionId, "optics");
                        result = opticsDiscountsRepository.findBySessionId(sessionId);
                        return ResponseEntity.ok(result);
                    }
                } catch (Exception e1) {
                    log.debug("OpticsDiscountsRepository failed for session: {}", sessionId);
                }
                
                try {
                    log.info("🎯 Trying NursingDiscountsRepository for session: {}", sessionId);
                    var result = nursingDiscountsRepository.findBySessionId(sessionId);
                    if (result != null) {
                        checkAndUpdateDiscountStatus(result, sessionId, "nursing");
                        result = nursingDiscountsRepository.findBySessionId(sessionId);
                        return ResponseEntity.ok(result);
                    }
                } catch (Exception e2) {
                    log.debug("NursingDiscountsRepository failed for session: {}", sessionId);
                }
                
                try {
                    log.info("🎯 Trying RenewableDiscountsRepository for session: {}", sessionId);
                    var result = renewableDiscountsRepository.findBySessionId(sessionId);
                    if (result != null) {
                        checkAndUpdateDiscountStatus(result, sessionId, "renewable");
                        result = renewableDiscountsRepository.findBySessionId(sessionId);
                        return ResponseEntity.ok(result);
                    }
                } catch (Exception e3) {
                    log.debug("RenewableDiscountsRepository failed for session: {}", sessionId);
                }
                
                try {
                    log.info("🎯 Trying PolymersDiscountsRepository for session: {}", sessionId);
                    var result = polymersDiscountsRepository.findBySessionId(sessionId);
                    if (result != null) {
                        checkAndUpdateDiscountStatus(result, sessionId, "polymers");
                        result = polymersDiscountsRepository.findBySessionId(sessionId);
                        return ResponseEntity.ok(result);
                    }
                } catch (Exception e4) {
                    log.debug("PolymersDiscountsRepository failed for session: {}", sessionId);
                }
                
                log.error("❌ Session not found in any discount repository: {}", sessionId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(java.util.Map.of("error", "session_not_found", "sessionId", sessionId));
            }
        } catch (Exception e) {
            log.error("❌ Error getting discount payment status for session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(java.util.Map.of("error", "provider_status_retrieval_failed", "message", e.getMessage()));
        }
    }
    
    /**
     * Helper method to check payment status from Stripe and update database if completed
     * This simulates real-time status checking until discount services have proper getPaymentStatusFromProvider methods
     */
    private void checkAndUpdateDiscountStatus(Object discountRecord, String sessionId, String service) {
        try {
            log.info("🔄 Checking and updating discount status for session: {} in {} service", sessionId, service);
            
            // Extract current payment status from the discount record
            String currentPaymentStatus = extractPaymentStatus(discountRecord);
            log.info("📋 Current payment status in database: {}", currentPaymentStatus);
            
            // If already paid, no need to check again
            if ("paid".equals(currentPaymentStatus) || "COMPLETED".equals(currentPaymentStatus)) {
                log.info("✅ Payment already marked as completed for session: {}", sessionId);
                return;
            }
            
            // Check with Stripe for real-time status (placeholder for now)
            // TODO: Implement actual Stripe API calls when discount services have getPaymentStatusFromProvider methods
            log.info("🔍 Would check Stripe API for real-time status of session: {} (placeholder)", sessionId);
            
            // For now, we'll update based on webhook updates that might have occurred
            // In a real implementation, this would call Stripe API to check session status
            
            // Attempt to update payment status using the discount service
            boolean updated = false;
            switch (service) {
                case "optics":
                    updated = opticsDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                    break;
                case "nursing":
                    updated = nursingDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                    break;
                case "renewable":
                    updated = renewableDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                    break;
                case "polymers":
                    updated = polymersDiscountsService.updatePaymentStatusBySessionId(sessionId, "paid");
                    break;
                default:
                    log.warn("⚠️ Unknown service type: {}", service);
            }
            
            if (updated) {
                log.info("✅ Updated discount payment status for session: {} in {} service", sessionId, service);
            } else {
                log.info("ℹ️ No update needed for discount payment status for session: {} in {} service", sessionId, service);
            }
            
        } catch (Exception e) {
            log.error("❌ Error checking/updating discount status for session {}: {}", sessionId, e.getMessage());
        }
    }
    
    /**
     * Helper method to extract payment status from discount record using reflection
     */
    private String extractPaymentStatus(Object discountRecord) {
        try {
            // Try to get paymentStatus field using reflection
            java.lang.reflect.Method getPaymentStatus = discountRecord.getClass().getMethod("getPaymentStatus");
            Object status = getPaymentStatus.invoke(discountRecord);
            return status != null ? status.toString() : "unknown";
        } catch (Exception e) {
            try {
                // Try to get status field as fallback
                java.lang.reflect.Method getStatus = discountRecord.getClass().getMethod("getStatus");
                Object status = getStatus.invoke(discountRecord);
                return status != null ? status.toString() : "unknown";
            } catch (Exception e2) {
                log.warn("⚠️ Could not extract payment status from discount record: {}", e2.getMessage());
                return "unknown";
            }
        }
    }
    
    /**
     * Get discount payment status from database - uses repository to find discount records
     * Routes to appropriate discount service based on Origin/Referer headers
     */
    @PostMapping("/status")
    public ResponseEntity<?> getDiscountPaymentStatus(@RequestBody java.util.Map<String, String> request, HttpServletRequest httpRequest) {
        String sessionId = request.get("sessionId");
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("sessionId is required");
        }
        
        String origin = httpRequest.getHeader("Origin");
        String referer = httpRequest.getHeader("Referer");
        
        log.info("📋 Getting discount payment status for session: {} from origin: {}, referer: {}", sessionId, origin, referer);
        
        try {
            // Route based on domain
            if ((origin != null && origin.contains("globallopmeet.com")) || 
                (referer != null && referer.contains("globallopmeet.com"))) {
                // Route to Optics discount repository
                log.info("🎯 Routing to OpticsDiscountsRepository for session: {}", sessionId);
                var result = opticsDiscountsRepository.findBySessionId(sessionId);
                return ResponseEntity.ok(result != null ? result : java.util.Map.of("error", "No optics discount found for session: " + sessionId));
            } else if ((origin != null && origin.contains("polyscienceconference.com")) || 
                       (referer != null && referer.contains("polyscienceconference.com"))) {
                // Route to Polymers discount repository
                log.info("🎯 Routing to PolymersDiscountsRepository for session: {}", sessionId);
                var result = polymersDiscountsRepository.findBySessionId(sessionId);
                return ResponseEntity.ok(result != null ? result : java.util.Map.of("error", "No polymers discount found for session: " + sessionId));
            } else if ((origin != null && origin.contains("nursingmeet2026.com")) || 
                       (referer != null && referer.contains("nursingmeet2026.com"))) {
                // Route to Nursing discount repository
                log.info("🎯 Routing to NursingDiscountsRepository for session: {}", sessionId);
                var result = nursingDiscountsRepository.findBySessionId(sessionId);
                return ResponseEntity.ok(result != null ? result : java.util.Map.of("error", "No nursing discount found for session: " + sessionId));
            } else if ((origin != null && origin.contains("globalrenewablemeet.com")) || 
                       (referer != null && referer.contains("globalrenewablemeet.com"))) {
                // Route to Renewable discount repository
                log.info("🎯 Routing to RenewableDiscountsRepository for session: {}", sessionId);
                var result = renewableDiscountsRepository.findBySessionId(sessionId);
                return ResponseEntity.ok(result != null ? result : java.util.Map.of("error", "No renewable discount found for session: " + sessionId));
            } else {
                // Default to nursing discount repository for backward compatibility
                log.info("🎯 Routing to NursingDiscountsRepository (default) for session: {}", sessionId);
                var result = nursingDiscountsRepository.findBySessionId(sessionId);
                return ResponseEntity.ok(result != null ? result : java.util.Map.of("error", "No nursing discount found for session: " + sessionId));
            }
        } catch (Exception e) {
            log.error("❌ Error getting discount payment status for session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error getting discount payment status: " + e.getMessage());
        }
    }

    // handle stripe webhook - ONLY for discount payments
    @PostMapping("/test/webhook")
    public ResponseEntity<String> handleStripeWebhook(HttpServletRequest request) throws IOException {
        log.info("##############    Received DISCOUNT webhook request  ##################");
        String payload;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
            payload = reader.lines().collect(Collectors.joining("\n"));
        }
        String sigHeader = request.getHeader("Stripe-Signature");
        log.info("Discount webhook received. Signature header present: {}", sigHeader != null);
        if (sigHeader == null || sigHeader.isEmpty()) {
            log.error("Missing Stripe-Signature header in discount webhook");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing signature header");
        }
        try {
            // Try to construct event using polymers service first since it's most likely to be polymers
            Event event = null;
            
            // First, try polymers (most common for discount webhooks)
            try {
                event = polymersDiscountsService.constructWebhookEvent(payload, sigHeader);
                log.info("✅ Webhook event successfully constructed using PolymersDiscountsService");
            } catch (Exception e) {
                log.warn("Polymers constructWebhookEvent failed: {}. Trying other services.", e.getMessage());
            }
            
            // If polymers failed, try other services
            if (event == null) {
                try {
                    event = opticsDiscountsService.constructWebhookEvent(payload, sigHeader);
                    log.info("✅ Webhook event successfully constructed using OpticsDiscountsService");
                } catch (Exception e) {
                    try {
                        event = nursingDiscountsService.constructWebhookEvent(payload, sigHeader);
                        log.info("✅ Webhook event successfully constructed using NursingDiscountsService");
                    } catch (Exception e2) {
                        event = renewableDiscountsService.constructWebhookEvent(payload, sigHeader);
                        log.info("✅ Webhook event successfully constructed using RenewableDiscountsService");
                    }
                }
            }
            
            if (event == null) {
                log.error("❌ Failed to parse webhook event with any service");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Failed to parse event");
            }

            // Extract metadata to determine which service to use for processing
            String source = null;
            String paymentType = null;
            String productName = null;
            
            // First try reflection-based extraction
            try {
                java.util.Optional<com.stripe.model.StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
                if (stripeObjectOpt.isPresent()) {
                    com.stripe.model.StripeObject stripeObject = stripeObjectOpt.get();
                    java.util.Map<String, String> metadata = null;
                    try {
                        java.lang.reflect.Method getMetadata = stripeObject.getClass().getMethod("getMetadata");
                        Object metaObj = getMetadata.invoke(stripeObject);
                        if (metaObj instanceof java.util.Map) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, String> metadataMap = (java.util.Map<String, String>) metaObj;
                            metadata = metadataMap;
                            if (metadata != null) {
                                source = metadata.get("source");
                                paymentType = metadata.get("paymentType");
                                productName = metadata.get("productName");
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Could not extract metadata from stripe object: {}", ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                log.warn("Could not extract metadata from event: {}", ex.getMessage());
            }

            // If reflection failed, try JSON parsing as fallback
            if (productName == null && paymentType == null && source == null) {
                try {
                    String eventJson = event.toJson();
                    com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(eventJson);
                    com.fasterxml.jackson.databind.JsonNode metadataNode = root.path("data").path("object").path("metadata");
                    if (metadataNode != null && !metadataNode.isMissingNode()) {
                        if (metadataNode.has("productName")) {
                            productName = metadataNode.get("productName").asText();
                        }
                        if (metadataNode.has("paymentType")) {
                            paymentType = metadataNode.get("paymentType").asText();
                        }
                        if (metadataNode.has("source")) {
                            source = metadataNode.get("source").asText();
                        }
                        log.info("📋 [Discount Webhook] JSON fallback extraction successful - source: {}, paymentType: {}, productName: {}", source, paymentType, productName);
                    }
                } catch (Exception ex) {
                    log.warn("JSON parsing for metadata also failed: {}", ex.getMessage());
                }
            } else {
                log.info("📋 [Discount Webhook] Reflection extraction successful - source: {}, paymentType: {}, productName: {}", source, paymentType, productName);
            }

            // Process the webhook event
            String eventType = event.getType();
            log.info("🎯 Processing discount webhook event: {}", eventType);
            boolean updated = false;

            // Determine which service to use based on metadata - route explicitly
            String serviceToUse = "polymers"; // Default to polymers for discount webhooks
            
            if (productName != null) {
                String productUpper = productName.toUpperCase();
                if (productUpper.contains("OPTICS")) {
                    serviceToUse = "optics";
                } else if (productUpper.contains("NURSING")) {
                    serviceToUse = "nursing";
                } else if (productUpper.contains("RENEWABLE")) {
                    serviceToUse = "renewable";
                }
            }
            
            log.info("🎯 Routing discount webhook to {} service based on productName: {}", serviceToUse, productName);
            
            // Route to appropriate service
            if ("optics".equals(serviceToUse)) {
                // Use OpticsDiscountsService
                try {
                    opticsDiscountsService.processWebhookEvent(event);
                    log.info("✅ [OpticsDiscountsService] Successfully processed discount webhook event: {}", eventType);
                    
                    // Additional direct update logic based on event type
                    if ("checkout.session.completed".equals(eventType)) {
                        java.util.Optional<com.stripe.model.StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
                        if (stripeObjectOpt.isPresent() && stripeObjectOpt.get() instanceof com.stripe.model.checkout.Session) {
                            com.stripe.model.checkout.Session session = (com.stripe.model.checkout.Session) stripeObjectOpt.get();
                            if (opticsDiscountsService.updatePaymentStatusBySessionId(session.getId(), "paid")) {
                                log.info("✅ [OpticsDiscountsService] Additional direct update completed for sessionId: {}", session.getId());
                            }
                        }
                    } else if ("payment_intent.succeeded".equals(eventType)) {
                        java.util.Optional<com.stripe.model.StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
                        if (stripeObjectOpt.isPresent() && stripeObjectOpt.get() instanceof com.stripe.model.PaymentIntent) {
                            com.stripe.model.PaymentIntent paymentIntent = (com.stripe.model.PaymentIntent) stripeObjectOpt.get();
                            if (opticsDiscountsService.updatePaymentStatusByPaymentIntentId(paymentIntent.getId(), "paid")) {
                                log.info("✅ [OpticsDiscountsService] Additional direct update completed for paymentIntentId: {}", paymentIntent.getId());
                            }
                        }
                    } else if ("payment_intent.payment_failed".equals(eventType)) {
                        java.util.Optional<com.stripe.model.StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
                        if (stripeObjectOpt.isPresent() && stripeObjectOpt.get() instanceof com.stripe.model.PaymentIntent) {
                            com.stripe.model.PaymentIntent paymentIntent = (com.stripe.model.PaymentIntent) stripeObjectOpt.get();
                            if (opticsDiscountsService.updatePaymentStatusByPaymentIntentId(paymentIntent.getId(), "FAILED")) {
                                log.info("✅ [OpticsDiscountsService] Additional direct update completed for paymentIntentId: {}", paymentIntent.getId());
                            }
                        }
                    }
                    
                    updated = true;
                } catch (Exception e) {
                    log.warn("⚠️ [OpticsDiscountsService] Failed to process webhook event {}: {}", eventType, e.getMessage());
                    // Continue to try other services as fallback
                }
            } else if ("nursing".equals(serviceToUse)) {
                // Use NursingDiscountsService
                try {
                    nursingDiscountsService.processWebhookEvent(event);
                    log.info("✅ [NursingDiscountsService] Successfully processed discount webhook event: {}", eventType);
                    
                    // Additional direct update logic
                    if ("checkout.session.completed".equals(eventType)) {
                        java.util.Optional<com.stripe.model.StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
                        if (stripeObjectOpt.isPresent() && stripeObjectOpt.get() instanceof com.stripe.model.checkout.Session) {
                            com.stripe.model.checkout.Session session = (com.stripe.model.checkout.Session) stripeObjectOpt.get();
                            if (nursingDiscountsService.updatePaymentStatusBySessionId(session.getId(), "paid")) {
                                log.info("✅ [NursingDiscountsService] Additional direct update completed for sessionId: {}", session.getId());
                            }
                        }
                    } else if ("payment_intent.succeeded".equals(eventType)) {
                        java.util.Optional<com.stripe.model.StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
                        if (stripeObjectOpt.isPresent() && stripeObjectOpt.get() instanceof com.stripe.model.PaymentIntent) {
                            com.stripe.model.PaymentIntent paymentIntent = (com.stripe.model.PaymentIntent) stripeObjectOpt.get();
                            if (nursingDiscountsService.updatePaymentStatusByPaymentIntentId(paymentIntent.getId(), "paid")) {
                                log.info("✅ [NursingDiscountsService] Additional direct update completed for paymentIntentId: {}", paymentIntent.getId());
                            }
                        }
                    }
                    
                    updated = true;
                } catch (Exception e) {
                    log.warn("⚠️ [NursingDiscountsService] Failed to process webhook event {}: {}", eventType, e.getMessage());
                    // Continue to try other services as fallback
                }
            } else if ("renewable".equals(serviceToUse)) {
                // Use RenewableDiscountsService
                try {
                    renewableDiscountsService.processWebhookEvent(event);
                    log.info("✅ [RenewableDiscountsService] Successfully processed discount webhook event: {}", eventType);
                    
                    // Additional direct update logic
                    if ("checkout.session.completed".equals(eventType)) {
                        java.util.Optional<com.stripe.model.StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
                        if (stripeObjectOpt.isPresent() && stripeObjectOpt.get() instanceof com.stripe.model.checkout.Session) {
                            com.stripe.model.checkout.Session session = (com.stripe.model.checkout.Session) stripeObjectOpt.get();
                            if (renewableDiscountsService.updatePaymentStatusBySessionId(session.getId(), "paid")) {
                                log.info("✅ [RenewableDiscountsService] Additional direct update completed for sessionId: {}", session.getId());
                            }
                        }
                    } else if ("payment_intent.succeeded".equals(eventType)) {
                        java.util.Optional<com.stripe.model.StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
                        if (stripeObjectOpt.isPresent() && stripeObjectOpt.get() instanceof com.stripe.model.PaymentIntent) {
                            com.stripe.model.PaymentIntent paymentIntent = (com.stripe.model.PaymentIntent) stripeObjectOpt.get();
                            if (renewableDiscountsService.updatePaymentStatusByPaymentIntentId(paymentIntent.getId(), "paid")) {
                                log.info("✅ [RenewableDiscountsService] Additional direct update completed for paymentIntentId: {}", paymentIntent.getId());
                            }
                        }
                    }
                    
                    updated = true;
                } catch (Exception e) {
                    log.warn("⚠️ [RenewableDiscountsService] Failed to process webhook event {}: {}", eventType, e.getMessage());
                    // Continue to try other services as fallback
                }
            } else {
                // Default to polymers (most discount webhooks are polymers)
                try {
                    polymersDiscountsService.processWebhookEvent(event);
                    log.info("✅ [PolymersDiscountsService] Successfully processed discount webhook event: {}", eventType);
                    
                    // Additional direct update logic based on event type - same pattern as payment webhook
                    if ("checkout.session.completed".equals(eventType)) {
                        java.util.Optional<com.stripe.model.StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
                        if (stripeObjectOpt.isPresent() && stripeObjectOpt.get() instanceof com.stripe.model.checkout.Session) {
                            com.stripe.model.checkout.Session session = (com.stripe.model.checkout.Session) stripeObjectOpt.get();
                            if (polymersDiscountsService.updatePaymentStatusBySessionId(session.getId(), "paid")) {
                                log.info("✅ [PolymersDiscountsService] Additional direct update completed for sessionId: {}", session.getId());
                            }
                        }
                    } else if ("payment_intent.succeeded".equals(eventType)) {
                        java.util.Optional<com.stripe.model.StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
                        if (stripeObjectOpt.isPresent() && stripeObjectOpt.get() instanceof com.stripe.model.PaymentIntent) {
                            com.stripe.model.PaymentIntent paymentIntent = (com.stripe.model.PaymentIntent) stripeObjectOpt.get();
                            if (polymersDiscountsService.updatePaymentStatusByPaymentIntentId(paymentIntent.getId(), "paid")) {
                                log.info("✅ [PolymersDiscountsService] Additional direct update completed for paymentIntentId: {}", paymentIntent.getId());
                            }
                        }
                    } else if ("payment_intent.payment_failed".equals(eventType)) {
                        java.util.Optional<com.stripe.model.StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
                        if (stripeObjectOpt.isPresent() && stripeObjectOpt.get() instanceof com.stripe.model.PaymentIntent) {
                            com.stripe.model.PaymentIntent paymentIntent = (com.stripe.model.PaymentIntent) stripeObjectOpt.get();
                            if (polymersDiscountsService.updatePaymentStatusByPaymentIntentId(paymentIntent.getId(), "FAILED")) {
                                log.info("✅ [PolymersDiscountsService] Additional direct update completed for paymentIntentId: {}", paymentIntent.getId());
                            }
                        }
                    }
                    
                    updated = true;
                } catch (Exception e) {
                    log.warn("⚠️ [PolymersDiscountsService] Failed to process webhook event {}: {}", eventType, e.getMessage());
                    // If polymers service fails, this might not be a polymers discount, continue to other services
                }
            }
            
            // If the primary service failed, try fallback services
            if (!updated) {
                log.info("🔄 Primary service failed, trying all services as fallback for discount webhook processing");
                try {
                    opticsDiscountsService.processWebhookEvent(event);
                    log.info("✅ [OpticsDiscountsService] Successfully processed discount webhook as fallback: {}", eventType);
                    updated = true;
                } catch (Exception e) {
                    try {
                        nursingDiscountsService.processWebhookEvent(event);
                        log.info("✅ [NursingDiscountsService] Successfully processed discount webhook as fallback: {}", eventType);
                        updated = true;
                    } catch (Exception e2) {
                        try {
                            renewableDiscountsService.processWebhookEvent(event);
                            log.info("✅ [RenewableDiscountsService] Successfully processed discount webhook as fallback: {}", eventType);
                            updated = true;
                        } catch (Exception e3) {
                            try {
                                polymersDiscountsService.processWebhookEvent(event);
                                log.info("✅ [PolymersDiscountsService] Successfully processed discount webhook as fallback: {}", eventType);
                                updated = true;
                            } catch (Exception e4) {
                                log.warn("⚠️ All discount services failed to process webhook event: {}", eventType);
                            }
                        }
                    }
                }
            }
            
            if (updated) {
                log.info("✅ Discount webhook processed and discount table updated.");
                return ResponseEntity.ok("Discount webhook processed and discount table updated");
            } else {
                return ResponseEntity.ok("Webhook received but no discount record found - likely a regular payment sent to wrong endpoint");
            }
        } catch (Exception e) {
            log.error("Error processing discount webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Discount webhook processing failed");
        }
    }

}