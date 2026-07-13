# Gateway durable outbox

Mount `runtime-data/outbox` at `/var/lib/tokensea-gateway/outbox` in production.
The gateway writes a mode `0600`, fsync-backed append-only WAL here whenever
PostgreSQL or Redis accounting compensation is required. The directory must be
private to the gateway process and placed on durable storage. Compose volume
wiring is intentionally left to the deployment change that owns infrastructure.

DNS snapshot checks reduce rebinding risk before LiteLLM registration, but they
cannot constrain LiteLLM's independent second DNS resolution. Production still
requires a destination-pinning egress proxy or equivalent network policy.
