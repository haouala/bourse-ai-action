# Module 12 — Portfolio Risk Management (stateless research preview)

POST /api/v1/portfolio-risk/analyze with a hypothetical basket:

```json
{"holdings":[{"symbol":"NVDA","quantity":2},{"symbol":"AMD","quantity":1.5}]}
```

**Not a trading or brokerage integration.** The API reads canonical daily prices and returns latest mark-to-market value, weights, largest position, Herfindahl concentration (HHI), historical annualized log-return volatility, maximum 60-session drawdown, and empirical 95th-percentile one-session loss.

Safety and limitations:
- 1..10 distinct positions, positive quantities, at least 61 *common* UTC daily price dates, valid quality gate and non-stale quotes (within seven calendar days).
- **Only same-currency holdings**: mixed USD/EUR portfolios fail closed until point-in-time FX conversion and risk modeling are developed; do not add raw prices across currencies.
- Equal fixed quantities across the 60-session portfolio history, no deposits, withdrawals, rebalancing, leverage, derivatives, dividend reinvestment, fees or tax.
- Downside estimates describe *observed historical losses*, not maximum future loss or a confidence guarantee. 60 returns is an extremely small sample for estimating tail risk. Historical 95th-percentile loss is not statistically dependable at 60 returns.
- Market date alignment by UTC date may be inaccurate for assets listed in separate exchanges. Do not infer close-to-close simultaneous execution.
- No trades are placed or portfolio holdings persisted. Input is hypothetical; the POST merely accepts a JSON body for read-only analysis.
- Production access controls and better FX, calendar and corporate-action modeling remain required.

Test with `mvn -B -ntp clean verify` from `bourse-ai-engine`.
