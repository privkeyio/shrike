-- Deliberately nullable and without a default. A default expression is refused when the wallet file is opened, and
-- NOT NULL would make this file unwritable by a build that does not know the column: its insert does not list it,
-- so adding a keystore would fail on the constraint. Absent reads as unmarked, which is what an older file means.
alter table keystore add column unifiedSigHashSupported boolean after deviceRegistration;
