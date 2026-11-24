CREATE TABLE ml_market_runs (
    run_id VARCHAR(64) PRIMARY KEY,
    horizon VARCHAR(10) NOT NULL CHECK (horizon IN ('DAY', 'MONTH', 'YEAR')),
    data_cutoff TIMESTAMP WITH TIME ZONE NOT NULL,
    summary TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ml_market_runs_latest ON ml_market_runs(horizon, data_cutoff DESC);

CREATE TABLE ml_market_predictions (
    run_id VARCHAR(64) NOT NULL REFERENCES ml_market_runs(run_id),
    symbol VARCHAR(40) NOT NULL,
    as_of TIMESTAMP WITH TIME ZONE NOT NULL,
    currency VARCHAR(3) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    estimate DOUBLE PRECISION NOT NULL CHECK (estimate >= 0 AND estimate <= 1),
    PRIMARY KEY (run_id, symbol)
);

CREATE INDEX idx_ml_market_predictions_latest ON ml_market_predictions(symbol, as_of DESC);
