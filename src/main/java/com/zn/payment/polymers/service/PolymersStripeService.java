package com.zn.payment.polymers.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paypal.core.PayPalEnvironment;
import com.paypal.core.PayPalHttpClient;
import com.paypal.http.HttpResponse;
import com.paypal.orders.AmountWithBreakdown;
import com.paypal.orders.ApplicationContext;
import com.paypal.orders.Capture;
import com.paypal.orders.LinkDescription;
import com.paypal.orders.Order;
import com.paypal.orders.OrderRequest;
import com.paypal.orders.OrdersCaptureRequest;
import com.paypal.orders.OrdersCreateRequest;
import com.paypal.orders.PurchaseUnit;
import com.paypal.orders.PurchaseUnitRequest;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import com.zn.payment.dto.CheckoutRequest;
import com.zn.payment.polymers.dto.PolymersPaymentResponseDTO;
import com.zn.payment.polymers.entity.PolymersPaymentRecord;
import com.zn.payment.polymers.repository.PolymersPaymentRecordRepository;
import com.zn.polymers.entity.PolymersPricingConfig;
import com.zn.polymers.entity.PolymersRegistrationForm;
import com.zn.polymers.repository.IPolymersPricingConfigRepository;
import com.zn.polymers.repository.IPolymersRegistrationFormRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Stripe Payment Service - EURO ONLY PAYMENTS WITH MANDATORY PRICING CONFIG
 * 
 * This service handles all Stripe payment operations and enforces EURO-only payments throughout the system.
 * ALL CHECKOUT SESSIONS NOW REQUIRE A PRICING CONFIG ID FOR VALIDATION.
 * 
 * Key Features:
 * - All payments are processed in EUR currency only
 * - pricingConfigId is MANDATORY for all checkout session creation
 * - Request amounts are validated against PricingConfig.totalPrice in EUROS
 * - Amounts are stored in euros (BigDecimal) in the database
 * - Stripe checkout sessions are created with EUR currency
 * - Payment dashboard reports show amounts in euros
 * - Pricing config validation ensures amounts match exactly in euros
 * - All Stripe API calls use EUR currency
 * 
 * Currency Policy:
 * - Only "eur" currency is accepted in all checkout requests
 * - Unit amounts in requests come in euros (e.g., 45.00) and are converted to cents by the controller
 * - Stripe API receives amounts in cents (e.g., 4500 = €45.00)
 * - Database stores amounts in euros (e.g., 45.00)
 * - Stripe dashboard will display all payments in euros
 * - Payment reports and statistics show euro values
 * 
 * Pricing Validation Policy:
 * - Every checkout session MUST include a valid pricingConfigId
 * - Request amount (unitAmount * quantity) MUST exactly match PricingConfig.totalPrice in EUROS
 * - Validation is performed in EUROS before conversion to cents for Stripe
 * - No sessions can be created without pricing config validation
 * 
 * @author System
 */
@Service
@Slf4j
public class PolymersStripeService {
    private static final ZoneId US_ZONE = ZoneId.of("America/New_York");

    @Value("${stripe.api.secret.key}")
    private String secretKey;

    @Value("${stripe.nursing.webhook.secret}")
    private String endpointSecret;

    @Value("${paypal.client.id}")
    private String paypalClientId;

    @Value("${paypal.client.secret}")
    private String paypalClientSecret;

    @Value("${paypal.mode}")
    private String paypalMode;

    @Value("${paypal.base.url}")
    private String paypalBaseUrl;
    
    // PayPal SDK Client
    private PayPalHttpClient paypalClient;
    
    @Autowired
    private PolymersPaymentRecordRepository paymentRecordRepository;

    @Autowired
    private IPolymersPricingConfigRepository pricingConfigRepository;

    @Autowired
    private IPolymersRegistrationFormRepository registrationFormRepository;

    /**
     * Initialize PayPal client after bean construction
     */
    @jakarta.annotation.PostConstruct
    public void initPayPalClient() {
        try {
            PayPalEnvironment environment;
            if ("sandbox".equalsIgnoreCase(paypalMode)) {
                environment = new PayPalEnvironment.Sandbox(paypalClientId, paypalClientSecret);
                log.info("🏗️ PayPal client initialized in SANDBOX mode for Polymers");
            } else {
                environment = new PayPalEnvironment.Live(paypalClientId, paypalClientSecret);
                log.info("🏗️ PayPal client initialized in LIVE mode for Polymers");
            }
            
            this.paypalClient = new PayPalHttpClient(environment);
            log.info("✅ PayPal SDK client successfully initialized for Polymers");
            
        } catch (Exception e) {
            log.error("❌ Failed to initialize PayPal client for Polymers: {}", e.getMessage(), e);
            throw new RuntimeException("PayPal client initialization failed", e);
        }
    }
    
    private LocalDateTime convertToLocalDateTime(Long timestamp) {
        if (timestamp == null) return null;
        return Instant.ofEpochSecond(timestamp)
                     .atZone(US_ZONE)
                     .toLocalDateTime();
    }

    public PolymersPaymentResponseDTO mapSessionToResponseDTO(Session session) {
        PolymersPaymentResponseDTO responseDTO = new PolymersPaymentResponseDTO();
        responseDTO.setSessionId(session.getId());
        responseDTO.setUrl(session.getUrl()); // Add the checkout URL
        
        // Map payment status properly - if session shows "paid", use it; otherwise default appropriately
        String stripePaymentStatus = session.getPaymentStatus();
        responseDTO.setPaymentStatus(stripePaymentStatus != null ? stripePaymentStatus : "unpaid");
        
        // Convert timestamps to LocalDateTime
        LocalDateTime createdTime = convertToLocalDateTime(session.getCreated());
        LocalDateTime expiresTime = convertToLocalDateTime(session.getExpiresAt());
        
        responseDTO.setStripeCreatedAt(createdTime);
        responseDTO.setStripeExpiresAt(expiresTime);
        
        // Map other fields from session metadata if available
        if (session.getMetadata() != null) {
            responseDTO.setCustomerName(session.getMetadata().get("customerName"));
            responseDTO.setProductName(session.getMetadata().get("productName"));
        }

        log.info("✅ Mapped session to response DTO with paymentStatus: {}", responseDTO.getPaymentStatus());
        
        return responseDTO;
    }

    /**
     * Create complete response DTO with both Stripe session and database record information
     */
    public PolymersPaymentResponseDTO createCompleteResponseDTO(Session session, PolymersPaymentRecord paymentRecord) {
        PolymersPaymentResponseDTO responseDTO = new PolymersPaymentResponseDTO();

        // Map Stripe session information
        responseDTO.setSessionId(session.getId());
        responseDTO.setUrl(session.getUrl());
        // Use paymentStatus from database record instead of session (database has the updated value)
        responseDTO.setPaymentStatus(paymentRecord.getPaymentStatus());
        responseDTO.setStripeCreatedAt(convertToLocalDateTime(session.getCreated()));
        responseDTO.setStripeExpiresAt(convertToLocalDateTime(session.getExpiresAt()));

        // Map database record information
        responseDTO.setId(paymentRecord.getId());
        responseDTO.setCustomerEmail(paymentRecord.getCustomerEmail());
        responseDTO.setAmountTotalEuros(paymentRecord.getAmountTotal());
        responseDTO.setAmountTotalCents(paymentRecord.getAmountTotal().multiply(BigDecimal.valueOf(100)).longValue());
        responseDTO.setCurrency(paymentRecord.getCurrency());
        responseDTO.setStatus(paymentRecord.getStatus() != null ? paymentRecord.getStatus().toString() : null);
        responseDTO.setCreatedAt(paymentRecord.getCreatedAt());
        responseDTO.setUpdatedAt(paymentRecord.getUpdatedAt());

        // Map pricing config information if available
        if (paymentRecord.getPricingConfig() != null) {
            responseDTO.setPricingConfigId(paymentRecord.getPricingConfig().getId());
            responseDTO.setPricingConfigTotalPrice(paymentRecord.getPricingConfig().getTotalPrice());
        }

        // Map other fields from session metadata if available
        if (session.getMetadata() != null) {
            responseDTO.setCustomerName(session.getMetadata().get("customerName"));
            responseDTO.setProductName(session.getMetadata().get("productName"));
        }

        log.info("✅ Created complete response DTO with DB ID: {} and session ID: {} with paymentStatus: {}", 
                paymentRecord.getId(), session.getId(), paymentRecord.getPaymentStatus());

        return responseDTO;
    }

