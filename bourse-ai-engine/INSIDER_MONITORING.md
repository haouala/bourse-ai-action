# Module 4 — Insider Monitoring / SEC Form 4

Collect SEC Form 4 reports of directors and officers (non-derivative transaction table), store an immutable-source transaction ledger, and expose cautious monitoring signals.

## API

```bash
# Asset must already exist; configure a real SEC_USER_AGENT.
curl -X POST 'http://localhost:8081/api/v1/insiders/sync/NVDA?cik=1045810&maxFilings=15'
curl 'http://localhost:8081/api/v1/insiders/NVDA?limit=100'
curl 'http://localhost:8081/api/v1/insiders/NVDA/alerts?days=90'
```

The sync API fetches issuer submissions from `data.sec.gov/submissions/CIK##########.json`, then supported Form 4 XML filings from `www.sec.gov/Archives`. No key; the SEC requires an honest contactable User-Agent. Config: `SEC_USER_AGENT`. Official filing URL and accession identifier are retained. Duplicate imports are idempotent.

## Interpret correctly

- `S` with disposition `D` indicates an explicitly reported sale, and `P` with acquisition `A` indicates a purchase. Other transaction codes are kept but **not** counted as open-market sales/purchases.
- **Insider sale != illegal insider trading**. Form 4 can contain 10b5-1 scheduled transactions, tax-related transactions, gifts, exercises, options and indirect ownership.
- The alert level for one reported sale is merely informational. Three or more sales by the same owner in the configured interval cause a review flag; it does not forecast returns.
- Form 4 **non-derivative transactions only** in this first version. Derivative tables, 10b5-1 plan footnotes, amendments reconciliation and historical submission archive pagination are **not yet implemented**. The import skips filings that do not point to XML as their primary document.
- Shares remaining is not always the person's total economic exposure; trusts and other reporting entities matter. Do not calculate percentages liquidated unless holdings are reliably identified.
- No portfolio orders or risk scores are modified by this module.

## Security and operational limits

- XML parser disables DTD/external entities. Requests are restricted to the configured SEC archive origin, without redirects; files are limited to 2 MB.
- Maximum 30 filing attempts per explicit sync. SEC may respond with 403/429; no uncontrolled retries are attempted.
- SEC sync calls should be rate-limited and authenticated before production; they are currently development endpoints.
- Failed or unsupported filings are counted as skipped, not silently interpreted as a clean record.
- Validated CIK is required to avoid associating reports to the wrong ticker.
- No scheduler is enabled yet. A later integration can periodically collect within SEC rate limits.

## Validation

```bash
cd bourse-ai-engine
mvn -B -ntp clean verify
```
