CREATE TABLE bourse_ml.datasets (
    dataset_id char(64) PRIMARY KEY,
    source text NOT NULL CHECK (source = 'SYNTHETIC_DEMO'),
    metadata jsonb NOT NULL,
    data_digest char(64) NOT NULL,
    row_count integer NOT NULL CHECK (row_count > 0),
    created_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE bourse_ml.observations (
    dataset_id char(64) NOT NULL REFERENCES bourse_ml.datasets,
    symbol text NOT NULL CHECK (symbol ~ '^DEMO_[0-9]{3}$'),
    as_of timestamptz NOT NULL,
    feature_available_at timestamptz NOT NULL,
    entry_at timestamptz NOT NULL,
    label_at timestamptz NOT NULL,
    features jsonb NOT NULL CHECK (jsonb_typeof(features) = 'array' AND jsonb_array_length(features) = 5),
    baseline_score double precision NOT NULL CHECK (baseline_score BETWEEN 0 AND 100),
    entry_price double precision NOT NULL CHECK (entry_price > 0 AND entry_price < 'Infinity'::float8),
    exit_price double precision NOT NULL CHECK (exit_price > 0 AND exit_price < 'Infinity'::float8),
    net_return double precision NOT NULL CHECK (net_return >= -1 AND net_return < 'Infinity'::float8),
    adverse_close_return double precision NOT NULL CHECK (adverse_close_return >= -1 AND adverse_close_return < 'Infinity'::float8),
    stress_tag text NOT NULL,
    created_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (dataset_id, symbol, as_of),
    CHECK (feature_available_at <= as_of AND as_of < entry_at AND entry_at <= label_at)
);
CREATE INDEX observations_dates ON bourse_ml.observations(dataset_id, as_of, label_at);
CREATE TABLE bourse_ml.runs (
    run_id uuid PRIMARY KEY,
    dataset_id char(64) NOT NULL REFERENCES bourse_ml.datasets,
    configuration jsonb NOT NULL,
    status text NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    attempts integer NOT NULL CHECK (attempts > 0),
    summary jsonb,
    error_code text,
    created_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, dataset_id)
);
CREATE TABLE bourse_ml.models (
    run_id uuid NOT NULL REFERENCES bourse_ml.runs,
    fold text NOT NULL,
    status text NOT NULL CHECK (status = 'DEMO_ONLY'),
    promotion_allowed boolean NOT NULL CHECK (NOT promotion_allowed),
    model jsonb NOT NULL,
    metrics jsonb NOT NULL,
    stress_results jsonb NOT NULL,
    created_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, fold)
);
CREATE TABLE bourse_ml.predictions (
    run_id uuid NOT NULL,
    fold text NOT NULL,
    dataset_id char(64) NOT NULL,
    symbol text NOT NULL,
    as_of timestamptz NOT NULL,
    estimate double precision NOT NULL CHECK (estimate BETWEEN 0 AND 1),
    created_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, fold, symbol, as_of),
    FOREIGN KEY (run_id, fold) REFERENCES bourse_ml.models,
    FOREIGN KEY (run_id, dataset_id) REFERENCES bourse_ml.runs(run_id, dataset_id),
    FOREIGN KEY (dataset_id, symbol, as_of) REFERENCES bourse_ml.observations
);