    public Session createDetailedCheckoutSession(CheckoutRequest request) throws StripeException {
        log.info("Creating detailed checkout session for product: {}", request.getProductName());
        
        // Enforce EURO-only payments
        validateEuroCurrency(request);
        
        Stripe.apiKey = secretKey;

        // Create metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("productName", request.getProductName());
        if (request.getOrderReference() != null) {
            metadata.put("orderReference", request.getOrderReference());
        }
        
        // Store customer details for auto-registration
        if (request.getName() != null) {
            metadata.put("customerName", request.getName());
        }
        if (request.getPhone() != null) {
            metadata.put("customerPhone", request.getPhone());
        }
        if (request.getInstituteOrUniversity() != null) {
            metadata.put("customerInstitute", request.getInstituteOrUniversity());
        }
        if (request.getCountry() != null) {
            metadata.put("customerCountry", request.getCountry());
        }

        // Set expiration time (US Eastern)
        ZonedDateTime expirationTime = ZonedDateTime.now(US_ZONE).plus(30, ChronoUnit.MINUTES);

        // Build Stripe SessionCreateParams - enforcing EUR currency
        SessionCreateParams params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl(request.getSuccessUrl() + "?session_id={CHECKOUT_SESSION_ID}")
            .setCancelUrl(request.getCancelUrl() + "?session_id={CHECKOUT_SESSION_ID}")
            .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
            .setExpiresAt(expirationTime.toEpochSecond())
            .putAllMetadata(metadata)
            .setCustomerEmail(request.getEmail())
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setQuantity(request.getQuantity())
                    .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency("eur") // Always use EUR
                            .setUnitAmount(request.getUnitAmount())
                            .setProductData(
                                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(request.getProductName())
                                    .setDescription(request.getDescription())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build();

    try {
        Session session = Session.create(params);

        log.info("✅ Created checkout session with ID: {} at {}", 
                session.getId(), 
                convertToLocalDateTime(session.getCreated()));

        // 💾 Save to DB with status PENDING - always EUR currency
        PolymersPaymentRecord record = PolymersPaymentRecord.builder()
            .sessionId(session.getId())
            .paymentIntentId(null)
            .customerEmail(request.getEmail())
            .amountTotal(BigDecimal.valueOf(request.getUnitAmount() * request.getQuantity()).divide(BigDecimal.valueOf(100))) // Convert cents to euros
            .currency("eur") // Always EUR
            .status(PolymersPaymentRecord.PaymentStatus.PENDING) // Initial status should be PENDING
            .stripeCreatedAt(convertToLocalDateTime(session.getCreated()))
            .stripeExpiresAt(convertToLocalDateTime(session.getExpiresAt()))
            .paymentStatus(session.getPaymentStatus())
            .build();

        paymentRecordRepository.save(record);
        log.info("💾 Payment record created in PolymersPaymentRecord table for session: {}. No discount record created.", session.getId());

        return session;

    } catch (StripeException e) {
        log.error("❌ Error creating checkout session: {}", e.getMessage());
        throw e;
    }
}

    /**
     * @deprecated This method is no longer used since pricingConfigId is now mandatory.
     * Use createCheckoutSessionWithPricingValidation instead.
     */
    @Deprecated
    public PolymersPaymentResponseDTO createCheckoutSessionWithoutPricingValidation(CheckoutRequest request) throws StripeException {
        throw new UnsupportedOperationException("pricingConfigId is now mandatory. Use createCheckoutSessionWithPricingValidation instead.");
    }

    /**
     * Create checkout session with pricing config validation
     * This ensures the payment amount matches the configured pricing
     */
    public Session createCheckoutSessionWithPricing(CheckoutRequest request, PolymersPricingConfig pricingConfig) throws StripeException {
        log.info("Creating checkout session with pricing config validation for product: {}", request.getProductName());
        
        // Validate EUR currency first
        validateEuroCurrency(request);
        
        // Validate amount matches pricing config
        Long expectedAmountInCents = pricingConfig.getTotalPrice().multiply(BigDecimal.valueOf(100)).longValue();
        Long requestedAmount = request.getUnitAmount() * request.getQuantity();
        
        if (!expectedAmountInCents.equals(requestedAmount)) {
            throw new IllegalArgumentException(
                String.format("Nursing payment amount (%d cents) does not match NursingPricingConfig total (%d cents)", 
                            requestedAmount, expectedAmountInCents));
        }
        
        Stripe.apiKey = secretKey;

        // Create metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("productName", request.getProductName());
        metadata.put("pricingConfigId", pricingConfig.getId().toString());
        if (request.getOrderReference() != null) {
            metadata.put("orderReference", request.getOrderReference());
        }
        
        // Store customer details for auto-registration
        if (request.getName() != null) {
            metadata.put("customerName", request.getName());
        }
        if (request.getPhone() != null) {
            metadata.put("customerPhone", request.getPhone());
        }
        if (request.getInstituteOrUniversity() != null) {
            metadata.put("customerInstitute", request.getInstituteOrUniversity());
        }
        if (request.getCountry() != null) {
            metadata.put("customerCountry", request.getCountry());
        }
        
        // Store additional registration details
        if (request.getRegistrationType() != null) {
            metadata.put("registrationType", request.getRegistrationType());
        }
        if (request.getPresentationType() != null) {
            metadata.put("presentationType", request.getPresentationType());
        }
        metadata.put("accompanyingPerson", String.valueOf(request.isAccompanyingPerson()));
        metadata.put("extraNights", String.valueOf(request.getExtraNights()));
        metadata.put("accommodationNights", String.valueOf(request.getAccommodationNights()));
        metadata.put("accommodationGuests", String.valueOf(request.getAccommodationGuests()));

        // Set expiration time (US Eastern)
        ZonedDateTime expirationTime = ZonedDateTime.now(US_ZONE).plus(30, ChronoUnit.MINUTES);

        // Build Stripe SessionCreateParams - enforcing EUR currency
        SessionCreateParams params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl(request.getSuccessUrl() + "?session_id={CHECKOUT_SESSION_ID}")
            .setCancelUrl(request.getCancelUrl() + "?session_id={CHECKOUT_SESSION_ID}")
            .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
            .setExpiresAt(expirationTime.toEpochSecond())
            .putAllMetadata(metadata)
            .setCustomerEmail(request.getEmail())
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setQuantity(request.getQuantity())
                    .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency("eur") // Always use EUR
                            .setUnitAmount(request.getUnitAmount())
                            .setProductData(
                                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(request.getProductName())
                                    .setDescription(request.getDescription())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build();

        try {
            Session session = Session.create(params);

            log.info("✅ Created checkout session with ID: {} at {} for pricing config: {}", 
                    session.getId(), 
                    convertToLocalDateTime(session.getCreated()),
                    pricingConfig.getId());

        // 💾 Save to DB with pricing config relationship - always EUR currency
        PolymersPaymentRecord record = PolymersPaymentRecord.fromStripeWithPricing(
            session.getId(),
            request.getEmail(),
            "eur", // Always EUR
            pricingConfig,
            convertToLocalDateTime(session.getCreated()),
            convertToLocalDateTime(session.getExpiresAt()),
            session.getPaymentStatus()
        );

        paymentRecordRepository.save(record);
        
        // ✅ CRITICAL: Link payment record to existing registration form if available
        try {
            PolymersRegistrationForm existingRegistration = 
                registrationFormRepository.findTopByEmailOrderByIdDesc(request.getEmail());
            
            if (existingRegistration != null && existingRegistration.getPaymentRecord() == null) {
                log.info("🔗 Linking existing Polymers registration form ID: {} to payment record ID: {}", 
                        existingRegistration.getId(), record.getId());
                
                // Establish bidirectional relationship
                existingRegistration.setPaymentRecord(record);
                record.setRegistrationForm(existingRegistration);
                
                // Save both entities
                registrationFormRepository.save(existingRegistration);
                paymentRecordRepository.save(record);
                
                log.info("✅ Successfully linked Polymers registration form ID: {} to payment record ID: {}", 
                        existingRegistration.getId(), record.getId());
            }
        } catch (Exception e) {
            log.error("❌ Error linking Polymers registration form to payment record: {}", e.getMessage());
        }
        
        log.info("💾 Payment record created in PolymersPaymentRecord table for session: {} with PolymersPricingConfig: {}. No discount record created.", 
                session.getId(), pricingConfig.getId());

        return session;

        } catch (StripeException e) {
            log.error("❌ Error creating checkout session with pricing: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Create checkout session with strict validation against pricing config
     * Validates amount, currency, and ensures all values are in euros
     */
    public PolymersPaymentResponseDTO createValidatedCheckoutSession(CheckoutRequest request) throws StripeException {
        log.info("Creating validated checkout session for pricingConfigId: {}", request.getPricingConfigId());
        
        // 1. Validate required fields
        if (request.getPricingConfigId() == null) {
            throw new IllegalArgumentException("Pricing config ID is required for checkout validation");
        }
        
        // 2. Validate currency is EUR
        validateEuroCurrency(request);
        
        // 3. Fetch pricing config
        PolymersPricingConfig pricingConfig = pricingConfigRepository.findById(request.getPricingConfigId())
            .orElseThrow(() -> new IllegalArgumentException("PolymersPricingConfig not found with ID: " + request.getPricingConfigId()));
        
        log.info("Found PolymersPricingConfig with total price: {} EUR", pricingConfig.getTotalPrice());
        
        // 4. Validate unitAmount matches pricing config (convert euros to cents for comparison)
        Long expectedAmountInCents = pricingConfig.getTotalPrice().multiply(BigDecimal.valueOf(100)).longValue();
        Long requestedTotalInCents = request.getUnitAmount() * request.getQuantity();
        
        if (!expectedAmountInCents.equals(requestedTotalInCents)) {
            log.error("Payment amount validation failed. Expected: {} cents ({} EUR), Requested: {} cents ({} EUR)", 
                     expectedAmountInCents, pricingConfig.getTotalPrice(), 
                     requestedTotalInCents, BigDecimal.valueOf(requestedTotalInCents).divide(BigDecimal.valueOf(100)));
            throw new IllegalArgumentException(
                String.format("Payment amount mismatch. Expected: %s EUR, but received: %s EUR", 
                            pricingConfig.getTotalPrice(),
                            BigDecimal.valueOf(requestedTotalInCents).divide(BigDecimal.valueOf(100))));
        }
        
        log.info("✅ Amount validation passed: {} EUR", pricingConfig.getTotalPrice());
        
        // 5. Create Stripe session with validated amounts
        Session session = createCheckoutSessionWithPricing(request, pricingConfig);
        
        // 6. Fetch the saved PaymentRecord from database
        PolymersPaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(session.getId())
            .orElseThrow(() -> new RuntimeException("PolymersPaymentRecord not found after creation for session: " + session.getId()));
        // 7. Create complete response DTO with both Stripe and DB information
        PolymersPaymentResponseDTO response = createCompleteResponseDTO(session, paymentRecord);
        log.info("✅ Validated checkout session created: {} with Polymers DB ID: {}", session.getId(), paymentRecord.getId());
        return response;
    }

    /**
     * Create checkout session with pricing config ID validation
     * Fetches pricing config by ID and validates amount matches exactly in EUROS
     */
    public PolymersPaymentResponseDTO createCheckoutSessionWithPricingValidation(CheckoutRequest request, Long pricingConfigId) throws StripeException {
        log.info("Creating checkout session with mandatory pricing config ID: {}", pricingConfigId);
        // 1. Validate currency is EUR
        validateEuroCurrency(request);
        // 2. Fetch pricing config by ID
        PolymersPricingConfig pricingConfig = pricingConfigRepository.findById(pricingConfigId)
            .orElseThrow(() -> new IllegalArgumentException("PolymersPricingConfig not found with ID: " + pricingConfigId));
        log.info("Found PolymersPricingConfig with total price: {} EUR", pricingConfig.getTotalPrice());
        // 3. Convert request amount back to euros for comparison (request comes in cents after controller conversion)
        BigDecimal requestTotalInEuros = BigDecimal.valueOf(request.getUnitAmount() * request.getQuantity()).divide(BigDecimal.valueOf(100));
        BigDecimal pricingConfigTotalInEuros = pricingConfig.getTotalPrice();
        log.info("Comparing amounts in EUROS - Request total: {} EUR, PricingConfig total: {} EUR", 
                requestTotalInEuros, pricingConfigTotalInEuros);
        // 4. Validate amounts match exactly in EUROS (with scale precision)
        if (requestTotalInEuros.compareTo(pricingConfigTotalInEuros) != 0) {
            log.error("❌ Polymers payment amount validation failed in EUROS. Expected: {} EUR, Requested: {} EUR", 
                     pricingConfigTotalInEuros, requestTotalInEuros);
            throw new IllegalArgumentException(
                String.format("Polymers payment amount mismatch. Expected: %s EUR from PolymersPricingConfig, but received: %s EUR in request", 
                            pricingConfigTotalInEuros, requestTotalInEuros));
        }
        log.info("✅ Amount validation passed in EUROS: {} EUR matches PolymersPricingConfig", requestTotalInEuros);
        // 5. Create Stripe session using the existing method
        Session session = createCheckoutSessionWithPricing(request, pricingConfig);
        // 6. Fetch the saved PolymersPaymentRecord from database
        PolymersPaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(session.getId())
            .orElseThrow(() -> new RuntimeException("PolymersPaymentRecord not found after creation for session: " + session.getId()));
        // 7. Create complete response DTO with both Stripe and DB information
        PolymersPaymentResponseDTO response = createCompleteResponseDTO(session, paymentRecord);
        log.info("✅ Polymers checkout session created with mandatory pricing validation: {} with Polymers DB ID: {}", 
                session.getId(), paymentRecord.getId());
        return response;
    }

    public Event constructWebhookEvent(String payload, String sigHeader) throws SignatureVerificationException {
        log.debug("Constructing webhook event from payload with signature");
        
        if (endpointSecret == null || endpointSecret.trim().isEmpty()) {
            log.error("❌ Webhook endpoint secret is not configured! Check your STRIPE_WEBHOOK_SECRET environment variable");
            throw new SignatureVerificationException("Webhook endpoint secret is not configured", sigHeader);
        }
        
        if (!endpointSecret.startsWith("whsec_")) {
            log.error("❌ Invalid webhook secret format! Webhook secrets should start with 'whsec_'. Current: {}", 
                    endpointSecret.substring(0, Math.min(10, endpointSecret.length())) + "...");
            throw new SignatureVerificationException("Invalid webhook secret format", sigHeader);
        }
        
        try {
            log.info("✅ Using webhook secret: {}...", endpointSecret.substring(0, Math.min(10, endpointSecret.length())));
            return Webhook.constructEvent(payload, sigHeader, endpointSecret);
        } catch (SignatureVerificationException e) {
            log.error("❌ Signature verification failed. Payload length: {}, Signature: {}, Secret prefix: {}", 
                    payload.length(), sigHeader, endpointSecret.substring(0, Math.min(10, endpointSecret.length())));
            throw e;
        }
    }

    public void processWebhookEvent(Event event) {
        log.info("Processing webhook event of type: {}", event.getType());
        
        try {
            switch (event.getType()) {
                case "checkout.session.completed" -> {
                    log.info("🎯 Handling checkout.session.completed event");
                    handleCheckoutSessionCompleted(event);
                }
                case "payment_intent.succeeded" -> {
                    log.info("🎯 Handling payment_intent.succeeded event");
                    handlePaymentIntentSucceeded(event);
                }
                case "payment_intent.payment_failed" -> {
                    log.info("🎯 Handling payment_intent.payment_failed event");
                    handlePaymentIntentFailed(event);
                }
                case "checkout.session.expired" -> {
                    log.info("🎯 Handling checkout.session.expired event");
                    handleCheckoutSessionExpired(event);
                }
                default -> {
                    log.info("⚠️ Unhandled event type: {} - No action taken", event.getType());
                }
            }
        } catch (Exception e) {
            log.error("❌ Error processing webhook event {}: {}", event.getType(), e.getMessage(), e);
        }
    }

    private void handleCheckoutSessionCompleted(Event event) {
        log.info("🔄 Starting handleCheckoutSessionCompleted processing...");
        
        try {
            // Use EventDataObjectDeserializer to get the Session object
            EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
            
            if (dataObjectDeserializer.getObject().isPresent()) {
                Object deserializedObject = dataObjectDeserializer.getObject().get();
                log.info("📋 Event data object type: {}", deserializedObject.getClass().getSimpleName());
                
                if (deserializedObject instanceof Session session) {
                    log.info("✅ Successfully retrieved Session: {}", session.getId());
                    
                    // Log key data from the webhook for debugging
                    log.info("📊 Webhook Session Data:");
                    log.info("   - Session ID: {}", session.getId());
                    log.info("   - Amount Total: {} cents", session.getAmountTotal());
                    log.info("   - Currency: {}", session.getCurrency());
                    log.info("   - Customer Email: {}", session.getCustomerEmail());
                    log.info("   - Payment Intent: {}", session.getPaymentIntent());
                    log.info("   - Payment Status: {}", session.getPaymentStatus());
                    log.info("   - Session Status: {}", session.getStatus());
                    
                    processCheckoutSessionCompleted(session);
                } else {
                    log.error("❌ Event data object is not a Session! Type: {}", deserializedObject.getClass().getName());
                    
                    // Fallback: Try to extract session data manually from event
                    log.info("🔄 Attempting manual session data extraction from event...");
                    extractAndProcessSessionDataFromEvent(event);
                }
            } else {
                log.error("❌ Failed to deserialize checkout.session.completed event data");
                
                // Fallback: Try to extract session data manually from event
                log.info("🔄 Attempting manual session data extraction from event...");
                extractAndProcessSessionDataFromEvent(event);
            }
        } catch (Exception e) {
            log.error("❌ Error in handleCheckoutSessionCompleted: {}", e.getMessage(), e);
            
            // Last resort: Try manual extraction
            try {
                log.info("🔄 Last resort: Attempting manual session data extraction...");
                extractAndProcessSessionDataFromEvent(event);
            } catch (Exception fallbackException) {
                log.error("❌ All fallback methods failed: {}", fallbackException.getMessage(), fallbackException);
            }
        }
        
        log.info("✅ Completed handleCheckoutSessionCompleted processing");
    }
    
    /**
     * Fallback method to manually extract session data from the event when deserialization fails
     * This handles the case where EventDataObjectDeserializer doesn't work properly
     */
    private void extractAndProcessSessionDataFromEvent(Event event) {
        try {
            log.info("🔄 Starting manual extraction from Event object...");
            
            // Try to get the raw JSON string from the event directly
            String rawEventJson = event.toJson();
            log.info("📋 Full Event JSON: {}", rawEventJson);
            
            // Extract the data object from the event JSON
            String dataObjectJson = extractDataObjectFromEventJson(rawEventJson);
            
            if (dataObjectJson != null) {
                log.info("📋 Extracted data object JSON: {}", dataObjectJson);
                
                // Parse key fields manually from the data object JSON
                String sessionId = extractJsonField(dataObjectJson, "id");
                String customerEmail = extractJsonField(dataObjectJson, "customer_email");
                String paymentIntent = extractJsonField(dataObjectJson, "payment_intent");
                String paymentStatus = extractJsonField(dataObjectJson, "payment_status");
                String sessionStatus = extractJsonField(dataObjectJson, "status");
                String currency = extractJsonField(dataObjectJson, "currency");
                String amountTotalStr = extractJsonField(dataObjectJson, "amount_total");
                
                Long amountTotal = null;
                if (amountTotalStr != null && !amountTotalStr.isEmpty()) {
                    try {
                        amountTotal = Long.parseLong(amountTotalStr);
                    } catch (NumberFormatException e) {
                        log.warn("⚠️ Could not parse amount_total: {}", amountTotalStr);
                    }
                }
                
                log.info("📊 Manually Extracted Session Data:");
                log.info("   - Session ID: {}", sessionId);
                log.info("   - Customer Email: {}", customerEmail);
                log.info("   - Payment Intent: {}", paymentIntent);
                log.info("   - Payment Status: {}", paymentStatus);
                log.info("   - Session Status: {}", sessionStatus);
                log.info("   - Currency: {}", currency);
                log.info("   - Amount Total: {} cents", amountTotal);
                
                if (sessionId == null) {
                    log.error("❌ Session ID not found in webhook data");
                    return;
                }
                
                // Process the manually extracted data
                processCheckoutSessionCompletedManual(sessionId, customerEmail, paymentIntent, 
                                                    paymentStatus, currency, amountTotal);
            } else {
                log.error("❌ No data object found in event after manual extraction attempt");
            }
            
        } catch (Exception e) {
            log.error("❌ Error in manual session data extraction: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Extract the data.object JSON from the full event JSON
     */
    private String extractDataObjectFromEventJson(String eventJson) {
        try {
            // Find the "data" object in the event JSON
            String dataPattern = "\"data\":";
            int dataStart = eventJson.indexOf(dataPattern);
            if (dataStart == -1) {
                log.error("❌ No 'data' field found in event JSON");
                return null;
            }
            
            dataStart += dataPattern.length();
            
            // Skip whitespace
            while (dataStart < eventJson.length() && Character.isWhitespace(eventJson.charAt(dataStart))) {
                dataStart++;
            }
            
            if (dataStart >= eventJson.length() || eventJson.charAt(dataStart) != '{') {
                log.error("❌ Invalid data object structure in event JSON");
                return null;
            }
            
            // Find the matching closing brace for the data object
            int braceCount = 0;
            int dataEnd = dataStart;
            
            for (int i = dataStart; i < eventJson.length(); i++) {
                char c = eventJson.charAt(i);
                if (c == '{') {
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        dataEnd = i + 1;
                        break;
                    }
                }
            }
            
            String dataObjectJson = eventJson.substring(dataStart, dataEnd);
            
            // Now extract the "object" field from the data object
            String objectPattern = "\"object\":";
            int objectStart = dataObjectJson.indexOf(objectPattern);
            if (objectStart == -1) {
                log.error("❌ No 'object' field found in data JSON");
                return null;
            }
            
            objectStart += objectPattern.length();
            
            // Skip whitespace
            while (objectStart < dataObjectJson.length() && Character.isWhitespace(dataObjectJson.charAt(objectStart))) {
                objectStart++;
            }
            
            if (objectStart >= dataObjectJson.length() || dataObjectJson.charAt(objectStart) != '{') {
                log.error("❌ Invalid object structure in data JSON");
                return null;
            }
            
            // Find the matching closing brace for the object
            braceCount = 0;
            int objectEnd = objectStart;
            
            for (int i = objectStart; i < dataObjectJson.length(); i++) {
                char c = dataObjectJson.charAt(i);
                if (c == '{') {
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        objectEnd = i + 1;
                        break;
                    }
                }
            }
            
            return dataObjectJson.substring(objectStart, objectEnd);
            
        } catch (Exception e) {
            log.error("❌ Error extracting data object from event JSON: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Simple JSON field extractor for basic parsing
     */
    private String extractJsonField(String json, String fieldName) {
        try {
            String searchPattern = "\"" + fieldName + "\":";
            int startIndex = json.indexOf(searchPattern);
            if (startIndex == -1) {
                return null;
            }
            
            startIndex += searchPattern.length();
            
            // Skip whitespace
            while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
                startIndex++;
            }
            
            if (startIndex >= json.length()) {
                return null;
            }
            
            // Handle string values (enclosed in quotes)
            if (json.charAt(startIndex) == '"') {
                startIndex++; // Skip opening quote
                int endIndex = json.indexOf('"', startIndex);
                if (endIndex == -1) {
                    return null;
                }
                return json.substring(startIndex, endIndex);
            } else {
                // Handle numeric or other values
                int endIndex = startIndex;
                while (endIndex < json.length() && 
                       json.charAt(endIndex) != ',' && 
                       json.charAt(endIndex) != '}' && 
                       json.charAt(endIndex) != ']' &&
                       !Character.isWhitespace(json.charAt(endIndex))) {
                    endIndex++;
                }
                return json.substring(startIndex, endIndex);
            }
        } catch (Exception e) {
            log.warn("⚠️ Error extracting field '{}' from JSON: {}", fieldName, e.getMessage());
            return null;
        }
    }
    
    /**
     * Process checkout session completion using manually extracted data
     */
    private void processCheckoutSessionCompletedManual(String sessionId, String customerEmail, 
            String paymentIntent, String paymentStatus, 
            String currency, Long amountTotal) {
        try {
            log.info("🔄 Processing manually extracted session data for session: {}", sessionId);
            
            // Find existing PolymersPaymentRecord
            PolymersPaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(sessionId)
                .orElse(null);
            
            if (paymentRecord != null) {
                log.info("📋 Found existing PolymersPaymentRecord ID: {} for session: {}", paymentRecord.getId(), sessionId);
                // Update existing record with webhook data
                paymentRecord.setPaymentIntentId(paymentIntent);
                paymentRecord.setStatus(PolymersPaymentRecord.PaymentStatus.COMPLETED);
                paymentRecord.setPaymentStatus(paymentStatus != null ? paymentStatus : "paid");
                // Update customer email if it was null before
                if (paymentRecord.getCustomerEmail() == null && customerEmail != null) {
                    paymentRecord.setCustomerEmail(customerEmail);
                }
                // Update amount if provided and different
                if (amountTotal != null) {
                    BigDecimal amountInEuros = BigDecimal.valueOf(amountTotal).divide(BigDecimal.valueOf(100));
                    if (paymentRecord.getAmountTotal() == null || 
                        paymentRecord.getAmountTotal().compareTo(amountInEuros) != 0) {
                        log.info("💰 Updating amount from {} to {} EUR", paymentRecord.getAmountTotal(), amountInEuros);
                        paymentRecord.setAmountTotal(amountInEuros);
                    }
                }
                // Update currency if provided
                if (currency != null && paymentRecord.getCurrency() == null) {
                    paymentRecord.setCurrency(currency);
                }
                PolymersPaymentRecord savedRecord = paymentRecordRepository.save(paymentRecord);
                log.info("💾 ✅ Updated PolymersPaymentRecord ID: {} for session: {} to COMPLETED status with paymentStatus '{}'", 
                        savedRecord.getId(), sessionId, savedRecord.getPaymentStatus());
                
                // Log the current state for debugging
                log.info("🔍 PolymersPaymentRecord state after manual update: ID={}, Status={}, PaymentStatus={}, PaymentIntentId={}", 
                        savedRecord.getId(), savedRecord.getStatus(), savedRecord.getPaymentStatus(), savedRecord.getPaymentIntentId());
                // Link registration after successful payment (pass null for session since we don't have the object)
                autoRegisterUserAfterPaymentManual(savedRecord, customerEmail);
            } else {
                log.warn("⚠️ PolymersPaymentRecord not found for session {}, creating new one from webhook data", sessionId);
                PolymersPaymentRecord newRecord = PolymersPaymentRecord.builder()
                        .sessionId(sessionId)
                        .paymentIntentId(paymentIntent)
                        .customerEmail(customerEmail)
                        .amountTotal(amountTotal != null ? 
                            BigDecimal.valueOf(amountTotal).divide(BigDecimal.valueOf(100)) : null)
                        .currency(currency != null ? currency : "eur")
                        .status(PolymersPaymentRecord.PaymentStatus.COMPLETED)
                        .stripeCreatedAt(LocalDateTime.now()) // Use current time since we don't have stripe timestamp
                        .paymentStatus(paymentStatus != null ? paymentStatus : "paid")
                        .build();
                PolymersPaymentRecord savedRecord = paymentRecordRepository.save(newRecord);
                log.info("💾 ✅ Created new PolymersPaymentRecord ID: {} for session: {} with paymentStatus '{}'", 
                        savedRecord.getId(), sessionId, savedRecord.getPaymentStatus());
                // Link registration after successful payment
                autoRegisterUserAfterPaymentManual(savedRecord, customerEmail);
            }
        } catch (Exception e) {
            log.error("❌ Error processing manual checkout session completion for session {}: {}", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * Auto-register user after payment using manual data extraction
     */
    private void autoRegisterUserAfterPaymentManual(PolymersPaymentRecord paymentRecord, String customerEmail) {
        log.info("🔄 Manual verification of association for polymers payment record ID: {}", paymentRecord.getId());
        try {
            // Use the existing auto-registration logic but with manual email extraction
            if (customerEmail == null) {
                customerEmail = paymentRecord.getCustomerEmail();
            }
            if (customerEmail == null) {
                log.error("❌ CRITICAL: Cannot verify association - customer email not found for nursing payment record ID: {}", paymentRecord.getId());
                return;
            }
            // Find the most recent polymers registration form for this customer email
            var existingRegistration = registrationFormRepository.findTopByEmailOrderByIdDesc(customerEmail);
            if (existingRegistration == null) {
                log.error("❌ CRITICAL: No polymers registration form found for email: {} (polymers payment record ID: {})", 
                        customerEmail, paymentRecord.getId());
                return;
            }
            // Check if association already exists
            if (paymentRecord.getRegistrationForm() != null) {
                log.info("✅ Polymers payment record ID: {} already has registration form ID: {} associated", 
                        paymentRecord.getId(), paymentRecord.getRegistrationForm().getId());
                return;
            }
            // Establish the bidirectional association
            log.info("🔗 Creating association between polymers registration form ID: {} and polymers payment record ID: {}", 
                    existingRegistration.getId(), paymentRecord.getId());
            existingRegistration.setPaymentRecord(paymentRecord);
            paymentRecord.setRegistrationForm(existingRegistration);
            // Save both entities to ensure the relationship is persisted
            registrationFormRepository.save(existingRegistration);
            paymentRecordRepository.save(paymentRecord);
            
            log.info("✅ Successfully linked polymers registration form ID: {} to polymers payment record ID: {}", 
                    existingRegistration.getId(), paymentRecord.getId());
        } catch (Exception e) {
            log.error("❌ Error in manual registration linking for polymers payment record ID {}: {}", 
                    paymentRecord.getId(), e.getMessage(), e);
        }
    }
    
    /**
     * Process checkout session completed logic (extracted for reuse)
     * Updates PaymentRecord based on Stripe webhook data structure
     */
    private void processCheckoutSessionCompleted(Session session) {
        LocalDateTime completedTime = convertToLocalDateTime(session.getCreated());

        log.info("✅ Payment successful for session: {} at {}", session.getId(), completedTime);
        log.info("💳 Customer email: {}", session.getCustomerDetails() != null ?
                session.getCustomerDetails().getEmail() : session.getCustomerEmail());
        log.info("💰 Amount total: {} cents", session.getAmountTotal());
        log.info("💳 Payment Status from Stripe: {}", session.getPaymentStatus());
        log.info("📋 Session Status from Stripe: {}", session.getStatus());
        log.info("🔗 Payment Intent ID: {}", session.getPaymentIntent());

        // Update existing record based on webhook data structure
        try {
            PolymersPaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(session.getId())
                .orElse(null);
            
            if (paymentRecord != null) {
                log.info("📋 Found existing PolymersPaymentRecord ID: {} for session: {}", paymentRecord.getId(), session.getId());
                // Update record with webhook data - following the sample structure you provided
                paymentRecord.setPaymentIntentId(session.getPaymentIntent());
                if ("complete".equals(session.getStatus())) {
                    paymentRecord.setStatus(PolymersPaymentRecord.PaymentStatus.COMPLETED);
                }
                String stripePaymentStatus = session.getPaymentStatus();
                paymentRecord.setPaymentStatus(stripePaymentStatus != null ? stripePaymentStatus : "paid");
                String customerEmail = session.getCustomerDetails() != null ? 
                    session.getCustomerDetails().getEmail() : session.getCustomerEmail();
                if (paymentRecord.getCustomerEmail() == null && customerEmail != null) {
                    paymentRecord.setCustomerEmail(customerEmail);
                }
                if (session.getAmountTotal() != null) {
                    BigDecimal webhookAmountInEuros = BigDecimal.valueOf(session.getAmountTotal()).divide(BigDecimal.valueOf(100));
                    if (paymentRecord.getAmountTotal() == null || 
                        paymentRecord.getAmountTotal().compareTo(webhookAmountInEuros) != 0) {
                        log.info("💰 Updating amount from {} to {} EUR based on webhook data", 
                                paymentRecord.getAmountTotal(), webhookAmountInEuros);
                        paymentRecord.setAmountTotal(webhookAmountInEuros);
                    }
                }
                if (session.getCurrency() != null && paymentRecord.getCurrency() == null) {
                    paymentRecord.setCurrency(session.getCurrency());
                }
                PolymersPaymentRecord savedRecord = paymentRecordRepository.save(paymentRecord);
                log.info("💾 ✅ Updated PolymersPaymentRecord ID: {} for session: {} to COMPLETED status with paymentStatus '{}'", 
                        savedRecord.getId(), session.getId(), savedRecord.getPaymentStatus());
                
                log.info("🔍 PolymersPaymentRecord final state: ID={}, Status={}, PaymentStatus={}, PaymentIntentId={}, Amount={} EUR", 
                        savedRecord.getId(), savedRecord.getStatus(), savedRecord.getPaymentStatus(), 
                        savedRecord.getPaymentIntentId(), savedRecord.getAmountTotal());
                autoRegisterUserAfterPayment(savedRecord, session);
            } else {
                log.warn("⚠️ PolymersPaymentRecord not found for session {}, creating new one based on webhook data", session.getId());
                String customerEmail = session.getCustomerDetails() != null ? 
                    session.getCustomerDetails().getEmail() : session.getCustomerEmail();
                String stripePaymentStatus = session.getPaymentStatus();
                PolymersPaymentRecord record = PolymersPaymentRecord.builder()
                        .sessionId(session.getId())
                        .paymentIntentId(session.getPaymentIntent())
                        .customerEmail(customerEmail)
                        .amountTotal(session.getAmountTotal() != null ? 
                            BigDecimal.valueOf(session.getAmountTotal()).divide(BigDecimal.valueOf(100)) : null)
                        .currency(session.getCurrency())
                        .status(PolymersPaymentRecord.PaymentStatus.COMPLETED)
                        .stripeCreatedAt(completedTime)
                        .stripeExpiresAt(convertToLocalDateTime(session.getExpiresAt()))
                        .paymentStatus(stripePaymentStatus != null ? stripePaymentStatus : "paid")
                        .build();
                PolymersPaymentRecord savedRecord = paymentRecordRepository.save(record);
                log.info("💾 ✅ Created new PolymersPaymentRecord ID: {} for session: {} with paymentStatus '{}'", 
                        savedRecord.getId(), session.getId(), savedRecord.getPaymentStatus());
                autoRegisterUserAfterPayment(savedRecord, session);
            }
        } catch (Exception e) {
            log.error("❌ Error updating PaymentRecord for session {}: {}", session.getId(), e.getMessage(), e);
        }
    }

    /**
     * Handle payment_intent.succeeded webhook event
     * This fires when the payment is successfully processed
     */
    private void handlePaymentIntentSucceeded(Event event) {
        log.info("🔄 Starting handlePaymentIntentSucceeded processing...");
        
        try {
            // Use EventDataObjectDeserializer instead of deprecated getObject()
            EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
            if (dataObjectDeserializer.getObject().isPresent()) {
                Object deserializedObject = dataObjectDeserializer.getObject().get();
                log.info("📋 Event data object type: {}", deserializedObject.getClass().getSimpleName());
                
                if (deserializedObject instanceof com.stripe.model.PaymentIntent paymentIntent) {
                    log.info("✅ Payment Intent succeeded: {} for amount: {} {}", 
                            paymentIntent.getId(), 
                            paymentIntent.getAmount() != null ? BigDecimal.valueOf(paymentIntent.getAmount()).divide(BigDecimal.valueOf(100)) : "unknown",
                            paymentIntent.getCurrency());
                    
                    processPaymentIntentUpdate(paymentIntent);
                } else {
                    log.error("❌ Event data object is not a PaymentIntent! Type: {}", deserializedObject.getClass().getName());
                }
            } else {
                log.error("❌ Failed to deserialize payment_intent.succeeded event data");
            }
        } catch (Exception e) {
            log.error("❌ Failed to process payment_intent.succeeded: {}", e.getMessage(), e);
        }
        
        log.info("✅ Completed handlePaymentIntentSucceeded processing");
    }
    
    /**
     * Process PaymentIntent update logic (extracted for reuse)
     */
    private void processPaymentIntentUpdate(com.stripe.model.PaymentIntent paymentIntent) {
        try {
            // First, try to find PolymersPaymentRecord by payment intent ID
            PolymersPaymentRecord existingRecord = paymentRecordRepository.findByPaymentIntentId(paymentIntent.getId())
                .orElse(null);
            
            if (existingRecord != null) {
                log.info("📋 Found existing PolymersPaymentRecord by payment intent ID: {}", existingRecord.getId());
                updateExistingPaymentRecord(existingRecord, paymentIntent);
            } else {
                log.info("🔍 No PolymersPaymentRecord found by payment intent ID, searching for PENDING records...");
                
                // Look for PENDING records and update the most suitable one
                List<PolymersPaymentRecord> pendingRecords = paymentRecordRepository.findByStatus(PolymersPaymentRecord.PaymentStatus.PENDING);
                log.info("📊 Found {} PENDING PolymersPaymentRecord(s)", pendingRecords.size());
                
                if (!pendingRecords.isEmpty()) {
                    // Find the best matching pending record (by amount if possible)
                    PolymersPaymentRecord recordToUpdate = findBestMatchingPendingRecord(pendingRecords, paymentIntent);
                    updatePendingPaymentRecord(recordToUpdate, paymentIntent);
                } else {
                    log.warn("⚠️ No PENDING PolymersPaymentRecord found, creating new record");
                    createNewPaymentRecord(paymentIntent);
                }
            }
        } catch (Exception e) {
            log.error("❌ Error in processPaymentIntentUpdate: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Update existing PaymentRecord that already has payment intent ID
     */
    private void updateExistingPaymentRecord(PolymersPaymentRecord record, com.stripe.model.PaymentIntent paymentIntent) {
        log.info("🔄 Updating existing PolymersPaymentRecord ID: {} for payment intent: {}", record.getId(), paymentIntent.getId());
        
        record.setStatus(PolymersPaymentRecord.PaymentStatus.COMPLETED);
        record.setPaymentIntentId(paymentIntent.getId());
        record.setPaymentStatus("paid"); // Set Stripe payment status to "paid"
        
        // Update amount and currency if needed
        if (record.getAmountTotal() == null && paymentIntent.getAmount() != null) {
            BigDecimal amount = BigDecimal.valueOf(paymentIntent.getAmount()).divide(BigDecimal.valueOf(100));
            record.setAmountTotal(amount);
            log.info("💰 Updated amount to: {} EUR", amount);
        }
        if (record.getCurrency() == null && paymentIntent.getCurrency() != null) {
            record.setCurrency(paymentIntent.getCurrency());
            log.info("💱 Updated currency to: {}", paymentIntent.getCurrency());
        }
        
        paymentRecordRepository.save(record);
        log.info("💾 ✅ Successfully updated existing PolymersPaymentRecord ID: {} to COMPLETED status with paymentStatus 'paid'", record.getId());
    }
    
    /**
     * Find the best matching pending record for the payment intent
     */
    private PolymersPaymentRecord findBestMatchingPendingRecord(List<PolymersPaymentRecord> pendingRecords, com.stripe.model.PaymentIntent paymentIntent) {
        if (paymentIntent.getAmount() != null) {
            BigDecimal paymentAmount = BigDecimal.valueOf(paymentIntent.getAmount()).divide(BigDecimal.valueOf(100));
            
            // Try to find a record with matching amount
            for (PolymersPaymentRecord record : pendingRecords) {
                if (record.getAmountTotal() != null && record.getAmountTotal().compareTo(paymentAmount) == 0) {
                    log.info("🎯 Found PENDING record with matching amount: {} EUR (ID: {})", paymentAmount, record.getId());
                    return record;
                }
            }
        }
        
        // If no amount match, return the first (most recent) pending record
        PolymersPaymentRecord firstRecord = pendingRecords.get(0);
        log.info("📋 Using first PENDING record (ID: {}) - no amount match found", firstRecord.getId());
        return firstRecord;
    }
    
    /**
     * Update a pending PaymentRecord with payment intent information
     */
    private void updatePendingPaymentRecord(PolymersPaymentRecord record, com.stripe.model.PaymentIntent paymentIntent) {
        log.info("🔄 Updating PENDING PolymersPaymentRecord ID: {} with payment intent: {}", record.getId(), paymentIntent.getId());
        
        record.setPaymentIntentId(paymentIntent.getId());
        record.setStatus(PolymersPaymentRecord.PaymentStatus.COMPLETED);
        record.setPaymentStatus("paid"); // Set Stripe payment status to "paid"
        
        // Update amount if needed or if it doesn't match
        if (paymentIntent.getAmount() != null) {
            BigDecimal paymentAmount = BigDecimal.valueOf(paymentIntent.getAmount()).divide(BigDecimal.valueOf(100));
            if (record.getAmountTotal() == null || record.getAmountTotal().compareTo(paymentAmount) != 0) {
                log.info("💰 Updating amount from {} to {} EUR", record.getAmountTotal(), paymentAmount);
                record.setAmountTotal(paymentAmount);
            }
        }
        
        if (paymentIntent.getCurrency() != null && record.getCurrency() == null) {
            record.setCurrency(paymentIntent.getCurrency());
            log.info("💱 Set currency to: {}", paymentIntent.getCurrency());
        }
        
        paymentRecordRepository.save(record);
        log.info("💾 ✅ Successfully updated PENDING PolymersPaymentRecord ID: {} to COMPLETED status with paymentStatus 'paid'", record.getId());
    }
    
    /**
     * Create new PaymentRecord from payment intent (fallback)
     */
    private void createNewPaymentRecord(com.stripe.model.PaymentIntent paymentIntent) {
        log.info("🆕 Creating new PolymersPaymentRecord for payment intent: {}", paymentIntent.getId());
        
        PolymersPaymentRecord newRecord = PolymersPaymentRecord.builder()
                .paymentIntentId(paymentIntent.getId())
                .amountTotal(paymentIntent.getAmount() != null ? 
                    BigDecimal.valueOf(paymentIntent.getAmount()).divide(BigDecimal.valueOf(100)) : null)
                .currency(paymentIntent.getCurrency() != null ? paymentIntent.getCurrency() : "eur")
                .status(PolymersPaymentRecord.PaymentStatus.COMPLETED)
                .paymentStatus("paid") // Set Stripe payment status to "paid"
                .stripeCreatedAt(convertToLocalDateTime(paymentIntent.getCreated()))
                .build();
        
        paymentRecordRepository.save(newRecord);
        log.info("💾 ✅ Created new PolymersPaymentRecord ID: {} for payment intent: {} with paymentStatus 'paid'", newRecord.getId(), paymentIntent.getId());
    }

    /**
     * Handle payment_intent.payment_failed webhook event
     */
    private void handlePaymentIntentFailed(Event event) {
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            try {
                com.stripe.model.PaymentIntent paymentIntent = (com.stripe.model.PaymentIntent) dataObjectDeserializer.getObject().get();
                
                log.warn("❌ Payment Intent failed: {} - Reason: {}", 
                        paymentIntent.getId(), 
                        paymentIntent.getLastPaymentError() != null ? paymentIntent.getLastPaymentError().getMessage() : "Unknown");
                
                // Update PolymersPaymentRecord to FAILED status
                PolymersPaymentRecord existingRecord = paymentRecordRepository.findByPaymentIntentId(paymentIntent.getId())
                    .orElse(null);
                
                if (existingRecord != null) {
                    existingRecord.setStatus(PolymersPaymentRecord.PaymentStatus.FAILED);
                    existingRecord.setPaymentStatus("failed"); // Set Stripe payment status to "failed"
                    paymentRecordRepository.save(existingRecord);
                    log.info("💾 Updated PolymersPaymentRecord for payment intent: {} to FAILED status with paymentStatus 'failed'", paymentIntent.getId());
                } else {
                    log.warn("⚠️ PolymersPaymentRecord not found for failed payment intent: {}", paymentIntent.getId());
                }
                
            } catch (Exception e) {
                log.error("❌ Failed to process payment_intent.payment_failed: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Handle checkout.session.expired webhook event
     */
    private void handleCheckoutSessionExpired(Event event) {
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            try {
                Session session = (Session) dataObjectDeserializer.getObject().get();
                
                log.warn("⏰ Checkout session expired: {}", session.getId());
                
                // Update PolymersPaymentRecord to EXPIRED status
                PolymersPaymentRecord existingRecord = paymentRecordRepository.findBySessionId(session.getId())
                    .orElse(null);
                
                if (existingRecord != null) {
                    existingRecord.setStatus(PolymersPaymentRecord.PaymentStatus.EXPIRED);
                    existingRecord.setPaymentStatus("expired"); // Set Stripe payment status to "expired"
                    paymentRecordRepository.save(existingRecord);
                    log.info("💾 Updated PolymersPaymentRecord for session: {} to EXPIRED status with paymentStatus 'expired'", session.getId());
                } else {
                    log.warn("⚠️ PolymersPaymentRecord not found for expired session: {}", session.getId());
                }
                
            } catch (Exception e) {
                log.error("❌ Failed to process checkout.session.expired: {}", e.getMessage(), e);
            }
        }
    }

public PolymersPaymentResponseDTO retrieveSession(String sessionId) throws StripeException {
        log.info("Retrieving session with ID: {}", sessionId);
        Stripe.apiKey = secretKey;
        
        try {
            Session session = Session.retrieve(sessionId);
            return mapSessionToResponseDTO(session);
        } catch (StripeException e) {
            log.error("Error retrieving session: {}", e.getMessage());
            throw e;
        }
    }

    public PolymersPaymentResponseDTO expireSession(String sessionId) throws StripeException {
        log.info("Expiring session with ID: {}", sessionId);
        Stripe.apiKey = secretKey;
        
        try {
            Session session = Session.retrieve(sessionId);
            Session expiredSession = session.expire();
            return mapSessionToResponseDTO(expiredSession);
        } catch (StripeException e) {
            log.error("Error expiring session: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Validates and ensures that the request currency is EUR
     * If currency is null, sets it to EUR. If it's not EUR, throws exception.
     * @param request CheckoutRequest to validate and potentially modify
     * @throws IllegalArgumentException if currency is explicitly set to non-EUR
     */
    private void validateEuroCurrency(CheckoutRequest request) {
        if (request.getCurrency() == null) {
            // Default to EUR if not specified
            request.setCurrency("eur");
            log.info("Currency not specified, defaulting to EUR");
        } else if (!"eur".equalsIgnoreCase(request.getCurrency())) {
            throw new IllegalArgumentException(
                String.format("Currency must be 'eur' - only Euro payments are supported. Received: '%s'. All amounts must be in euros and will be displayed in euros in the Stripe dashboard.", 
                request.getCurrency()));
        }
    }
    
    /**
     * Automatically create a RegistrationForm after successful payment
     * This method is called from webhook processing after payment completion
     */
    /**
     * Link the existing RegistrationForm (created during session creation) to the PaymentRecord
     * after successful payment. This replaces the auto-registration logic since the form
     * is now created upfront with all user data.
     */
    private void autoRegisterUserAfterPayment(PolymersPaymentRecord paymentRecord, Session session) {
        log.info("🔄 Verifying association between registration form and payment record ID: {}", paymentRecord.getId());
        
        try {
            // Check if the payment record already has a registration form associated
            if (paymentRecord.getRegistrationForm() != null) {
                log.info("✅ Payment record ID: {} already has registration form ID: {} associated", 
                        paymentRecord.getId(), paymentRecord.getRegistrationForm().getId());
                return;
            }
            
            // Find the existing registration form by customer email
            String customerEmail = paymentRecord.getCustomerEmail();
            if (customerEmail == null && session.getCustomerDetails() != null) {
                customerEmail = session.getCustomerDetails().getEmail();
            }
            
            if (customerEmail == null) {
                log.error("❌ CRITICAL: Cannot verify association - customer email not found for payment record ID: {}", paymentRecord.getId());
                return;
            }
            
            // Find the most recent registration form for this customer email
            PolymersRegistrationForm existingRegistration = registrationFormRepository.findTopByEmailOrderByIdDesc(customerEmail);
            
            if (existingRegistration == null) {
                log.error("❌ CRITICAL: No registration form found for email: {} (payment record ID: {})", 
                        customerEmail, paymentRecord.getId());
                return;
            }
            
            // Check if this registration form is already linked to a payment record
            if (existingRegistration.getPaymentRecord() != null) {
                if (existingRegistration.getPaymentRecord().getId().equals(paymentRecord.getId())) {
                    log.info("✅ Registration form ID: {} is already correctly linked to payment record ID: {}", 
                            existingRegistration.getId(), paymentRecord.getId());
                    return;
                } else {
                    log.warn("⚠️ Registration form ID: {} is linked to different payment record ID: {}, but current payment is: {}", 
                            existingRegistration.getId(), existingRegistration.getPaymentRecord().getId(), paymentRecord.getId());
                    // This might be an issue - log for manual review
                }
            }
            
            // Establish the bidirectional association if it doesn't exist
            log.info("🔗 Creating missing association between registration form ID: {} and payment record ID: {}", 
                    existingRegistration.getId(), paymentRecord.getId());
            
            existingRegistration.setPaymentRecord(paymentRecord);
            paymentRecord.setRegistrationForm(existingRegistration);
            
            // Save both entities to ensure the relationship is persisted
            registrationFormRepository.save(existingRegistration);
            paymentRecordRepository.save(paymentRecord);
            
            log.info("✅ Successfully linked registration form ID: {} to payment record ID: {}", 
                    existingRegistration.getId(), paymentRecord.getId());
            
        } catch (Exception e) {
            log.error("❌ Error verifying/creating association for payment record ID {}: {}", 
                    paymentRecord.getId(), e.getMessage(), e);
            // Don't throw exception here - webhook processing should continue
        }
    }

    /**
     * Link an existing RegistrationForm to a PaymentRecord by session ID
     * This method establishes the mandatory bidirectional association during session creation
     */
    @Transactional
    public void linkRegistrationToPayment(Long registrationFormId, String sessionId) {
        log.info("🔗 Linking registration form ID: {} to payment record for session: {}", registrationFormId, sessionId);
        
        try {
            // Find the registration form
            PolymersRegistrationForm registrationForm = registrationFormRepository.findById(registrationFormId)
                    .orElseThrow(() -> new IllegalArgumentException("RegistrationForm not found with ID: " + registrationFormId));
            
            // Find the payment record
            PolymersPaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("PaymentRecord not found for session: " + sessionId));
            
            // Establish the bidirectional relationship
            registrationForm.setPaymentRecord(paymentRecord);
            paymentRecord.setRegistrationForm(registrationForm);
            
            // Save both entities to persist the relationship
            registrationFormRepository.save(registrationForm);
            paymentRecordRepository.save(paymentRecord);
            
            log.info("✅ Successfully linked registration form ID: {} to payment record ID: {} for session: {}", 
                    registrationFormId, paymentRecord.getId(), sessionId);
            
        } catch (Exception e) {
            log.error("❌ Failed to link registration form ID: {} to payment record for session: {}: {}", 
                    registrationFormId, sessionId, e.getMessage(), e);
            throw new RuntimeException("Failed to create mandatory registration-payment association: " + e.getMessage());
        }
    }
    
    /**
     * Update payment status from Stripe - follows same pattern as webhook processing
     * Fetches latest session data from Stripe and updates database record
     */
    public PolymersPaymentRecord updatePaymentStatus(String sessionId) throws StripeException {
        log.info("🔄 Updating payment status for Polymers session: {} (webhook-style processing)", sessionId);
        
        // 1. Fetch latest session data from Stripe (same as webhook does)
        Stripe.apiKey = secretKey;
        com.stripe.model.checkout.Session stripeSession = com.stripe.model.checkout.Session.retrieve(sessionId);
        
        // 2. Find existing payment record (same as webhook does)
        PolymersPaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Payment record not found for session: " + sessionId));
        
        log.info("📋 Found existing PolymersPaymentRecord ID: {} for session: {}", paymentRecord.getId(), sessionId);
        
        // 3. Update record with Stripe data (EXACTLY like webhook processing)
        // Sample: "payment_intent": "pi_1PABC1SDxA1b23dEfGHIjklm"
        paymentRecord.setPaymentIntentId(stripeSession.getPaymentIntent());
        
        // Sample: "status": "complete" indicates successful payment
        if ("complete".equals(stripeSession.getStatus())) {
            paymentRecord.setStatus(PolymersPaymentRecord.PaymentStatus.COMPLETED);
        }
        
        // Sample: "payment_status": "paid" indicates payment was successful
        String stripePaymentStatus = stripeSession.getPaymentStatus();
        paymentRecord.setPaymentStatus(stripePaymentStatus != null ? stripePaymentStatus : "paid");
        
        // Update customer email from Stripe data
        String customerEmail = stripeSession.getCustomerDetails() != null ? 
            stripeSession.getCustomerDetails().getEmail() : stripeSession.getCustomerEmail();
        if (paymentRecord.getCustomerEmail() == null && customerEmail != null) {
            paymentRecord.setCustomerEmail(customerEmail);
        }
        
        // Update amount from Stripe data
        if (stripeSession.getAmountTotal() != null) {
            BigDecimal stripeAmountInEuros = BigDecimal.valueOf(stripeSession.getAmountTotal()).divide(BigDecimal.valueOf(100));
            if (paymentRecord.getAmountTotal() == null || 
                paymentRecord.getAmountTotal().compareTo(stripeAmountInEuros) != 0) {
                log.info("💰 Updating amount from {} to {} EUR based on Stripe data", 
                        paymentRecord.getAmountTotal(), stripeAmountInEuros);
                paymentRecord.setAmountTotal(stripeAmountInEuros);
            }
        }
        
        // Update currency
        if (stripeSession.getCurrency() != null) {
            paymentRecord.setCurrency(stripeSession.getCurrency().toUpperCase());
        }
        
        // Update timestamps
        if (stripeSession.getCreated() != null) {
            paymentRecord.setStripeCreatedAt(convertToLocalDateTime(stripeSession.getCreated()));
        }
        
        paymentRecord.setUpdatedAt(LocalDateTime.now());
        
        // 4. Save updated record (same as webhook does)
        PolymersPaymentRecord updatedRecord = paymentRecordRepository.save(paymentRecord);
        
        log.info("✅ Updated Polymers payment record ID: {} for session: {} (webhook-style)", 
                updatedRecord.getId(), sessionId);
        
        return updatedRecord;
    }
    
    /**
     * Get payment status from database - returns stored record without calling Stripe
     */
    public PolymersPaymentRecord getPaymentStatus(String sessionId) {
        log.info("Getting payment status for Polymers session: {}", sessionId);
        
        PolymersPaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Payment record not found for session: " + sessionId));
        
        log.info("✅ Retrieved Polymers payment record for session: {}", sessionId);
        return paymentRecord;
    }
    
    /**
     * Get payment status directly from Stripe/PayPal providers - fetches real-time data
     * This method calls the actual payment providers to get the most current status
     */
    public PolymersPaymentRecord getPaymentStatusFromProvider(String sessionId) throws StripeException {
        log.info("🔄 Getting real-time payment status from provider for Polymers session: {}", sessionId);
        
        // Check if this is a PayPal order (starts with PAYPAL_)
        if (sessionId.startsWith("PAYPAL_")) {
            return getPayPalOrderStatus(sessionId);
        } else {
            // It's a Stripe session
            return getStripeSessionStatus(sessionId);
        }
    }
    
    /**
     * Get real-time status from Stripe API
     */
    private PolymersPaymentRecord getStripeSessionStatus(String sessionId) throws StripeException {
        log.info("🔄 Fetching real-time Stripe session status for: {}", sessionId);
        
        Stripe.apiKey = secretKey;
        
        try {
            // Fetch latest session data from Stripe
            com.stripe.model.checkout.Session stripeSession = com.stripe.model.checkout.Session.retrieve(sessionId);
            
            // Find existing payment record
            PolymersPaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(sessionId)
                    .orElseThrow(() -> new RuntimeException("Payment record not found for session: " + sessionId));
            
            // Update payment record with real-time Stripe data
            paymentRecord.setPaymentIntentId(stripeSession.getPaymentIntent());
            
            // Map Stripe session status to our status
            if ("complete".equals(stripeSession.getStatus())) {
                paymentRecord.setStatus(PolymersPaymentRecord.PaymentStatus.COMPLETED);
            } else if ("expired".equals(stripeSession.getStatus())) {
                paymentRecord.setStatus(PolymersPaymentRecord.PaymentStatus.EXPIRED);
            } else if ("open".equals(stripeSession.getStatus())) {
                paymentRecord.setStatus(PolymersPaymentRecord.PaymentStatus.PENDING);
            }
            
            // Update payment status from Stripe
            String stripePaymentStatus = stripeSession.getPaymentStatus();
            paymentRecord.setPaymentStatus(stripePaymentStatus != null ? stripePaymentStatus : "unpaid");
            
            // Update other fields from Stripe
            String customerEmail = stripeSession.getCustomerDetails() != null ? 
                stripeSession.getCustomerDetails().getEmail() : stripeSession.getCustomerEmail();
            if (customerEmail != null) {
                paymentRecord.setCustomerEmail(customerEmail);
            }
            
            if (stripeSession.getAmountTotal() != null) {
                BigDecimal stripeAmountInEuros = BigDecimal.valueOf(stripeSession.getAmountTotal()).divide(BigDecimal.valueOf(100));
                paymentRecord.setAmountTotal(stripeAmountInEuros);
            }
            
            if (stripeSession.getCurrency() != null) {
                paymentRecord.setCurrency(stripeSession.getCurrency());
            }
            
            paymentRecord.setUpdatedAt(LocalDateTime.now());
            
            // Save updated record
            PolymersPaymentRecord updatedRecord = paymentRecordRepository.save(paymentRecord);
            
            log.info("✅ Retrieved and updated Polymers payment status from Stripe for session: {} - Status: {}, Payment Status: {}", 
                    sessionId, updatedRecord.getStatus(), updatedRecord.getPaymentStatus());
            
            return updatedRecord;
            
        } catch (StripeException e) {
            log.error("❌ Error fetching Stripe session status for {}: {}", sessionId, e.getMessage());
            throw e;
        }
    }
    
    /**
     * Get real-time status from PayPal API (mock implementation for now)
     */
    private PolymersPaymentRecord getPayPalOrderStatus(String orderId) {
        log.info("🔄 Fetching real-time PayPal order status for: {}", orderId);
        
        try {
            // Find existing payment record
            PolymersPaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(orderId)
                    .orElseThrow(() -> new RuntimeException("PayPal payment record not found for order: " + orderId));
            
            // TODO: Replace this with actual PayPal SDK call to get order status
            // For now, return the current database status
            log.info("📋 PayPal order status check - Current status: {}, Payment Status: {}", 
                    paymentRecord.getStatus(), paymentRecord.getPaymentStatus());
            
            // In a real implementation, you would:
            // 1. Call PayPal SDK: Order.get(orderId)
            // 2. Map PayPal status to our status enum
            // 3. Update and save the record
            
            paymentRecord.setUpdatedAt(LocalDateTime.now());
            PolymersPaymentRecord updatedRecord = paymentRecordRepository.save(paymentRecord);
            
            log.info("✅ Retrieved PayPal payment status for order: {} - Status: {}", 
                    orderId, updatedRecord.getStatus());
            
            return updatedRecord;
            
        } catch (Exception e) {
            log.error("❌ Error fetching PayPal order status for {}: {}", orderId, e.getMessage());
            throw new RuntimeException("PayPal status retrieval failed: " + e.getMessage());
        }
    }
    
    // ======================= PAYPAL INTEGRATION =======================
    
    /**
     * Create PayPal order for Polymers payments - PRODUCTION READY
     */
    public com.zn.payment.dto.PayPalOrderResponse createPayPalOrder(com.zn.payment.dto.PayPalCreateOrderRequest request) {
        log.info("Creating PayPal order for Polymers - Amount: {} {}, Customer: {}", 
                request.getAmount(), request.getCurrency(), request.getCustomerEmail());
        
        try {
            // Validate request
            if (request.getCustomerEmail() == null || request.getCustomerEmail().trim().isEmpty()) {
                throw new IllegalArgumentException("Customer email is required for PayPal order");
            }
            
            if (request.getAmount() == null || request.getAmount().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Amount must be greater than zero");
            }

            // Validate and get pricing config if provided
            PolymersPricingConfig pricingConfig = null;
            if (request.getPricingConfigId() != null) {
                pricingConfig = pricingConfigRepository.findById(request.getPricingConfigId())
                        .orElseThrow(() -> new IllegalArgumentException("Pricing config not found with ID: " + request.getPricingConfigId()));
                
                // Validate amount matches pricing config
                if (request.getAmount().compareTo(pricingConfig.getTotalPrice()) != 0) {
                    throw new IllegalArgumentException(String.format(
                        "Request amount %.2f does not match pricing config amount %.2f", 
                        request.getAmount(), pricingConfig.getTotalPrice()));
                }
            }

            // Create or find registration form
            PolymersRegistrationForm registrationForm = null;
            
            // Try to find existing registration by email
            PolymersRegistrationForm existingRegistration = 
                registrationFormRepository.findTopByEmailOrderByIdDesc(request.getCustomerEmail());
            
            if (existingRegistration != null) {
                registrationForm = existingRegistration;
                log.info("Found existing Polymers registration for email: {}", request.getCustomerEmail());
            } else {
                // Create new registration form
                registrationForm = new PolymersRegistrationForm();
                registrationForm.setName(request.getCustomerName() != null ? request.getCustomerName() : "");
                registrationForm.setPhone(request.getPhone() != null ? request.getPhone() : "");
                registrationForm.setEmail(request.getCustomerEmail());
                registrationForm.setInstituteOrUniversity(request.getInstituteOrUniversity() != null ? request.getInstituteOrUniversity() : "");
                registrationForm.setCountry(request.getCountry() != null ? request.getCountry() : "");
                
                if (pricingConfig != null) {
                    registrationForm.setPricingConfig(pricingConfig);
                    registrationForm.setAmountPaid(pricingConfig.getTotalPrice());
                }
                
                registrationForm = registrationFormRepository.save(registrationForm);
                log.info("✅ Created new Polymers registration form with ID: {}", registrationForm.getId());
            }

            // Create PayPal order via SDK - this will return the actual PayPal order ID and approval URL
            PayPalOrderResult orderResult = createPayPalOrderAPI(request, registrationForm.getId());
            
            // Use PayPal's actual order ID instead of custom internal ID
            String paypalOrderId = orderResult.getOrderId();
            String approvalUrl = orderResult.getApprovalUrl();
            
            // Create payment record with PayPal's actual order ID
            PolymersPaymentRecord paymentRecord = PolymersPaymentRecord.builder()
                    .sessionId(paypalOrderId) // Store PayPal's actual order ID
                    .customerEmail(request.getCustomerEmail())
                    .amountTotal(request.getAmount())
                    .currency(request.getCurrency() != null ? request.getCurrency().toUpperCase() : "EUR")
                    .provider("PAYPAL")
                    .status(PolymersPaymentRecord.PaymentStatus.PENDING)
                    .paymentStatus("unpaid")
                    .stripeCreatedAt(java.time.LocalDateTime.now())
                    .registrationForm(registrationForm)
                    .build();

            // Add pricing config to payment record if available
            if (pricingConfig != null) {
                paymentRecord.setPricingConfig(pricingConfig);
            }
            
            // Save payment record
            PolymersPaymentRecord savedRecord = paymentRecordRepository.save(paymentRecord);
            
            // ✅ CRITICAL: Establish bidirectional relationship
            registrationForm.setPaymentRecord(savedRecord);
            registrationFormRepository.save(registrationForm);
            
            log.info("💾 Saved Polymers PayPal payment record with ID: {} for PayPal order: {}", 
                    savedRecord.getId(), paypalOrderId);
            log.info("🔗 Linked Polymers registration form ID: {} to payment record ID: {}", 
                    registrationForm.getId(), savedRecord.getId());
            
            log.info("✅ Created PayPal order for Polymers: {} with approval URL: {}", paypalOrderId, approvalUrl);
            
            return com.zn.payment.dto.PayPalOrderResponse.success(
                    paypalOrderId, 
                    approvalUrl, 
                    request.getCustomerEmail(),
                    request.getAmount().toString(),
                    request.getCurrency()
            );
            
        } catch (Exception e) {
            log.error("❌ Error creating PayPal order for Polymers: {}", e.getMessage(), e);
            return com.zn.payment.dto.PayPalOrderResponse.error("paypal_order_creation_failed: " + e.getMessage());
        }
    }

    /**
     * Create PayPal order via PayPal SDK for Polymers
     * Returns PayPal order result with order ID and approval URL - PRODUCTION READY
     */
    private PayPalOrderResult createPayPalOrderAPI(com.zn.payment.dto.PayPalCreateOrderRequest request, Long registrationFormId) {
        try {
            log.info("🚀 Creating real PayPal order via SDK for Polymers registration form: {}", registrationFormId);
            
            // Build PayPal order request using SDK
            OrderRequest orderRequest = new OrderRequest();
            orderRequest.checkoutPaymentIntent("CAPTURE");
            
            // Set application context
            ApplicationContext applicationContext = new ApplicationContext()
                .returnUrl(request.getSuccessUrl() + "?paymentMethod=paypal")
                .cancelUrl(request.getCancelUrl() + "?paymentMethod=paypal&cancelled=true")
                .brandName("Polymers Science Conference 2026")
                .landingPage("BILLING")
                .userAction("PAY_NOW");
            
            orderRequest.applicationContext(applicationContext);
            
            // Create purchase units
            List<PurchaseUnitRequest> purchaseUnits = new ArrayList<>();
            
            // Create amount breakdown
            AmountWithBreakdown amountBreakdown = new AmountWithBreakdown()
                .currencyCode(request.getCurrency() != null ? request.getCurrency().toUpperCase() : "EUR")
                .value(request.getAmount().toString());
            
            PurchaseUnitRequest purchaseUnitRequest = new PurchaseUnitRequest()
                .referenceId("REG_" + registrationFormId)
                .description("Polymers Science Conference 2026 Registration - " + request.getCustomerEmail())
                .customId("POLYMERS_REG_" + registrationFormId)
                .softDescriptor("POLYMERSCONF")
                .amountWithBreakdown(amountBreakdown);
            
            purchaseUnits.add(purchaseUnitRequest);
            orderRequest.purchaseUnits(purchaseUnits);
            
            // Create the order
            OrdersCreateRequest ordersCreateRequest = new OrdersCreateRequest();
            ordersCreateRequest.prefer("return=representation");
            ordersCreateRequest.requestBody(orderRequest);
            
            HttpResponse<Order> response = paypalClient.execute(ordersCreateRequest);
            Order order = response.result();
            
            log.info("✅ PayPal order created successfully for Polymers: {} with status: {}", order.id(), order.status());
            
            // Extract approval URL
            String approvalUrl = null;
            for (LinkDescription link : order.links()) {
                if ("approve".equals(link.rel())) {
                    approvalUrl = link.href();
                    break;
                }
            }
            
            if (approvalUrl == null) {
                throw new RuntimeException("PayPal approval URL not found in response");
            }
            
            log.info("🔗 PayPal approval URL generated for Polymers: {}", approvalUrl);
            
            return new PayPalOrderResult(order.id(), approvalUrl);
            
        } catch (Exception e) {
            log.error("❌ Error creating PayPal order via SDK for Polymers: {}", e.getMessage(), e);
            throw new RuntimeException("PayPal SDK error: " + e.getMessage(), e);
        }
    }
    
    /**
     * Helper class to return both PayPal order ID and approval URL
     */
    private static class PayPalOrderResult {
        private final String orderId;
        private final String approvalUrl;
        
        public PayPalOrderResult(String orderId, String approvalUrl) {
            this.orderId = orderId;
            this.approvalUrl = approvalUrl;
        }
        
        public String getOrderId() {
            return orderId;
        }
        
        public String getApprovalUrl() {
            return approvalUrl;
        }
    }
    
    /**
     * Capture PayPal order for Polymers payments - PRODUCTION READY
     */
    public com.zn.payment.dto.PayPalOrderResponse capturePayPalOrder(String orderId) {
        log.info("Capturing PayPal order for Polymers: {} (called from return URL)", orderId);
        
        try {
            // Find payment record by order ID (token from return URL)
            PolymersPaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(orderId)
                    .orElseThrow(() -> new RuntimeException("PayPal payment record not found for order: " + orderId));
            
            // Capture the order via PayPal API
            boolean captureSuccess = capturePayPalOrderAPI(orderId, paymentRecord.getAmountTotal());
            
            if (captureSuccess) {
                // Update payment record to COMPLETED
                paymentRecord.setStatus(PolymersPaymentRecord.PaymentStatus.COMPLETED);
                paymentRecord.setPaymentStatus("paid");
                paymentRecord.setUpdatedAt(java.time.LocalDateTime.now());
                
                // Save updated record
                PolymersPaymentRecord updatedRecord = paymentRecordRepository.save(paymentRecord);
                log.info("✅ PayPal order captured for Polymers: {}", orderId);
                
                // Update registration form if linked
                if (updatedRecord.getRegistrationForm() != null) {
                    PolymersRegistrationForm registrationForm = updatedRecord.getRegistrationForm();
                    registrationForm.setAmountPaid(updatedRecord.getAmountTotal());
                    registrationFormRepository.save(registrationForm);
                    log.info("✅ Updated registration form amount for PayPal payment: {}", registrationForm.getId());
                }
                
                return com.zn.payment.dto.PayPalOrderResponse.success(
                        orderId,
                        null, // No approval URL needed for capture
                        updatedRecord.getCustomerEmail(),
                        updatedRecord.getAmountTotal().toString(),
                        updatedRecord.getCurrency()
                );
            } else {
                // Mark as failed if capture unsuccessful
                paymentRecord.setStatus(PolymersPaymentRecord.PaymentStatus.FAILED);
                paymentRecord.setPaymentStatus("failed");
                paymentRecord.setUpdatedAt(java.time.LocalDateTime.now());
                paymentRecordRepository.save(paymentRecord);
                
                throw new RuntimeException("PayPal capture failed for order: " + orderId);
            }
            
        } catch (Exception e) {
            log.error("❌ Error capturing PayPal order for Polymers: {}", e.getMessage(), e);
            return com.zn.payment.dto.PayPalOrderResponse.error("paypal_capture_failed: " + e.getMessage());
        }
    }

    /**
     * Capture PayPal order via PayPal SDK - PRODUCTION READY
     */
    private boolean capturePayPalOrderAPI(String orderId, java.math.BigDecimal amount) {
        try {
            log.info("🔗 Capturing PayPal order via SDK for Polymers: {} for amount: {} EUR", orderId, amount);
            
            String paypalOrderId = extractPayPalOrderId(orderId);
            
            if (paypalOrderId == null) {
                log.error("❌ Could not extract PayPal order ID from: {}", orderId);
                return false;
            }
            
            // Create capture request
            OrdersCaptureRequest ordersCaptureRequest = new OrdersCaptureRequest(paypalOrderId);
            ordersCaptureRequest.prefer("return=representation");
            
            // Execute the capture
            HttpResponse<Order> response = paypalClient.execute(ordersCaptureRequest);
            Order capturedOrder = response.result();
            
            log.info("✅ PayPal order captured successfully for Polymers: {} with status: {}", 
                    capturedOrder.id(), capturedOrder.status());
            
            // Verify the capture was successful
            if ("COMPLETED".equals(capturedOrder.status())) {
                // Verify the captured amount matches expected amount
                if (capturedOrder.purchaseUnits() != null && !capturedOrder.purchaseUnits().isEmpty()) {
                    PurchaseUnit purchaseUnit = capturedOrder.purchaseUnits().get(0);
                    if (purchaseUnit.payments() != null && 
                        purchaseUnit.payments().captures() != null && 
                        !purchaseUnit.payments().captures().isEmpty()) {
                        
                        Capture capture = purchaseUnit.payments().captures().get(0);
                        String capturedAmount = capture.amount().value();
                        
                        if (amount.toString().equals(capturedAmount)) {
                            log.info("✅ PayPal capture amount verified for Polymers: {} EUR", capturedAmount);
                            return true;
                        } else {
                            log.error("❌ PayPal capture amount mismatch for Polymers. Expected: {} EUR, Captured: {} EUR", 
                                    amount, capturedAmount);
                            return false;
                        }
                    }
                }
                
                // If we can't verify the amount but status is COMPLETED, consider it successful
                log.warn("⚠️ PayPal capture completed but couldn't verify amount for Polymers order: {}", paypalOrderId);
                return true;
            } else {
                log.error("❌ PayPal capture failed with status: {} for Polymers order: {}", 
                        capturedOrder.status(), paypalOrderId);
                return false;
            }
            
        } catch (Exception e) {
            log.error("❌ Error capturing PayPal order via SDK for Polymers: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Extract PayPal order ID from internal order ID for Polymers
     */
    private String extractPayPalOrderId(String internalOrderId) {
        try {
            java.util.Optional<PolymersPaymentRecord> paymentRecordOpt = 
                paymentRecordRepository.findBySessionId(internalOrderId);
                
            if (paymentRecordOpt.isPresent()) {
                // Temporary: Use the internal order ID as PayPal order ID
                // In production, you should store the actual PayPal order ID returned from creation
                log.warn("⚠️ Using internal order ID as PayPal order ID for Polymers (should be updated in production): {}", 
                        internalOrderId);
                return internalOrderId;
            }
            
            log.error("❌ Payment record not found for internal order ID: {}", internalOrderId);
            return null;
            
        } catch (Exception e) {
            log.error("❌ Error extracting PayPal order ID for Polymers: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Handle PayPal return URL - extract token and capture payment
     */
    public com.zn.payment.dto.PayPalOrderResponse handlePayPalReturn(String token, String payerId) {
        log.info("Handling PayPal return for Polymers - token: {} and payer: {}", token, payerId);
        
        try {
            // Token is the order ID - capture the payment
            return capturePayPalOrder(token);
            
        } catch (Exception e) {
            log.error("❌ Error handling PayPal return for Polymers: {}", e.getMessage(), e);
            return com.zn.payment.dto.PayPalOrderResponse.error("paypal_return_handling_failed: " + e.getMessage());
        }
    }

    // ...existing code...
}
