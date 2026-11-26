# Module 5 — Stock Relationship Graph

A **business relationship graph**, not a price contagion predictor.

## API

- `POST /api/v1/relationships` — manually register an evidenced business relation (administrative operation).
- `GET /api/v1/relationships/NVDA` — direct active relationships.
- `GET /api/v1/relationships/NVDA?includeRetracted=true` — audit view.
- `GET /api/v1/relationships/NVDA/graph?depth=2` — bounded two-hop graph: nodes and directed edges.
- `POST /api/v1/relationships/{id}/retract` — mark an assertion retracted; never silently erase provenance.

For a development fixture with both assets already registered:

```bash
curl -X POST http://localhost:8081/api/v1/relationships \
 -H 'Content-Type: application/json' \
 -d '{"fromSymbol":"NVDA","toSymbol":"TSM","relationshipType":"CUSTOMER_OF","confidence":"MEDIUM","evidenceUrl":"https://example.org/replace-with-official-document","evidenceSummary":"Replace with verified evidence before using in any analysis","evidenceDate":"2026-11-01"}'
```

The JSON above is only an **illustration**; the URL is not evidence and must not be stored as verified production data.

## Relationship types and meaning

- `SUPPLIES_TO`: **from** supplier **to** customer.
- `CUSTOMER_OF`: **from** customer **to** supplier.
- `OWNS_STAKE_IN`: from shareholder **to** company.
- `COMPETES_WITH`, `PARTNER_OF`, `SECTOR_PEER`: symmetric relationships; canonicalized by ticker.

Confidence (`LOW|MEDIUM|HIGH`) represents **source evidence quality**, not correlation strength, direction of stock movement, probability of profit, or forecast accuracy.

Every relationship requires a source HTTPS URL, evidence summary, evidence date, and explicit endpoints already present in `assets`. Source documents are *not fetched or verified by this service*: an analyst or trusted integration must verify them first.

Historical relationships can be bounded by `validFrom`/`validUntil`; current graph queries filter invalid and retracted assertions. Graph traversal deliberately follows links either direction for discovery but preserves the original link orientation and type in API responses. Maximum depth 2, maximum 200 nodes and 500 edges. Bigger searches fail safely.

## Deployment limitations

- No source ingestion/crawling or entity resolution is provided yet.
- No automatic inferred corporate relation or unsupported claims are generated.
- No statistical correlation, lag analysis, market contagion or order blocking in module 5.
- Add authentication and authorization for create/retract endpoints **before production**.
- Source link provenance is recorded, but its validity and accuracy must be reviewed outside the app.
- A symbol refers to an asset on the existing canonical asset table, not a permanent corporate entity identifier (ticker changes, share classes, mergers must be addressed before production).
- Java 21 + Spring Boot, PostgreSQL Flyway V8; tests use the existing H2-compatible suite.

```bash
cd bourse-ai-engine
mvn -B -ntp clean verify
```
