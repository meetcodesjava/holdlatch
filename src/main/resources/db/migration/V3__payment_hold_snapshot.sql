-- The signed hold token is kept with the payment attempt so a payment webhook
-- arriving minutes later still knows exactly which seats/tickets it was for.
alter table payment_transactions add column hold_snapshot text not null default '{}';

-- One payment attempt per hold, ever: a retried checkout can never charge twice for the same seats.
drop index ix_payments_hold;
create unique index uq_payments_hold on payment_transactions (hold_id);
