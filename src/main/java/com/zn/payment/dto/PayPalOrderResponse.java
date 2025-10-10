package com.zn.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayPalOrderResponse {
    
    private String orderId;
    private String status;
    private String approvalUrl;
    private String customerEmail;
    private String amount;
    private String currency;
    private String provider;
    private String paymentStatus;
    private boolean success;
    private String errorMessage;
    
    // Static factory methods for success and error responses
    public static PayPalOrderResponse success(String orderId, String approvalUrl, String customerEmail, 
                                            String amount, String currency) {
        return PayPalOrderResponse.builder()
                .orderId(orderId)
                .status("CREATED")
                .approvalUrl(approvalUrl)
                .customerEmail(customerEmail)
                .amount(amount)
                .currency(currency)
                .provider("PAYPAL")
                .paymentStatus("unpaid")
                .success(true)
                .build();
    }
    
    public static PayPalOrderResponse error(String errorMessage) {
        return PayPalOrderResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .provider("PAYPAL")
                .paymentStatus("failed")
                .build();
    }
    
    public static PayPalOrderResponse cancelled(String orderId) {
        return PayPalOrderResponse.builder()
                .orderId(orderId)
                .status("CANCELLED")
                .provider("PAYPAL")
                .paymentStatus("cancelled")
                .success(false)
                .errorMessage("Payment cancelled by user")
                .build();
    }
}