--liquibase formatted sql

--changeset capacity:005-confirmed-by
-- Who confirmed a hold. Changeset 004 said that a hold already has an updated_at: it does not. A
-- hold records who created it (created_by, created_at), who confirmed it (confirmed_by,
-- confirmed_at) and who closed it (released_by, released_at); each of those is written once, so
-- no updated_by/updated_at is needed. Null until it happens, and for a hold confirmed before this
-- migration (the person is not known).
-- Note on 004: it set a resource's created_at to its updated_at, because the column did not exist.
-- For a resource that was resized or used before 004 that value is later than the real creation, so
-- created_at of an old resource is approximate. It is correct for every resource created after 004.
ALTER TABLE capacity.capacity_reservation ADD COLUMN confirmed_by varchar(100);
--rollback ALTER TABLE capacity.capacity_reservation DROP COLUMN confirmed_by;
