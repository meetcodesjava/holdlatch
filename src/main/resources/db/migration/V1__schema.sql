-- HoldLatch core schema. Seat HOLDS are not stored here: they live in AeroKV
-- with a TTL. This database only records confirmed outcomes and audit data.

create table users (
    id            uuid         primary key,
    email         varchar(254) not null,
    password_hash varchar(100) not null,
    display_name  varchar(100) not null,
    role          varchar(20)  not null,
    created_at    timestamptz  not null,
    version       bigint       not null,
    constraint ck_users_role check (role in ('CUSTOMER', 'ORGANIZER', 'ADMIN'))
);
create unique index uq_users_email_lower on users (lower(email));

create table events (
    id           uuid         primary key,
    organizer_id uuid         not null references users (id),
    name         varchar(200) not null,
    venue        varchar(200) not null,
    starts_at    timestamptz  not null,
    seating_mode varchar(20)  not null,
    status       varchar(20)  not null,
    created_at   timestamptz  not null,
    version      bigint       not null,
    constraint ck_events_seating_mode check (seating_mode in ('ASSIGNED', 'GENERAL_ADMISSION', 'HYBRID')),
    constraint ck_events_status check (status in ('DRAFT', 'ON_SALE', 'CLOSED'))
);
create index ix_events_status_starts_at on events (status, starts_at);
create index ix_events_organizer on events (organizer_id);

create table sections (
    id          uuid         primary key,
    event_id    uuid         not null references events (id),
    name        varchar(100) not null,
    kind        varchar(20)  not null,
    capacity    integer      not null,
    price_cents bigint       not null,
    currency    varchar(3)   not null,
    created_at  timestamptz  not null,
    version     bigint       not null,
    constraint ck_sections_kind check (kind in ('ASSIGNED', 'GENERAL_ADMISSION')),
    constraint ck_sections_capacity check (capacity > 0),
    constraint ck_sections_price check (price_cents >= 0),
    constraint uq_sections_event_name unique (event_id, name)
);

create table seats (
    id          uuid        primary key,
    event_id    uuid        not null references events (id),
    section_id  uuid        not null references sections (id),
    row_label   varchar(10) not null,
    seat_number integer     not null,
    status      varchar(20) not null,
    version     bigint      not null,
    constraint ck_seats_status check (status in ('AVAILABLE', 'BOOKED')),
    constraint uq_seats_position unique (section_id, row_label, seat_number)
);
create index ix_seats_event on seats (event_id);

create table payment_transactions (
    id                       uuid         primary key,
    user_id                  uuid         not null references users (id),
    event_id                 uuid         not null references events (id),
    hold_id                  varchar(100) not null,
    stripe_payment_intent_id varchar(100),
    state                    varchar(20)  not null,
    amount_cents             bigint       not null,
    currency                 varchar(3)   not null,
    failure_reason           varchar(500),
    created_at               timestamptz  not null,
    updated_at               timestamptz  not null,
    version                  bigint       not null,
    constraint ck_payments_state check (state in
        ('PENDING', 'SUCCEEDED', 'CONFIRMED', 'FAILED', 'REFUND_PENDING', 'REFUNDED')),
    constraint ck_payments_amount check (amount_cents >= 0),
    constraint uq_payments_intent unique (stripe_payment_intent_id)
);
create index ix_payments_hold on payment_transactions (hold_id);
create index ix_payments_user on payment_transactions (user_id);

create table booking_orders (
    id                     uuid        primary key,
    user_id                uuid        not null references users (id),
    event_id               uuid        not null references events (id),
    payment_transaction_id uuid        not null references payment_transactions (id),
    status                 varchar(20) not null,
    total_cents            bigint      not null,
    currency               varchar(3)  not null,
    created_at             timestamptz not null,
    version                bigint      not null,
    constraint ck_orders_status check (status in ('CONFIRMED', 'CANCELLED', 'REFUNDED')),
    constraint uq_orders_payment unique (payment_transaction_id)
);
create index ix_orders_user on booking_orders (user_id, created_at desc);
create index ix_orders_event on booking_orders (event_id);

create table booking_items (
    id               uuid    primary key,
    order_id         uuid    not null references booking_orders (id),
    section_id       uuid    not null references sections (id),
    seat_id          uuid    references seats (id),
    quantity         integer not null,
    unit_price_cents bigint  not null,
    version          bigint  not null,
    constraint ck_items_quantity check (quantity > 0),
    -- A seat can appear on at most one order, ever: the last line of defence
    -- against double-booking if every layer above it failed.
    constraint uq_items_seat unique (seat_id)
);
create index ix_items_order on booking_items (order_id);
create index ix_items_section on booking_items (section_id);

create table idempotency_records (
    id              uuid         primary key,
    user_id         uuid         not null references users (id),
    idem_key        varchar(200) not null,
    request_hash    varchar(64)  not null,
    response_status integer,
    response_body   text,
    created_at      timestamptz  not null,
    version         bigint       not null,
    constraint uq_idempotency_user_key unique (user_id, idem_key)
);

create table outbox_events (
    id              uuid         primary key,
    aggregate_type  varchar(50)  not null,
    aggregate_id    varchar(100) not null,
    event_type      varchar(100) not null,
    payload         jsonb        not null,
    created_at      timestamptz  not null,
    processed_at    timestamptz,
    attempts        integer      not null,
    next_attempt_at timestamptz  not null,
    version         bigint       not null
);
create index ix_outbox_due on outbox_events (next_attempt_at) where processed_at is null;

create table refunds (
    id                     uuid         primary key,
    user_id                uuid         not null references users (id),
    payment_transaction_id uuid         not null references payment_transactions (id),
    amount_cents           bigint       not null,
    currency               varchar(3)   not null,
    reason                 varchar(200) not null,
    status                 varchar(20)  not null,
    stripe_refund_id       varchar(100),
    created_at             timestamptz  not null,
    version                bigint       not null,
    constraint ck_refunds_status check (status in ('PENDING', 'SUCCEEDED', 'FAILED')),
    constraint uq_refunds_stripe unique (stripe_refund_id)
);
create index ix_refunds_payment on refunds (payment_transaction_id);
