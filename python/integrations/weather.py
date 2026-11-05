# =============================================================================
# Weather and air quality for the Today screen. Plan section 6.2 item 10.
#
# Open-Meteo: free, no API key, no account, and it does not want our users'
# identity — only a coordinate. We round the coordinate to two decimals (about a
# kilometre) before sending it, so a retired official's exact address never
# leaves this server, and cache the answer for 30 minutes per rounded cell so
# fifty thousand people in one city cost one request, not fifty thousand.
# =============================================================================
from __future__ import annotations

import logging
import time

from python.integrations import _http

log = logging.getLogger("doosri.weather")

_CACHE: dict[tuple[float, float], tuple[float, dict]] = {}
TTL_S = 1800

_WMO = {
    0: "Clear", 1: "Mostly clear", 2: "Partly cloudy", 3: "Overcast", 45: "Fog", 48: "Fog",
    51: "Light drizzle", 53: "Drizzle", 55: "Heavy drizzle", 61: "Light rain", 63: "Rain",
    65: "Heavy rain", 71: "Light snow", 73: "Snow", 75: "Heavy snow", 80: "Showers",
    81: "Heavy showers", 82: "Violent showers", 95: "Thunderstorm", 96: "Thunderstorm with hail",
    99: "Thunderstorm with hail",
}


def _aqi_band(aqi: float | None) -> str | None:
    """US AQI bands (Open-Meteo reports the US scale), in words. Not a diagnosis."""
    if aqi is None:
        return None
    for limit, label in (
        (50, "Good"), (100, "Moderate"), (150, "Unhealthy for sensitive people"),
        (200, "Unhealthy"), (300, "Very unhealthy"),
    ):
        if aqi <= limit:
            return label
    return "Hazardous"


def _advice(temp: float | None, aqi: float | None, code: int | None) -> str | None:
    """One kind line for an older person. Suggestions, not medical advice."""
    if aqi is not None and aqi > 150:
        return "The air is poor today. Consider staying indoors for your walk."
    if temp is not None and temp >= 40:
        return "It is very hot. Drink water often and avoid going out at noon."
    if temp is not None and temp <= 8:
        return "It is cold. Keep warm, especially in the morning."
    if code in (61, 63, 65, 80, 81, 82, 95, 96, 99):
        return "Rain is likely. Watch your step on wet floors."
    return None


def current(lat: float, lng: float) -> dict | None:
    key = (round(lat, 2), round(lng, 2))
    hit = _CACHE.get(key)
    if hit and time.time() - hit[0] < TTL_S:
        return hit[1]

    w = _http.request(
        "GET",
        "https://api.open-meteo.com/v1/forecast",
        params={
            "latitude": key[0],
            "longitude": key[1],
            "current": "temperature_2m,relative_humidity_2m,weather_code",
            "daily": "temperature_2m_max,temperature_2m_min",
            "timezone": "auto",
            "forecast_days": 1,
        },
        timeout=8.0,
        retries=1,
    )
    if not w.ok or not isinstance(w.body, dict):
        return None
    cur = w.body.get("current") or {}
    daily = w.body.get("daily") or {}

    aqi = None
    a = _http.request(
        "GET",
        "https://air-quality-api.open-meteo.com/v1/air-quality",
        params={"latitude": key[0], "longitude": key[1], "current": "us_aqi,pm2_5"},
        timeout=8.0,
        retries=0,
    )
    if a.ok and isinstance(a.body, dict):
        aqi = (a.body.get("current") or {}).get("us_aqi")

    code = cur.get("weather_code")
    temp = cur.get("temperature_2m")
    out = {
        "temp_c": temp,
        "humidity": cur.get("relative_humidity_2m"),
        "summary": _WMO.get(code, "—"),
        "high_c": (daily.get("temperature_2m_max") or [None])[0],
        "low_c": (daily.get("temperature_2m_min") or [None])[0],
        "aqi": aqi,
        "aqi_band": _aqi_band(aqi),
        "advice": _advice(temp, aqi, code),
        "source": "Open-Meteo",
    }
    _CACHE[key] = (time.time(), out)
    return out
