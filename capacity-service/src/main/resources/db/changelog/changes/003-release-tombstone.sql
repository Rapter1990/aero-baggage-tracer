--liquibase formatted sql

--changeset capacity:003-release-tombstone
-- A caller that times out on a hold releases it by id. If the hold is still on its way, the release
-- finds nothing; without a record the hold then commits and nobody owns it. A release of an unknown
-- id therefore writes a RELEASED row under that id (a tombstone): the late hold meets it on the
-- primary key and is refused. A tombstone has no resource, case, bag or slots, so those columns may
-- be empty, but only on a RELEASED row.
ALTER TABLE capacity.capacity_reservation ALTER COLUMN resource_id DROP NOT NULL;
ALTER TABLE capacity.capacity_reservation ALTER COLUMN case_id DROP NOT NULL;
ALTER TABLE capacity.capacity_reservation ALTER COLUMN bag_tag DROP NOT NULL;
ALTER TABLE capacity.capacity_reservation ALTER COLUMN slots DROP NOT NULL;
ALTER TABLE capacity.capacity_reservation ADD CONSTRAINT ck_capacity_reservation_complete CHECK
    (status = 'RELEASED'
     OR (resource_id IS NOT NULL AND case_id IS NOT NULL AND bag_tag IS NOT NULL AND slots IS NOT NULL));
--rollback ALTER TABLE capacity.capacity_reservation DROP CONSTRAINT ck_capacity_reservation_complete; DELETE FROM capacity.capacity_reservation WHERE resource_id IS NULL; ALTER TABLE capacity.capacity_reservation ALTER COLUMN slots SET NOT NULL; ALTER TABLE capacity.capacity_reservation ALTER COLUMN bag_tag SET NOT NULL; ALTER TABLE capacity.capacity_reservation ALTER COLUMN case_id SET NOT NULL; ALTER TABLE capacity.capacity_reservation ALTER COLUMN resource_id SET NOT NULL;
