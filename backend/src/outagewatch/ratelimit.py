"""Tiny in-memory per-key rate limiter (sliding window).

Defense in depth against abuse of the public, unauthenticated endpoints,
especially POST /subscriptions (uncapped Firestore writes) and /explain (LLM
cost). Limits are deliberately generous: many mobile users share one carrier
IP (CGNAT), so the goal is to stop a single runaway script, not to throttle a
neighborhood. Per-instance memory is fine here (the API caps at a few instances).
"""

from __future__ import annotations

import time
from collections import deque

# Hard cap on tracked keys per limiter. An attacker rotating source IPs (trivial
# over IPv6, where one host controls a whole /64) would otherwise grow the map
# without limit and OOM the instance. When we cross this, sweep out every key
# whose window has fully expired; the residual is bounded by the number of
# distinct IPs actually seen within one window.
_MAX_KEYS = 20_000


class RateLimiter:
    def __init__(self, max_requests: int, window_seconds: float, max_keys: int = _MAX_KEYS):
        self.max = max_requests
        self.window = window_seconds
        self.max_keys = max_keys
        self._hits: dict[str, deque[float]] = {}

    def allow(self, key: str, now: float | None = None) -> bool:
        """Record a hit for `key`; return False if it's over the limit."""
        now = time.monotonic() if now is None else now
        cutoff = now - self.window
        # Bound memory before inserting a brand-new key.
        if key not in self._hits and len(self._hits) >= self.max_keys:
            self._sweep(cutoff)
        dq = self._hits.get(key)
        if dq is None:
            dq = self._hits[key] = deque()
        while dq and dq[0] < cutoff:
            dq.popleft()
        if len(dq) >= self.max:
            return False
        dq.append(now)
        return True

    def _sweep(self, cutoff: float) -> None:
        """Drop keys whose entire window has expired, reclaiming their memory.

        This also clears the empty deques left behind by idle keys, so a long
        instance lifetime doesn't accumulate dead entries.
        """
        for k in list(self._hits):
            dq = self._hits[k]
            while dq and dq[0] < cutoff:
                dq.popleft()
            if not dq:
                del self._hits[k]
