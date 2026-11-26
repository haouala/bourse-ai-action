-- Verified exchange listing date, not the date of the first stored candle.
ALTER TABLE assets ADD COLUMN first_trading_date DATE;
