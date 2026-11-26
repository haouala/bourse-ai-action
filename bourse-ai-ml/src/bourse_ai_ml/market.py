"""Read the Java-managed daily series and verified sessions from PostgreSQL."""
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta
import math
import re
from statistics import pstdev

from .domain import DataError, HORIZONS
from .technical import FEATURES as TECHNICAL_FEATURES, features as technical_features

LOOKBACK = 20
PRICE_FEATURES = ("return_5", "return_20", "volatility_20", "close_to_sma_20")

SERIES_SQL = """
    WITH selected AS (
        SELECT a.id, a.symbol, a.currency, p.calendar_code, p.delivery_grace_seconds,
               q.provider, q.qualified_at
        FROM public.assets a
        LEFT JOIN public.watchlist w ON w.asset_id=a.id
        LEFT JOIN public.asset_market_policies p ON p.asset_id=a.id
        LEFT JOIN public.daily_series_qualifications q ON q.asset_id=a.id
            AND q.adjustment='SPLIT_ADJUSTED'
        WHERE a.asset_type='STOCK'
          AND (CASE WHEN %s THEN a.symbol=ANY(%s) ELSE w.enabled IS TRUE END)
        ORDER BY a.symbol LIMIT 101
    )
    SELECT a.symbol,a.currency,a.provider,s.session_date,s.opens_at,s.closes_at,
           a.delivery_grace_seconds,
           COALESCE(c.open_price,o.open_price) AS open_price,
           COALESCE(c.close_price,o.close_price) AS close_price,
           COALESCE(c.high_price,o.high_price) AS high_price,
           COALESCE(c.low_price,o.low_price) AS low_price,
           COALESCE(c.volume,o.volume) AS volume
    FROM selected a
    LEFT JOIN public.market_sessions s ON s.calendar_code=a.calendar_code
        AND s.opens_at IS NOT NULL AND s.session_date BETWEEN %s AND %s
        AND s.closes_at + a.delivery_grace_seconds * INTERVAL '1 second' <= %s
    LEFT JOIN public.market_candles c ON c.asset_id=a.id AND c.timeframe='1d'
        AND c.provider=a.provider AND a.qualified_at<=%s
        AND c.open_time=(s.session_date::timestamp AT TIME ZONE 'UTC')
    LEFT JOIN public.provider_price_observations o ON c.open_time IS NULL
        AND a.provider='TWELVE_DATA' AND o.provider=a.provider AND o.asset_id=a.id
        AND o.currency=a.currency AND o.timeframe='1d' AND a.qualified_at<=%s
        AND o.open_time=(s.session_date::timestamp AT TIME ZONE 'UTC')
    ORDER BY a.symbol,s.session_date
"""


@dataclass(frozen=True)
class Session:
    day: date
    opens_at: datetime
    closes_at: datetime
    available_at: datetime
    open_price: float | None
    close_price: float | None
    high_price: float | None = None
    low_price: float | None = None
    volume: float | None = None


@dataclass(frozen=True)
class Event:
    symbol: str
    currency: str
    provider: str
    day: date
    as_of: datetime
    features: tuple[float | None, ...]
    entry_at: datetime | None = None
    label_at: datetime | None = None
    net_return: float | None = None


@dataclass(frozen=True)
class MarketDataset:
    events: list[Event]
    coverage: list[dict]
    feature_names: tuple[str, ...] = PRICE_FEATURES


def build_events(symbol, currency, provider, sessions, horizon, cost_bps):
    result = []
    for index in range(LOOKBACK, len(sessions)):
        window = sessions[index - LOOKBACK:index + 1]
        if any(s.close_price is None or not math.isfinite(s.close_price) or s.close_price <= 0 for s in window):
            continue
        closes = [s.close_price for s in window]
        close = closes[-1]
        returns = [b / a - 1 for a, b in zip(closes[:-1], closes[1:], strict=True)]
        features = (
            close / closes[-6] - 1, close / closes[0] - 1,
            pstdev(returns), close / (sum(closes[1:]) / LOOKBACK) - 1,
        )
        features += technical_features(sessions[max(0, index-249):index+1])
        signal = window[-1]
        future = sessions[index + 1:index + 1 + horizon]
        if (len(future) == horizon and future[0].opens_at > signal.available_at
                and all(s.open_price is not None and s.close_price is not None
                        and math.isfinite(s.open_price) and math.isfinite(s.close_price)
                        and s.open_price > 0 and s.close_price > 0 for s in future)):
            entry, exit_session = future[0], future[-1]
            net = exit_session.close_price / entry.open_price - 1 - cost_bps / 10_000
            result.append(Event(symbol, currency, provider, signal.day, signal.available_at, features,
                                entry.opens_at, exit_session.available_at, net))
        else:
            result.append(Event(symbol, currency, provider, signal.day, signal.available_at, features))
    return result


def read_market(conn, start, cutoff, horizon, cost_bps, symbols=()):
    if (cutoff.utcoffset() is None or start >= cutoff.date() or horizon not in HORIZONS
            or not math.isfinite(cost_bps) or not 0 <= cost_bps <= 1000):
        raise DataError("Dates, horizon ou coûts de données réelles invalides")
    symbols = sorted({s.upper() for s in symbols})
    if len(symbols) > 100 or any(not re.fullmatch(r"[A-Z0-9._-]{1,40}", s) for s in symbols):
        raise DataError("Maximum de 100 symboles valides par calcul")
    records = conn.execute(SERIES_SQL, (bool(symbols), symbols, start, cutoff.date(), cutoff, cutoff, cutoff)).fetchall()
    grouped = defaultdict(list)
    for row in records:
        grouped[row["symbol"]].append(row)
    if len(grouped) > 100:
        raise DataError("La sélection dépasse 100 actions")
    events, coverage = [], []
    for symbol in sorted(set(symbols) | grouped.keys()):
        records = grouped[symbol]
        provider = records[0]["provider"] if records else None
        sessions = [
            Session(r["session_date"], r["opens_at"], r["closes_at"],
                    r["closes_at"] + timedelta(seconds=r["delivery_grace_seconds"]),
                    float(r["open_price"]) if r["open_price"] is not None else None,
                    float(r["close_price"]) if r["close_price"] is not None else None,
                    float(r["high_price"]) if r["high_price"] is not None else None,
                    float(r["low_price"]) if r["low_price"] is not None else None,
                    float(r["volume"]) if r["volume"] is not None else None)
            for r in records if r["session_date"] is not None
        ]
        rows = build_events(symbol, records[0]["currency"], provider, sessions, HORIZONS[horizon], cost_bps) if sessions and provider else []
        events.extend(rows)
        coverage.append({"symbol": symbol, "provider": provider,
                         "expectedSessions": len(sessions),
                         "priceSessions": sum(s.close_price is not None for s in sessions),
                         "lastExpectedDate": sessions[-1].day.isoformat() if sessions else None,
                         "observations": len(rows), "labels": sum(r.label_at is not None for r in rows),
                         "technicalObservations": sum(r.features[-1] == 1. for r in rows),
                         "status": ("READY" if any(r.label_at is not None for r in rows)
                                    else "INSUFFICIENT_HISTORY" if provider else "UNQUALIFIED_OR_UNKNOWN")})
    return MarketDataset(sorted(events, key=lambda r: (r.day, r.symbol)), coverage, PRICE_FEATURES + TECHNICAL_FEATURES)
