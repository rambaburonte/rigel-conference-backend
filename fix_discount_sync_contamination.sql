-- ========================================================================
-- FIX DISCOUNT SYNC CONTAMINATION
-- ========================================================================
-- This script modifies the database triggers to prevent regular registration 
-- payments from being synced to discount tables.
-- 
-- PROBLEM: Current triggers sync ALL payment updates to discount tables,
-- causing regular registration payments to contaminate discount records.
-- 
-- SOLUTION: Modify triggers to only sync when there's already a discount 
-- record for that session_id (indicating it's actually a discount payment).
-- ========================================================================

-- ====================================
-- 1. FIX NURSING TRIGGERS
-- ====================================

-- Modified function to sync from nursing_payment_records to nursing_discounts
-- ONLY if a discount record already exists (indicating this is a discount payment)
CREATE OR REPLACE FUNCTION sync_nursing_payment_to_discount()
RETURNS TRIGGER AS $$
DECLARE
    discount_exists BOOLEAN := FALSE;
BEGIN
    -- Check if a discount record already exists for this session_id
    SELECT EXISTS(SELECT 1 FROM nursing_discounts WHERE session_id = NEW.session_id) INTO discount_exists;
    
    -- Only sync if this is actually a discount payment (discount record already exists)
    IF discount_exists THEN
        -- Update corresponding discount record if it exists
        UPDATE nursing_discounts 
        SET 
            payment_intent_id = NEW.payment_intent_id,
            status = NEW.status,
            payment_status = NEW.payment_status,
            updated_at = NEW.updated_at,
            amount_total = NEW.amount_total,
            currency = NEW.currency,
            customer_email = NEW.customer_email
        WHERE session_id = NEW.session_id;
        
        -- Log the sync operation
        IF FOUND THEN
            RAISE NOTICE '✅ Synced nursing payment record changes to discount table for session_id: %', NEW.session_id;
        END IF;
    ELSE
        -- Log that we're skipping sync for regular payment
        RAISE NOTICE '🚫 Skipping nursing discount sync for session_id: % - no existing discount record (regular payment)', NEW.session_id;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ====================================
-- 2. FIX OPTICS TRIGGERS
-- ====================================

-- Modified function to sync from optics_payment_records to optics_discounts
-- ONLY if a discount record already exists
CREATE OR REPLACE FUNCTION sync_optics_payment_to_discount()
RETURNS TRIGGER AS $$
DECLARE
    discount_exists BOOLEAN := FALSE;
BEGIN
    -- Check if a discount record already exists for this session_id
    SELECT EXISTS(SELECT 1 FROM optics_discounts WHERE session_id = NEW.session_id) INTO discount_exists;
    
    -- Only sync if this is actually a discount payment (discount record already exists)
    IF discount_exists THEN
        -- Update corresponding discount record if it exists
        UPDATE optics_discounts 
        SET 
            payment_intent_id = NEW.payment_intent_id,
            status = NEW.status,
            payment_status = NEW.payment_status,
            updated_at = NEW.updated_at,
            amount_total = NEW.amount_total,
            currency = NEW.currency,
            customer_email = NEW.customer_email
        WHERE session_id = NEW.session_id;
        
        -- Log the sync operation
        IF FOUND THEN
            RAISE NOTICE '✅ Synced optics payment record changes to discount table for session_id: %', NEW.session_id;
        END IF;
    ELSE
        -- Log that we're skipping sync for regular payment
        RAISE NOTICE '🚫 Skipping optics discount sync for session_id: % - no existing discount record (regular payment)', NEW.session_id;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ====================================
-- 3. FIX RENEWABLE TRIGGERS
-- ====================================

-- Modified function to sync from renewable_payment_records to renewable_discounts
-- ONLY if a discount record already exists
CREATE OR REPLACE FUNCTION sync_renewable_payment_to_discount()
RETURNS TRIGGER AS $$
DECLARE
    discount_exists BOOLEAN := FALSE;
BEGIN
    -- Check if a discount record already exists for this session_id
    SELECT EXISTS(SELECT 1 FROM renewable_discounts WHERE session_id = NEW.session_id) INTO discount_exists;
    
    -- Only sync if this is actually a discount payment (discount record already exists)
    IF discount_exists THEN
        -- Update corresponding discount record if it exists
        UPDATE renewable_discounts 
        SET 
            payment_intent_id = NEW.payment_intent_id,
            status = NEW.status,
            payment_status = NEW.payment_status,
            updated_at = NEW.updated_at,
            amount_total = NEW.amount_total,
            currency = NEW.currency,
            customer_email = NEW.customer_email
        WHERE session_id = NEW.session_id;
        
        -- Log the sync operation
        IF FOUND THEN
            RAISE NOTICE '✅ Synced renewable payment record changes to discount table for session_id: %', NEW.session_id;
        END IF;
    ELSE
        -- Log that we're skipping sync for regular payment
        RAISE NOTICE '🚫 Skipping renewable discount sync for session_id: % - no existing discount record (regular payment)', NEW.session_id;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ====================================
-- 4. FIX POLYMERS TRIGGERS (if they exist)
-- ====================================

-- Check if polymers triggers exist and fix them too
CREATE OR REPLACE FUNCTION sync_polymers_payment_to_discount()
RETURNS TRIGGER AS $$
DECLARE
    discount_exists BOOLEAN := FALSE;
BEGIN
    -- Check if a discount record already exists for this session_id
    SELECT EXISTS(SELECT 1 FROM polymers_discounts WHERE session_id = NEW.session_id) INTO discount_exists;
    
    -- Only sync if this is actually a discount payment (discount record already exists)
    IF discount_exists THEN
        -- Update corresponding discount record if it exists
        UPDATE polymers_discounts 
        SET 
            payment_intent_id = NEW.payment_intent_id,
            status = NEW.status,
            payment_status = NEW.payment_status,
            updated_at = NEW.updated_at,
            amount_total = NEW.amount_total,
            currency = NEW.currency,
            customer_email = NEW.customer_email
        WHERE session_id = NEW.session_id;
        
        -- Log the sync operation
        IF FOUND THEN
            RAISE NOTICE '✅ Synced polymers payment record changes to discount table for session_id: %', NEW.session_id;
        END IF;
    ELSE
        -- Log that we're skipping sync for regular payment
        RAISE NOTICE '🚫 Skipping polymers discount sync for session_id: % - no existing discount record (regular payment)', NEW.session_id;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ====================================
-- 5. VERIFICATION QUERIES
-- ====================================

-- Check existing trigger status
SELECT 
    schemaname,
    tablename,
    triggername,
    definition
FROM pg_triggers 
WHERE triggername LIKE '%sync%' 
AND tablename LIKE '%payment_records%'
ORDER BY schemaname, tablename, triggername;

-- ====================================
-- 6. CLEANUP OLD CONTAMINATED RECORDS (OPTIONAL)
-- ====================================

-- ⚠️ WARNING: Only run these if you want to clean up existing contaminated records
-- These queries will delete discount records that don't have the discount metadata indicators

-- You may want to check these records first before deleting:
/*
SELECT 'NURSING' as service, session_id, customer_email, amount_total, payment_status 
FROM nursing_discounts 
WHERE session_id NOT IN (
    SELECT DISTINCT session_id 
    FROM nursing_discounts 
    WHERE session_id IS NOT NULL
    -- Add additional criteria here to identify legitimate discount records
);

SELECT 'OPTICS' as service, session_id, customer_email, amount_total, payment_status 
FROM optics_discounts 
WHERE session_id NOT IN (
    SELECT DISTINCT session_id 
    FROM optics_discounts 
    WHERE session_id IS NOT NULL
    -- Add additional criteria here to identify legitimate discount records
);

SELECT 'RENEWABLE' as service, session_id, customer_email, amount_total, payment_status 
FROM renewable_discounts 
WHERE session_id NOT IN (
    SELECT DISTINCT session_id 
    FROM renewable_discounts 
    WHERE session_id IS NOT NULL
    -- Add additional criteria here to identify legitimate discount records
);

SELECT 'POLYMERS' as service, session_id, customer_email, amount_total, payment_status 
FROM polymers_discounts 
WHERE session_id NOT IN (
    SELECT DISTINCT session_id 
    FROM polymers_discounts 
    WHERE session_id IS NOT NULL
    -- Add additional criteria here to identify legitimate discount records
);
*/

-- ====================================
-- 7. SUCCESS MESSAGE
-- ====================================

DO $$
BEGIN
    RAISE NOTICE '========================================';
    RAISE NOTICE '✅ DISCOUNT SYNC CONTAMINATION FIX COMPLETE';
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Database triggers have been updated to prevent regular registration payments from syncing to discount tables.';
    RAISE NOTICE 'Only payments with existing discount records will be synced.';
    RAISE NOTICE 'Java service sync methods have also been updated with discount detection logic.';
    RAISE NOTICE '========================================';
END $$;