"""Sliding-window rate limiter."""

from outagewatch.ratelimit import RateLimiter


def test_allows_up_to_limit_then_blocks():
    rl = RateLimiter(max_requests=3, window_seconds=60)
    assert [rl.allow("ip", now=t) for t in (0.0, 0.1, 0.2)] == [True, True, True]
    assert rl.allow("ip", now=0.3) is False


def test_window_slides():
    rl = RateLimiter(max_requests=2, window_seconds=10)
    assert rl.allow("ip", now=0.0) is True
    assert rl.allow("ip", now=1.0) is True
    assert rl.allow("ip", now=2.0) is False  # both still in window
    assert rl.allow("ip", now=11.5) is True  # first hit (t=0) aged out


def test_keys_are_independent():
    rl = RateLimiter(max_requests=1, window_seconds=60)
    assert rl.allow("a", now=0.0) is True
    assert rl.allow("b", now=0.0) is True
    assert rl.allow("a", now=0.1) is False


def test_idle_keys_are_evicted_to_bound_memory():
    # Simulate IP rotation: many one-shot keys. Once the cap is crossed, keys
    # whose window has expired must be swept so memory stays bounded.
    rl = RateLimiter(max_requests=5, window_seconds=10, max_keys=100)
    for i in range(100):
        assert rl.allow(f"ip-{i}", now=0.0) is True
    assert len(rl._hits) == 100
    # A new key well after the window: the 100 idle keys should be reclaimed.
    assert rl.allow("fresh", now=50.0) is True
    assert len(rl._hits) == 1


def test_sweep_keeps_still_active_keys():
    rl = RateLimiter(max_requests=5, window_seconds=10, max_keys=2)
    assert rl.allow("a", now=0.0) is True
    assert rl.allow("b", now=9.9) is True
    # Adding a 3rd key at t=10.5 triggers a sweep (cutoff=0.5): "a" (t=0.0) has
    # aged out and is reclaimed; "b" (t=9.9) is still active and survives.
    assert rl.allow("c", now=10.5) is True
    assert "a" not in rl._hits
    assert "b" in rl._hits and "c" in rl._hits
