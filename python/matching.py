# =============================================================================
# The Anubhav matching score. Plan section 6.8.
#
#   score = 0.35 * skill_tag_overlap + 0.20 * language_match
#         + 0.20 * distance_score (1.0 within 3 km, 0 beyond 25 km, online = 1.0)
#         + 0.15 * availability_overlap + 0.10 * rating (Bayesian, prior 4.0 from 5)
#
# Pure functions, no database, so the same numbers can be checked against the
# Kotlin twin in shared/feature-seva and pinned with rule-based expected values
# (plan 20). Every component is in [0, 1], so the score is too.
# =============================================================================
from __future__ import annotations

from dataclasses import dataclass, field

from python.geo import haversine_km

W_SKILL, W_LANG, W_DIST, W_AVAIL, W_RATING = 0.35, 0.20, 0.20, 0.15, 0.10
NEAR_KM, FAR_KM = 3.0, 25.0
PRIOR_MEAN, PRIOR_WEIGHT, MAX_STARS = 4.0, 5, 5.0


@dataclass
class Seeker:
    """What the person looking wants. Any field may be empty."""

    tags: set[str] = field(default_factory=set)
    langs: set[str] = field(default_factory=set)
    lat: float | None = None
    lng: float | None = None
    # (weekday 1-7, start_min, end_min) windows the seeker could meet in.
    windows: list[tuple[int, int, int]] = field(default_factory=list)


@dataclass
class Offer:
    tags: set[str] = field(default_factory=set)
    langs: set[str] = field(default_factory=set)
    lat: float | None = None
    lng: float | None = None
    radius_km: float | None = None
    online: bool = False
    windows: list[tuple[int, int, int]] = field(default_factory=list)
    rating_sum: float = 0.0
    rating_count: int = 0


def skill_overlap(want: set[str], have: set[str]) -> float:
    """Share of what the seeker asked for that the offer covers."""
    want = {t.strip().lower() for t in want if t.strip()}
    have = {t.strip().lower() for t in have if t.strip()}
    if not want:
        return 0.5  # nothing asked for: neutral, not a penalty
    return len(want & have) / len(want)


def language_match(want: set[str], have: set[str]) -> float:
    if not want or not have:
        return 0.5
    return 1.0 if want & have else 0.0


def distance_score(seeker: Seeker, offer: Offer) -> float:
    if offer.online:
        return 1.0
    if None in (seeker.lat, seeker.lng, offer.lat, offer.lng):
        return 0.5  # unknown position is neutral
    km = haversine_km(seeker.lat, seeker.lng, offer.lat, offer.lng)
    if km <= NEAR_KM:
        return 1.0
    if km >= FAR_KM:
        return 0.0
    return 1.0 - (km - NEAR_KM) / (FAR_KM - NEAR_KM)


def availability_overlap(want: list[tuple[int, int, int]], have: list[tuple[int, int, int]]) -> float:
    """Share of the seeker's windows (by minutes) that the offer's windows cover."""
    if not want:
        return 0.5
    if not have:
        return 0.0
    total = covered = 0
    for wd, ws, we in want:
        span = max(0, we - ws)
        total += span
        for hd, hs, he in have:
            if hd != wd:
                continue
            covered += max(0, min(we, he) - max(ws, hs))
    return min(1.0, covered / total) if total else 0.5


def rating_score(total_stars: float, count: int) -> float:
    """Bayesian mean with a prior of 4.0 out of 5, scaled to [0, 1]."""
    mean = (PRIOR_MEAN * PRIOR_WEIGHT + total_stars) / (PRIOR_WEIGHT + max(count, 0))
    return max(0.0, min(1.0, mean / MAX_STARS))


def score(seeker: Seeker, offer: Offer) -> float:
    return round(
        W_SKILL * skill_overlap(seeker.tags, offer.tags)
        + W_LANG * language_match(seeker.langs, offer.langs)
        + W_DIST * distance_score(seeker, offer)
        + W_AVAIL * availability_overlap(seeker.windows, offer.windows)
        + W_RATING * rating_score(offer.rating_sum, offer.rating_count),
        4,
    )


def explain(seeker: Seeker, offer: Offer) -> str:
    """One plain line saying why this matched. Suggestions, not claims."""
    bits = []
    if seeker.tags and skill_overlap(seeker.tags, offer.tags) >= 0.5:
        bits.append("matches what you asked for")
    if language_match(seeker.langs, offer.langs) == 1.0:
        bits.append("in your language")
    if offer.online:
        bits.append("online")
    elif distance_score(seeker, offer) >= 0.9:
        bits.append("near you")
    return ", ".join(bits).capitalize() if bits else "Open to everyone"
