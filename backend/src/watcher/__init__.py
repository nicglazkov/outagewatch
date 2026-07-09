"""Generic watcher engine: poll a feed, diff state, match subscriptions, dispatch pushes.

This package is deliberately independent of any specific feed or product so it
can be extracted and reused by other watchers later. Keep product-specific
logic (PG&E field names, notification wording, quiet hours) out of here.
"""

from watcher.differ import Change, ChangeType, diff_snapshots
from watcher.matcher import Subscription, match_subscriptions
from watcher.types import Item, Snapshot

__all__ = [
    "Change",
    "ChangeType",
    "Item",
    "Snapshot",
    "Subscription",
    "diff_snapshots",
    "match_subscriptions",
]
