"""Contracts and chronology: the event outcome is never an input feature."""
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import json
import math

FEATURES = ("technical", "fundamental", "information", "market_momentum", "volatility")
HORIZONS = {"DAY": 1, "MONTH": 21, "YEAR": 252}
SOURCE = "SYNTHETIC_DEMO"
GENERATOR_VERSION = "synthetic-v1"
TRAINER_VERSION = "chronological-logistic-v1"


class DataError(ValueError):
    pass


def canonical(value) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def digest(value) -> str:
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def utc(value: str) -> datetime:
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None or result.utcoffset() is None:
        raise DataError("Un horodatage avec fuseau est obligatoire")
    return result.astimezone(timezone.utc)


@dataclass(frozen=True)
class DatasetSpec:
    seed: int = 42
    assets: int = 8
    horizon: str = "MONTH"
    cost_bps: float = 20.0
    start: str = "2003-01-02"
    end: str = "2025-12-31"

    def __post_init__(self):
        if self.horizon not in HORIZONS or not 1 <= self.assets <= 100:
            raise DataError("Horizon invalide ou nombre d'actifs hors de 1..100")
        if not isinstance(self.seed, int) or not 0 <= self.seed <= 1_000_000:
            raise DataError("Graine invalide")
        if not math.isfinite(self.cost_bps) or not 0 <= self.cost_bps <= 1000:
            raise DataError("Coût total aller-retour hors de 0..1000 points de base")
        try:
            a, b = utc(self.start + "T00:00:00Z"), utc(self.end + "T00:00:00Z")
        except (ValueError, TypeError) as exc:
            raise DataError("Dates de démonstration invalides") from exc
        if a >= b or a.year < 2000 or b.year > 2030:
            raise DataError("Période de démonstration invalide")

    def metadata(self) -> dict:
        return {**asdict(self), "source": SOURCE, "generator_version": GENERATOR_VERSION,
                "features": list(FEATURES), "calendar": "DEMO_WEEKDAYS_NOT_EXCHANGE",
                "target": "POSITIVE_FORWARD_NET_RETURN", "currency": "EUR", "fx": 1,
                "dividends": 0, "horizon_demo_sessions": HORIZONS[self.horizon]}

    @property
    def dataset_id(self) -> str:
        return digest(self.metadata())


@dataclass(frozen=True)
class Observation:
    symbol: str
    as_of: datetime
    feature_available_at: datetime
    entry_at: datetime
    label_at: datetime
    features: tuple[float, ...]
    baseline_score: float
    entry_price: float
    exit_price: float
    net_return: float
    adverse_close_return: float
    stress_tag: str  # Evaluation metadata ONLY, never included in FEATURES.

    @property
    def is_up(self) -> int:
        return int(self.net_return > 0)

    def payload(self) -> dict:
        return {**asdict(self), "as_of": self.as_of.isoformat(),
                "feature_available_at": self.feature_available_at.isoformat(),
                "entry_at": self.entry_at.isoformat(), "label_at": self.label_at.isoformat()}


def validate(rows: list[Observation], cutoff: datetime) -> None:
    if cutoff.tzinfo is None or not rows:
        raise DataError("Jeu vide ou date de disponibilité sans fuseau")
    seen = set()
    for row in rows:
        if any(x.tzinfo is None or x.utcoffset() is None for x in
               (row.as_of, row.feature_available_at, row.entry_at, row.label_at)):
            raise DataError("Horodatage sans fuseau")
        if not row.feature_available_at <= row.as_of < row.entry_at <= row.label_at <= cutoff:
            raise DataError("Information future ou résultat pas encore arrivé à échéance")
        if len(row.features) != len(FEATURES) or not all(math.isfinite(v) for v in row.features):
            raise DataError("Composantes absentes ou non finies")
        if not all(math.isfinite(v) for v in (row.baseline_score, row.entry_price, row.exit_price,
                                             row.net_return, row.adverse_close_return)):
            raise DataError("Valeurs non finies")
        if not row.symbol.startswith("DEMO_") or not 0 <= row.baseline_score <= 100:
            raise DataError("Ce lot accepte uniquement les actifs fictifs DEMO_")
        if row.entry_price <= 0 or row.exit_price <= 0 or row.net_return < -1:
            raise DataError("Prix ou rendement invalide")
        key = (row.symbol, row.as_of)
        if key in seen:
            raise DataError("Observation dupliquée")
        seen.add(key)
