-- Confirmed standing-room tickets per section. Updated only by a conditional
-- UPDATE (sold_quantity + n <= capacity), so the database itself refuses to
-- sell more standing tickets than the section holds.
alter table sections add column sold_quantity integer not null default 0;
alter table sections add constraint ck_sections_sold check (sold_quantity >= 0 and sold_quantity <= capacity);
