"""In-process LRU+TTL cache for /analyse responses."""
import os
import threading
import time
from collections import OrderedDict


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return value if value > 0 else default


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _env_positive_float(name: str, default: float) -> float:
    value = _env_float(name, default)
    return value if value > 0 else default


# 0 = do not wait; a busy engine returns 503 immediately.
ENGINE_LOCK_TIMEOUT_SECONDS = max(
    0.0, _env_float("STOCKFISH_LOCK_TIMEOUT_SECONDS", 0.0)
)


class BoundedTTLCache:
    """In-process LRU cache with TTL.

    Chosen over Redis: one gunicorn worker, no extra service. Not shared
    across processes — keep ``gunicorn -w 1``.
    """

    def __init__(self, max_entries: int = 256, ttl_seconds: float = 3600.0):
        self.max_entries = max_entries
        self.ttl_seconds = ttl_seconds
        self._data = OrderedDict()
        self._lock = threading.Lock()

    def get(self, key):
        now = time.monotonic()
        with self._lock:
            item = self._data.get(key)
            if item is None:
                return None
            value, expires = item
            if now >= expires:
                del self._data[key]
                return None
            self._data.move_to_end(key)
            return value

    def __setitem__(self, key, value):
        now = time.monotonic()
        with self._lock:
            self._purge_expired_locked(now)
            self._data[key] = (value, now + self.ttl_seconds)
            self._data.move_to_end(key)
            while len(self._data) > self.max_entries:
                self._data.popitem(last=False)

    def clear(self):
        with self._lock:
            self._data.clear()

    def __len__(self):
        now = time.monotonic()
        with self._lock:
            self._purge_expired_locked(now)
            return len(self._data)

    def _purge_expired_locked(self, now: float):
        expired = [key for key, (_, exp) in self._data.items() if now >= exp]
        for key in expired:
            del self._data[key]


CACHE = BoundedTTLCache(
    max_entries=_env_int("CACHE_MAX_ENTRIES", 256),
    ttl_seconds=_env_positive_float("CACHE_TTL_SECONDS", 3600.0),
)
