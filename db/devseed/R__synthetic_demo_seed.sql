INSERT INTO original_transaction (
    id, tenant_id, external_reference_token, public_reference_token,
    operation_type, amount, currency, status,
    occurred_at, created_at, updated_at, version
) VALUES
    ('10000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001',
     '6750461df0c5a4164af02eb5ba74ae1542a35a99b2b10f2bc00043cd34b44c77',
     'synth-txn-card-0001',
     'CARD_PURCHASE', 120.0000, 'ZAR', 'POSTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('10000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000001',
     '948ca2c7deb7e40bd3ac050973138e8481a612727519e99500d8315798406d17',
     'synth-txn-transfer-001',
     'ACCOUNT_TRANSFER', 1250.0000, 'ZAR', 'POSTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('10000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000000001',
     '4523f1fafa7178ee3f5fbf04ee1dd062036f33a8f6af0148965b9178f6acee2b',
     'synth-txn-cash-0001',
     'CASH_WITHDRAWAL', 500.0000, 'ZAR', 'POSTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('10000000-0000-4000-8000-000000000004', '00000000-0000-0000-0000-000000000001',
     'f6de6c34477edcd339244ece39faefe73f518460f2efc9c3644796bcdbe82438',
     'synth-txn-merchant-01',
     'MERCHANT_PAYOUT', 840.0000, 'ZAR', 'POSTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT DO NOTHING;

INSERT INTO operation_policy (
    id, tenant_id, operation_type, policy_version, status, max_attempts,
    initial_retry_delay_ms, maximum_retry_delay_ms, result_timeout_ms,
    effective_from, created_by, submitted_by, approved_by, created_at, updated_at, version
) VALUES
    ('20000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001',
     'CARD_PURCHASE', 1, 'ACTIVE', 3, 500, 10000, 5000, CURRENT_TIMESTAMP,
     'synthetic-policy-author', 'synthetic-policy-author', 'synthetic-policy-approver',
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('20000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000001',
     'ACCOUNT_TRANSFER', 1, 'ACTIVE', 3, 500, 10000, 5000, CURRENT_TIMESTAMP,
     'synthetic-policy-author', 'synthetic-policy-author', 'synthetic-policy-approver',
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('20000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000000001',
     'CASH_WITHDRAWAL', 1, 'ACTIVE', 3, 500, 10000, 5000, CURRENT_TIMESTAMP,
     'synthetic-policy-author', 'synthetic-policy-author', 'synthetic-policy-approver',
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('20000000-0000-4000-8000-000000000004', '00000000-0000-0000-0000-000000000001',
     'MERCHANT_PAYOUT', 1, 'ACTIVE', 3, 500, 10000, 5000, CURRENT_TIMESTAMP,
     'synthetic-policy-author', 'synthetic-policy-author', 'synthetic-policy-approver',
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT DO NOTHING;
