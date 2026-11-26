CREATE TABLE market_technical_analyses (
 asset_id BIGINT NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
 timeframe VARCHAR(8) NOT NULL,
 last_candle_at TIMESTAMP WITH TIME ZONE NOT NULL,
 candle_count INTEGER NOT NULL,
 payload TEXT NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (asset_id,timeframe)
);
