"""Tiny in-memory per-key rate limiter (sliding window).

Defense in depth against abuse of the public, unauthenticated endpoints,
especially POST /subscriptions (uncapped Firestore writes) and /explain (LLM
cost). Limits are deliberately generous: many mobile users share one carrier
IP (CGNAT), so the goal is to stop a single runaway script, not to throttle a
neighborhood. Per-instance memory is fine here (the API caps at a few instances).
"""

from __future__ import annotations

import time
from collections import defaultdict, deque


class RateLimiter:
    def __init__(self, max_requests: int, window_seconds: float):
        self.max = max_requests
        self.window = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)

    def allow(self, key: str, now: float | None = None) -> bool:
        """Record a hit for `key`; return False if it's over the limit.

        Memory is bounded by the distinct keys seen during an instance's short
        lifetime (Cloud Run recycles instances), which is fine at this scale.
        """
        now = time.monotonic() if now is None else now
        cutoff = now - self.window
        dq = self._hits[key]
        while dq and dq[0] < cutoff:
            dq.popleft()
        if len(dq) >= self.max:
            return False
        dq.append(now)
        return True
