"""Causal TECHNICAL_FEATURES_V1, mirrored by Java and checked against shared fixtures.

Rows contain every verified session, including missing prices. No future session is read.
Missing features remain None; imputation is fitted on the training partition only.
"""
from decimal import Decimal
from math import isfinite, sqrt

VERSION = "TECHNICAL_FEATURES_V1"
FEATURES = ("hammer", "doji", "engulf_bull", "engulf_bear", "confirmed_bull", "confirmed_bear",
            "relative_volume_20", "volume_available", "triple_bottom", "triple_top",
            "elliott_impulse", "elliott_abc", "donchian_breakout", "atr14_pct",
            "bollinger_position", "bollinger_width", "sma20_50_gap", "ohlc_available")


def valid(b):
    values = (b.open_price, b.high_price, b.low_price, b.close_price)
    return all(v is not None and isfinite(v) for v in values) and (
        0 < b.low_price <= min(b.open_price, b.close_price)
        and b.high_price >= max(b.open_price, b.close_price))


def shape(rows, index):
    """Match Java's decimal body/wick comparisons and exclude a previous doji from engulfing."""
    if index < 4:
        return set()
    b = rows[index]
    o, h, l, c = (Decimal(str(v)) for v in (b.open_price, b.high_price, b.low_price, b.close_price))
    span, body = h-l, abs(c-o)
    if not span:
        return set()
    if body <= span * Decimal('.10'):
        return {"doji"}
    previous = [r.close_price for r in rows[index-4:index]]
    down = all(a > b for a, b in zip(previous, previous[1:]))
    up = all(a < b for a, b in zip(previous, previous[1:]))
    result = set()
    if down and body <= span*Decimal('.33') and min(o, c)-l >= body*2 and h-max(o, c) <= body*Decimal('.5'):
        result.add("hammer")
    p = rows[index-1]
    po, ph, pl, pc = (Decimal(str(v)) for v in (p.open_price, p.high_price, p.low_price, p.close_price))
    if abs(pc-po) <= (ph-pl)*Decimal('.10'):
        return result
    if down and pc < po and c > o and o <= pc and c >= po and body > abs(pc-po):
        result.add("engulf_bull")
    if up and pc > po and c < o and o >= pc and c <= po and body > abs(pc-po):
        result.add("engulf_bear")
    return result


def pivots(closes):
    result = []
    for i in range(2, len(closes)-2):
        neighbours = closes[i-2:i]+closes[i+1:i+3]
        kind = -1 if all(closes[i] < x for x in neighbours) else 1 if all(closes[i] > x for x in neighbours) else 0
        if not kind:
            continue
        pivot = (i, kind, closes[i], i+2)
        if result and result[-1][1] == kind:
            if (pivot[2]-result[-1][2])*kind > 0:
                result[-1] = pivot
        else:
            result.append(pivot)
    return result


def alternating(points, sign):
    return all(p[1]*sign == (-1 if i % 2 == 0 else 1) for i, p in enumerate(points))


def impulse(points, sign):
    if not alternating(points, sign):
        return False
    v = [p[2]*sign for p in points]
    return (v[1] > v[0] and v[3] > v[2] and v[5] > v[4] and v[2] < v[1] and v[4] < v[3]
            and v[2] > v[0] and v[3] > v[1] and v[4] > v[1] and v[5] > v[3]
            and v[3]-v[2] >= min(v[1]-v[0], v[5]-v[4]))


def triple(closes, points, sign):
    if len(closes) < 20 or len(points) < 5:
        return 0.
    p = points[-5:]
    if not alternating(p, sign) or len(closes)-1-p[-1][3] > 20 or p[0][0] < 4:
        return 0.
    lows = [p[i][2]*sign for i in (0, 2, 4)]
    scale, floor, ceiling = abs(sum(lows)/3), min(lows), max(lows)
    peaks = [p[i][2]*sign for i in (1, 3)]
    if (ceiling-floor > scale*.02 or min(peaks) < ceiling+scale*.02
            or closes[p[0][0]-4]*sign < ceiling+scale*.03):
        return 0.
    breakout = None
    for i in range(p[-1][0]+1, len(closes)):
        price = closes[i]*sign
        if price < floor-scale*.01:
            return 0.
        if i >= p[-1][3] and breakout is None and price > max(peaks):
            breakout = i
    return float(breakout is not None and len(closes)-1-breakout <= 3)


def elliott(closes, points, sign, correction):
    count = 9 if correction else 6
    if len(closes) < 20 or len(points) < count:
        return False
    p = points[-count:]
    if len(closes)-1-p[-1][3] > 3 or not impulse(p[:6], sign):
        return False
    if correction:
        if not alternating(p, sign):
            return False
        top, a, b, c = (p[i][2]*sign for i in (5, 6, 7, 8))
        if not (a < top and a < b < top and p[0][2]*sign < c < a):
            return False
    return True


def features(rows):
    rows = rows[-250:]
    start = 0
    for i, row in enumerate(rows):
        if not valid(row):
            start = i+1
    rows = rows[start:]
    n = len(rows)
    f = dict.fromkeys(FEATURES)
    f.update(ohlc_available=float(n >= 5), volume_available=0.)
    if not n:
        return tuple(f[k] for k in FEATURES)
    closes = [r.close_price for r in rows]
    if n >= 5:
        current = shape(rows, n-1)
        for key in FEATURES[:4]:
            f[key] = float(key in current)
        previous = shape(rows, n-2)
        f["confirmed_bull"] = float(bool(previous & {"hammer", "engulf_bull"}) and closes[-1] > rows[-2].high_price)
        f["confirmed_bear"] = float("engulf_bear" in previous and closes[-1] < rows[-2].low_price)
    if n >= 21:
        volumes = [r.volume for r in rows[-21:]]
        if all(v is not None and isfinite(v) and v > 0 for v in volumes):
            f["relative_volume_20"] = min(20., volumes[-1]/(sum(volumes[:-1])/20))
            f["volume_available"] = 1.
        upper, lower = max(r.high_price for r in rows[-21:-1]), min(r.low_price for r in rows[-21:-1])
        f["donchian_breakout"] = 1. if closes[-1] > upper else -1. if closes[-1] < lower else 0.
    if n >= 15:
        tr = [max(rows[i].high_price-rows[i].low_price, abs(rows[i].high_price-closes[i-1]),
                  abs(rows[i].low_price-closes[i-1])) for i in range(n-14, n)]
        f["atr14_pct"] = sum(tr)/14/closes[-1]
    if n >= 20:
        mean = sum(closes[-20:])/20
        sd = sqrt(sum((c-mean)**2 for c in closes[-20:])/20)
        f["bollinger_position"] = (closes[-1]-mean)/(2*sd) if sd else 0.
        f["bollinger_width"] = 4*sd/mean
        f.update(triple_bottom=0., triple_top=0., elliott_impulse=0., elliott_abc=0.)
        points = pivots(closes)
        for sign in (1, -1):
            f["triple_bottom" if sign > 0 else "triple_top"] = triple(closes, points, sign)
            if elliott(closes, points, sign, False):
                f["elliott_impulse"] = float(sign)
            if elliott(closes, points, sign, True):
                f["elliott_abc"] = float(sign)
    if n >= 50:
        f["sma20_50_gap"] = (sum(closes[-20:])/20)/(sum(closes[-50:])/50)-1
    return tuple(f[k] for k in FEATURES)
