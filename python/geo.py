# =============================================================================
# Cities. Plan sections 6.1 (home city), 6.6 (city circles), 6.2 (weather label).
#
# `User.city_id` and `Circle.city_id` are bare integers with no cities table, and
# a table would need a licensed dataset and a migration to seed. This module is
# the reference instead: a curated list of Indian cities that matter for pension
# circles, keyed by a STABLE id.
#
# THE ID IS THE INDEX PLUS ONE AND THE LIST IS APPEND-ONLY. Reordering or
# deleting an entry would silently move every user's home city to a different
# place. Add new cities at the end.
#
# Coordinates are city centres to two decimals (about a kilometre) - accurate
# enough to pick a weather cell and to suggest the nearest city circle, and
# deliberately too coarse to locate a home.
# =============================================================================
from __future__ import annotations

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class City:
    id: int
    name: str
    state: str
    lat: float
    lng: float


_RAW: list[tuple[str, str, float, float]] = [
    ("Delhi", "DL", 28.61, 77.21), ("Mumbai", "MH", 19.08, 72.88), ("Kolkata", "WB", 22.57, 88.36),
    ("Chennai", "TN", 13.08, 80.27), ("Bengaluru", "KA", 12.97, 77.59), ("Hyderabad", "TS", 17.39, 78.49),
    ("Ahmedabad", "GJ", 23.02, 72.57), ("Pune", "MH", 18.52, 73.86), ("Jaipur", "RJ", 26.91, 75.79),
    ("Lucknow", "UP", 26.85, 80.95), ("Kanpur", "UP", 26.45, 80.35), ("Nagpur", "MH", 21.15, 79.09),
    ("Indore", "MP", 22.72, 75.86), ("Bhopal", "MP", 23.26, 77.41), ("Patna", "BR", 25.59, 85.14),
    ("Vadodara", "GJ", 22.31, 73.18), ("Ghaziabad", "UP", 28.67, 77.42), ("Ludhiana", "PB", 30.90, 75.86),
    ("Agra", "UP", 27.18, 78.02), ("Nashik", "MH", 19.99, 73.79), ("Varanasi", "UP", 25.32, 83.01),
    ("Meerut", "UP", 28.98, 77.71), ("Rajkot", "GJ", 22.30, 70.80), ("Srinagar", "JK", 34.08, 74.80),
    ("Amritsar", "PB", 31.63, 74.87), ("Prayagraj", "UP", 25.44, 81.85), ("Ranchi", "JH", 23.34, 85.31),
    ("Coimbatore", "TN", 11.02, 76.96), ("Jabalpur", "MP", 23.18, 79.99), ("Gwalior", "MP", 26.22, 78.18),
    ("Vijayawada", "AP", 16.51, 80.65), ("Jodhpur", "RJ", 26.24, 73.02), ("Madurai", "TN", 9.93, 78.12),
    ("Raipur", "CG", 21.25, 81.63), ("Kota", "RJ", 25.18, 75.83), ("Chandigarh", "CH", 30.73, 76.78),
    ("Guwahati", "AS", 26.14, 91.74), ("Thiruvananthapuram", "KL", 8.52, 76.94), ("Kochi", "KL", 9.93, 76.27),
    ("Bhubaneswar", "OD", 20.30, 85.82), ("Dehradun", "UK", 30.32, 78.03), ("Mysuru", "KA", 12.30, 76.64),
    ("Visakhapatnam", "AP", 17.69, 83.22), ("Surat", "GJ", 21.17, 72.83), ("Gorakhpur", "UP", 26.76, 83.37),
    ("Bareilly", "UP", 28.37, 79.43), ("Aligarh", "UP", 27.90, 78.08), ("Moradabad", "UP", 28.84, 78.78),
    ("Jalandhar", "PB", 31.33, 75.58), ("Shimla", "HP", 31.10, 77.17), ("Jammu", "JK", 32.73, 74.87),
    ("Panaji", "GA", 15.50, 73.83), ("Puducherry", "PY", 11.93, 79.83), ("Tiruchirappalli", "TN", 10.79, 78.70),
    ("Salem", "TN", 11.66, 78.15), ("Chhatrapati Sambhajinagar", "MH", 19.88, 75.34), ("Solapur", "MH", 17.66, 75.91),
    ("Kolhapur", "MH", 16.70, 74.24), ("Belagavi", "KA", 15.85, 74.50), ("Hubballi", "KA", 15.36, 75.12),
    ("Mangaluru", "KA", 12.91, 74.86), ("Warangal", "TS", 17.97, 79.59), ("Guntur", "AP", 16.31, 80.44),
    ("Tirupati", "AP", 13.63, 79.42), ("Jamshedpur", "JH", 22.80, 86.20), ("Dhanbad", "JH", 23.80, 86.43),
    ("Siliguri", "WB", 26.73, 88.40), ("Durgapur", "WB", 23.55, 87.32), ("Agartala", "TR", 23.83, 91.29),
    ("Imphal", "MN", 24.82, 93.94), ("Shillong", "ML", 25.58, 91.89), ("Gangtok", "SK", 27.34, 88.61),
    ("Noida", "UP", 28.54, 77.39), ("Gurugram", "HR", 28.46, 77.03), ("Faridabad", "HR", 28.41, 77.31),
    ("Rohtak", "HR", 28.90, 76.61), ("Ambala", "HR", 30.38, 76.78), ("Haridwar", "UK", 29.95, 78.16),
    ("Ayodhya", "UP", 26.80, 82.20), ("Mathura", "UP", 27.49, 77.67), ("Ujjain", "MP", 23.18, 75.78),
    ("Bilaspur", "CG", 22.09, 82.15), ("Udaipur", "RJ", 24.59, 73.71), ("Ajmer", "RJ", 26.45, 74.64),
    ("Bikaner", "RJ", 28.02, 73.31), ("Jhansi", "UP", 25.45, 78.57), ("Saharanpur", "UP", 29.96, 77.55),
    ("Gaya", "BR", 24.80, 85.00), ("Muzaffarpur", "BR", 26.12, 85.39), ("Bhagalpur", "BR", 25.24, 86.97),
    ("Cuttack", "OD", 20.46, 85.88), ("Thrissur", "KL", 10.53, 76.21), ("Kozhikode", "KL", 11.26, 75.78),
    ("Nellore", "AP", 14.44, 79.99), ("Thane", "MH", 19.22, 72.98), ("Navi Mumbai", "MH", 19.03, 73.03),
]

CITIES: tuple[City, ...] = tuple(
    City(i + 1, name, state, lat, lng) for i, (name, state, lat, lng) in enumerate(_RAW)
)
_BY_ID = {c.id: c for c in CITIES}


def get(city_id: int | None) -> City | None:
    return _BY_ID.get(city_id) if city_id else None


def haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lng2 - lng1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6371.0 * 2 * math.asin(min(1.0, math.sqrt(h)))


def nearest(lat: float, lng: float, within_km: float = 60.0) -> City | None:
    """The closest listed city, or None when nothing is within `within_km`."""
    best, best_d = None, within_km
    for c in CITIES:
        d = haversine_km(lat, lng, c.lat, c.lng)
        if d < best_d:
            best, best_d = c, d
    return best


def search(query: str, limit: int = 10) -> list[City]:
    q = (query or "").strip().lower()
    if not q:
        return []
    starts = [c for c in CITIES if c.name.lower().startswith(q)]
    contains = [c for c in CITIES if q in c.name.lower() and c not in starts]
    return (starts + contains)[:limit]


def city_view(c: City) -> dict:
    return {"id": c.id, "name": c.name, "state": c.state}
