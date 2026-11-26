import os
import pytest
from bourse_ai_ml import repository as db


@pytest.fixture
def connection():
    dsn = os.environ.get("BOURSE_ML_TEST_DSN")
    if not dsn:
        pytest.skip("BOURSE_ML_TEST_DSN requis pour les tests PostgreSQL")
    with db.connect(dsn) as conn:
        name = conn.execute("SELECT current_database() AS name").fetchone()["name"]
        if not name.startswith("bourse_ml_test_"):
            pytest.fail("Tests refusés : utiliser une base jetable nommée bourse_ml_test_...")
        conn.execute("DROP SCHEMA IF EXISTS bourse_ml CASCADE")
        db.initialize(conn)
        yield conn

