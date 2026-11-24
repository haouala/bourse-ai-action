ALTER TABLE financial_facts DROP CONSTRAINT uq_financial_fact;
ALTER TABLE financial_facts ADD CONSTRAINT uq_financial_fact_period
    UNIQUE NULLS NOT DISTINCT (asset_id, concept, accession_number, period_end, unit, period_start);
