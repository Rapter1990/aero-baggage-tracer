--liquibase formatted sql

--changeset identity:005-sample-data context:dev
-- Dummy accounts for local development, one per role plus a second passenger. They are only
-- created when Liquibase runs with the "dev" context (LIQUIBASE_CONTEXTS=dev, which the compose
-- file sets); without it this changeset is skipped, so a real deployment never gets them.
-- Every account has the password "dummy-password-1" (a BCrypt hash, cost 10). The passenger
-- references are fixed, made-up pseudonyms.
INSERT INTO identity.app_user (id, username, email, full_name, password_hash, passenger_ref, enabled) VALUES
    ('00000000-0000-4000-8000-000000000001', 'dummy.passenger',  'dummy.passenger@example.com',  'Dummy Passenger',  '$2a$10$8ZDHaU50K7k6Ok4N/mKAs.1nRvK6L6olmzJuYXcpi09lLMT9KdsGO', 'P-00000000000000000001', true),
    ('00000000-0000-4000-8000-000000000002', 'dummy.passenger2', 'dummy.passenger2@example.com', 'Dummy Passenger Two', '$2a$10$8ZDHaU50K7k6Ok4N/mKAs.1nRvK6L6olmzJuYXcpi09lLMT9KdsGO', 'P-00000000000000000002', true),
    ('00000000-0000-4000-8000-000000000003', 'dummy.agent',      'dummy.agent@example.com',      'Dummy Agent',      '$2a$10$8ZDHaU50K7k6Ok4N/mKAs.1nRvK6L6olmzJuYXcpi09lLMT9KdsGO', NULL, true),
    ('00000000-0000-4000-8000-000000000004', 'dummy.dispatcher', 'dummy.dispatcher@example.com', 'Dummy Dispatcher', '$2a$10$8ZDHaU50K7k6Ok4N/mKAs.1nRvK6L6olmzJuYXcpi09lLMT9KdsGO', NULL, true),
    ('00000000-0000-4000-8000-000000000005', 'dummy.admin',      'dummy.admin@example.com',      'Dummy Admin',      '$2a$10$8ZDHaU50K7k6Ok4N/mKAs.1nRvK6L6olmzJuYXcpi09lLMT9KdsGO', NULL, true);

INSERT INTO identity.user_role (user_id, role) VALUES
    ('00000000-0000-4000-8000-000000000001', 'PASSENGER'),
    ('00000000-0000-4000-8000-000000000002', 'PASSENGER'),
    ('00000000-0000-4000-8000-000000000003', 'AGENT'),
    ('00000000-0000-4000-8000-000000000004', 'DISPATCHER'),
    ('00000000-0000-4000-8000-000000000005', 'ADMIN');
--rollback DELETE FROM identity.app_user WHERE id IN ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000004', '00000000-0000-4000-8000-000000000005');
