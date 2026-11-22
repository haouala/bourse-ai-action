# Module 15 — Paper Trading

No real broker connection, credentials or executions. Create a virtual USD cash account:

```bash
curl -X POST http://localhost:8081/api/v1/paper/accounts -H 'Content-Type: application/json' -d '{"name":"demo_account_1","initialCash":1000}'
curl -X POST http://localhost:8081/api/v1/paper/orders -H 'Content-Type: application/json' -d '{"accountId":1,"clientOrderId":"my-test-001","symbol":"NVDA","side":"BUY","quantity":1}'
curl http://localhost:8081/api/v1/paper/accounts/1/snapshot
curl http://localhost:8081/api/v1/paper/accounts/1/trades
```

- Simulated fills use the latest imported canonical daily close, **not executable real-time bid/ask**; a 0.10% virtual fee is charged on both buys and sells.
- Account balance and holdings are updated transactionally. Idempotency key `clientOrderId` is unique per account. Insufficient cash, short sales and unsupported currencies are rejected.
- The model uses a USD-only cash equity portfolio; no margin, shorting, corporate actions, dividends, FX, taxes, partial fills or settlement.
- P&L is mark-to-market using last known closes; stale values or split adjustments can be misleading.
- POST endpoints are operational: protect with authentication/authorization and resource quotas before production. They cannot access an external brokerage API.
- **Not yet automatically coupled to the Trade Decision Guard.** A user can submit manual paper orders regardless of research score; no real trading is allowed.
- Test via `mvn -B -ntp clean verify`.
