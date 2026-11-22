# Module 14 — AI Bottleneck Evidence Detection

`POST /api/v1/ai-bottlenecks` records manually reviewed HTTPS evidence for an existing asset.
`GET /api/v1/ai-bottlenecks/NVDA` shows last 180 days and an explainable research index.

Types: SUPPLY_SHORTAGE, CAPACITY_EXPANSION, ORDER_BACKLOG, VALUATION_RISK, EXECUTION_RISK; positive or negative. At least 3 observations and 2 categories are necessary for any score. Source URL/summary/date/confidence are mandatory; duplicates are rejected. **The backend does not fetch URLs or fact-check claims.** A provided link is only an assertion pending human source review. It is not a genuine automatic EARLY detector yet.

No false claims of an 85x return, certain upside, actual supply shortage, or imminent trading opportunity. No implicit linking to real orders. Scheduled source aggregation, financial valuation and SEC document corroboration require later work. Protect writes with authentication. Unit tests via `mvn -B -ntp clean verify`.
