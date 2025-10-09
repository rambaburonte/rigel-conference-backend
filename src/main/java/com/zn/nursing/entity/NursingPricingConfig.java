package com.zn.nursing.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Data;

@Entity
@Data
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class NursingPricingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
 
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "presentation_type_id", nullable = false)
    private NursingPresentationType presentationType;

    @ManyToOne
    @JoinColumn(name = "accommodation_option_id")
    private NursingAccommodation accommodationOption;

    @Column(nullable = false)
    private double processingFeePercent;

    @Column(nullable = false)
    private BigDecimal totalPrice;

    @PrePersist
    @PreUpdate
    public void calculateTotalPrice() {
        if (presentationType == null || presentationType.getPrice() == null) {
            throw new IllegalStateException("Presentation type or its price must not be null.");
        }

        BigDecimal presPrice = presentationType.getPrice();
        BigDecimal accPrice = (accommodationOption != null && accommodationOption.getPrice() != null)
                ? accommodationOption.getPrice()
                : BigDecimal.ZERO;

        BigDecimal subtotal = presPrice.add(accPrice);
        BigDecimal fee = subtotal.multiply(BigDecimal.valueOf(processingFeePercent / 100.0));
        this.totalPrice = subtotal.add(fee).setScale(2, RoundingMode.HALF_UP);
    }
}
