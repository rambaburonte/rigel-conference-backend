package com.zn.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayPalCreateOrderRequest {
    
    private String customerEmail;
    private String customerName;
    private String phone;
    private String country;
    private String instituteOrUniversity;
    private BigDecimal amount;
    private String currency;
    private Long pricingConfigId;
    
    // Success and cancel URLs for PayPal redirect
    private String successUrl;
    private String cancelUrl;
}