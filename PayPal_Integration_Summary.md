# PayPal Integration with Existing Vertical Services

## Overview
I've successfully implemented PayPal integration using your existing service architecture, following the same patterns as your Stripe integration.

## What Was Implemented

### 1. Updated Payment Record Entities
All existing payment record entities now support both Stripe and PayPal:

- `OpticsPaymentRecord`
- `NursingPaymentRecord`
- `RenewablePaymentRecord`
- `PolymersPaymentRecord`

**Added Fields:**
- `provider` field (defaults to "STRIPE", can be "PAYPAL")
- PayPal factory methods: `fromPayPalResponse()`
- Enhanced factory methods to support provider parameter
- Convenience methods: `isStripePayment()`, `isPayPalPayment()`

### 2. PayPal DTOs Created
- `PayPalCreateOrderRequest.java` - Request DTO for creating PayPal orders
- `PayPalCaptureOrderRequest.java` - Request DTO for capturing PayPal orders
- `PayPalOrderResponse.java` - Response DTO with success/error factory methods

### 3. PaymentController APIs
Added domain-based routing PayPal endpoints:

**Create PayPal Order:**
```
POST /api/payment/paypal/create
```
- Routes to appropriate vertical service based on Origin/Referer header
- Validates pricing config if provided
- Uses backend pricing (same as Stripe)

**Capture PayPal Order:**
```
POST /api/payment/paypal/capture
```
- Routes to appropriate vertical service based on Origin/Referer header
- Falls back to trying all services if origin is unknown

### 4. Service Layer Integration
Added PayPal methods to all existing Stripe services:

**OpticsStripeService:**
- `createPayPalOrder(PayPalCreateOrderRequest)` 
- `capturePayPalOrder(String orderId)`

**NursingStripeService:**
- `createPayPalOrder(PayPalCreateOrderRequest)`
- `capturePayPalOrder(String orderId)`

**RenewaleStripeService:**
- `createPayPalOrder(PayPalCreateOrderRequest)`
- `capturePayPalOrder(String orderId)`

**PolymersStripeService:**
- `createPayPalOrder(PayPalCreateOrderRequest)`
- `capturePayPalOrder(String orderId)`

## Architecture Benefits

### 1. Consistent with Existing Patterns
- Same domain-based routing as Stripe
- Uses existing service classes instead of creating new ones
- Follows same validation and pricing logic
- Updates same vertical payment record tables

### 2. Provider Distinction
- `provider` field in all payment records distinguishes "STRIPE" vs "PAYPAL"
- Both providers use same database tables
- Same webhook-style processing patterns
- Unified payment tracking across providers

### 3. Vertical Separation
- Each vertical (Optics, Nursing, Renewable, Polymers) handles its own PayPal orders
- Domain-based routing ensures correct vertical processing
- Maintains existing service separation

## API Usage Examples

### Create PayPal Order for Optics
```http
POST /api/payment/paypal/create
Origin: https://globallopmeet.com

{
  "customerEmail": "user@example.com",
  "customerName": "John Doe",
  "amount": 45.00,
  "currency": "EUR",
  "pricingConfigId": 123
}
```

### Capture PayPal Order
```http
POST /api/payment/paypal/capture
Origin: https://globallopmeet.com

{
  "orderId": "PAYPAL_ORDER_1234567890",
  "payerId": "PAYER123"
}
```

## Database Schema Changes Required

Add `provider` column to all payment record tables:

```sql
-- For all vertical payment record tables:
ALTER TABLE optics_payment_records ADD COLUMN provider VARCHAR(20) DEFAULT 'STRIPE';
ALTER TABLE nursing_payment_records ADD COLUMN provider VARCHAR(20) DEFAULT 'STRIPE';
ALTER TABLE renewable_payment_records ADD COLUMN provider VARCHAR(20) DEFAULT 'STRIPE';
ALTER TABLE polymers_payment_records ADD COLUMN provider VARCHAR(20) DEFAULT 'STRIPE';
```

## Mock Implementation Note

The current implementation uses mock PayPal responses for testing. To make it fully functional, you need to:

1. **Add PayPal SDK dependency** to `pom.xml`
2. **Replace mock PayPal calls** with actual PayPal SDK integration
3. **Configure PayPal credentials** in application properties

## Integration Flow

1. **Frontend** calls `/api/payment/paypal/create` with domain-specific Origin header
2. **PaymentController** routes to appropriate vertical handler based on domain
3. **Vertical handler** validates pricing config and calls service
4. **Service** creates PayPal payment record with `provider="PAYPAL"`
5. **Service** calls PayPal SDK to create order
6. **Service** updates payment record with PayPal order ID
7. **Frontend** redirects user to PayPal approval URL
8. **User** completes payment on PayPal
9. **Frontend** calls `/api/payment/paypal/capture` with order ID
10. **Service** captures payment and updates record status to "COMPLETED"

This maintains the same architecture and patterns as your existing Stripe integration while adding PayPal support to all verticals.