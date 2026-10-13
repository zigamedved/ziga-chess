"""HTTP client for the Java Lucene /position hop."""

import os
import threading

import urllib3

JAVA_SERVICE_URL = os.environ.get(
    "JAVA_SERVICE_URL", "http://127.0.0.1:8080/position"
)

_http = None
_http_lock = threading.Lock()


def http_pool():
    """Reuse one urllib3 pool (created on first use)."""
    global _http
    if _http is not None:
        return _http
    with _http_lock:
        if _http is None:
            _http = urllib3.PoolManager()
        return _http
