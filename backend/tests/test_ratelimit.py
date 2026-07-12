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
