from dataclasses import replace
from datetime import date, datetime, timedelta, timezone

import pytest

from bourse_ai_ml.market import Session, build_events, read_market


def sessions(count):
    start = datetime(2025, 1, 1, 14, tzinfo=timezone.utc)
    return [Session((start + timedelta(days=i)).date(), start + timedelta(days=i),
                    start + timedelta(days=i, hours=6), start + timedelta(days=i, hours=6, minutes=3),
                    100 + i, 101 + i) for i in range(count)]


def test_features_only_use_known_prices_and_labels_use_following_sessions():
    days = sessions(26)
    events = build_events("NVDA", "USD", "TWELVE_DATA", days, 2, 20)
    first = events[0]
    assert first.as_of == days[20].available_at
    assert first.entry_at == days[21].opens_at
    assert first.label_at == days[22].available_at
    assert first.net_return == pytest.approx(days[22].close_price / days[21].open_price - 1 - .002)
    days[22] = replace(days[22], close_price=999)
    changed = build_events("NVDA", "USD", "TWELVE_DATA", days, 2, 20)[0]
    assert changed.features == first.features
    assert changed.net_return != first.net_return
    assert events[-1].net_return is None


def test_missing_expected_session_is_not_silently_skipped():
    days = sessions(28)
    days[21] = replace(days[21], open_price=None, close_price=None)
    events = build_events("NVDA", "USD", "TWELVE_DATA", days, 2, 0)
    assert len(events) == 1
    assert events[0].label_at is None


@pytest.mark.postgres
def test_adapter_reads_backend_schema_without_mutating_it(connection):
    # The standalone PostgreSQL suite may run with or without the backend smoke schema.
    # Temp tables plus a dedicated fixture schema avoid touching shared Java data.
    from bourse_ai_ml import market
    connection.execute("CREATE TEMP TABLE assets(id integer,symbol text,currency text,asset_type text)")
    connection.execute("CREATE TEMP TABLE watchlist(asset_id integer,enabled boolean)")
    connection.execute("CREATE TEMP TABLE asset_market_policies(asset_id integer,calendar_code text,delivery_grace_seconds integer)")
    connection.execute("CREATE TEMP TABLE daily_series_qualifications(asset_id integer,provider text,qualified_at timestamptz,adjustment text)")
    connection.execute("CREATE TEMP TABLE market_sessions(calendar_code text,session_date date,opens_at timestamptz,closes_at timestamptz)")
    connection.execute("CREATE TEMP TABLE market_candles(asset_id integer,timeframe text,provider text,open_time timestamptz,open_price numeric,close_price numeric)")
    connection.execute("CREATE TEMP TABLE provider_price_observations(asset_id integer,timeframe text,provider text,open_time timestamptz,open_price numeric,close_price numeric,currency text)")
    connection.execute("INSERT INTO assets VALUES (1,'NVDA','USD','STOCK'),(2,'MISSING','USD','STOCK')")
    connection.execute("INSERT INTO watchlist VALUES (1,true),(2,true)")
    connection.execute("INSERT INTO asset_market_policies VALUES(1,'test',180)")
    connection.execute("INSERT INTO daily_series_qualifications VALUES(1,'TWELVE_DATA','2024-01-01','SPLIT_ADJUSTED')")
    for s in sessions(26):
        connection.execute("INSERT INTO market_sessions VALUES ('test',%s,%s,%s)", (s.day, s.opens_at, s.closes_at))
        connection.execute("INSERT INTO market_candles VALUES (1,'1d','TWELVE_DATA',%s,%s,%s)",
                           (datetime.combine(s.day, datetime.min.time(), timezone.utc), s.open_price, s.close_price))
    connection.execute("""
        UPDATE market_candles SET provider='EODHD', open_price=900, close_price=900
        WHERE open_time='2025-01-24T00:00:00Z'
    """)
    connection.execute("""
        INSERT INTO provider_price_observations VALUES
        (1,'1d','TWELVE_DATA','2025-01-24T00:00:00Z',123,124,'USD'),
        (1,'1d','TWELVE_DATA','2025-01-25T00:00:00Z',999,999,'USD')
    """)
    class Fixture:
        def execute(self, sql, params):
            assert sql == market.SERIES_SQL
            return connection.execute(sql.replace("public.", "pg_temp."), params)
    result = read_market(Fixture(), date(2025, 1, 1), datetime(2025, 1, 26, 21, tzinfo=timezone.utc), "DAY", 20)
    assert [r["status"] for r in result.coverage] == ["UNQUALIFIED_OR_UNKNOWN", "READY"]
    assert result.coverage[-1]["labels"] == 5
    assert result.events[-1].label_at is None
    fixture = sessions(26)
    expected = build_events("NVDA", "USD", "TWELVE_DATA", fixture, 1, 20)
    assert result.events == expected
