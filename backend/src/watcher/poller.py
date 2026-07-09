"""One poll cycle: fetch feed, diff against stored state, persist, return changes.

Designed for Cloud Scheduler -> Cloud Run job invocation: each invocation runs
exactly one cycle. Long-running loop mode can wrap run_cycle() with sleep and
backoff; both share the same logic.
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime

from watcher.differ import Change, diff_snapshots
from watcher.types import Snapshot

logger = logging.getLogger(__name__)

FetchFn = Callable[[], Awaitable[Snapshot]]
LoadStateFn = Callable[[], Awaitable[Snapshot | None]]
SaveStateFn = Callable[[Snapshot, datetime], Awaitable[None]]


@dataclass
class CycleResult:
    changes: list[Change] = field(default_factory=list)
    snapshot: Snapshot = field(default_factory=dict)
    fetched_at: datetime | None = None
    first_run: bool = False


async def run_cycle(
    fetch: FetchFn, load_state: LoadStateFn, save_state: SaveStateFn
) -> CycleResult:
    """Fetch the feed once, diff against persisted state, persist the new state.

    On the very first run (no stored state) the snapshot is persisted but no
    changes are emitted: everything already in the feed is pre-existing, and
    blasting STARTED notifications for old outages would be wrong.
    """
    fetched_at = datetime.now(UTC)
    snapshot = await fetch()
    previous = await load_state()

    if previous is None:
        await save_state(snapshot, fetched_at)
        logger.info("first run: stored %d items, no events emitted", len(snapshot))
        return CycleResult(changes=[], snapshot=snapshot, fetched_at=fetched_at, first_run=True)

    changes = diff_snapshots(previous, snapshot)
    await save_state(snapshot, fetched_at)
    logger.info("cycle: %d items, %d changes", len(snapshot), len(changes))
    return CycleResult(changes=changes, snapshot=snapshot, fetched_at=fetched_at)
