"""In-memory fakes for the service pipeline and API tests."""

from __future__ import annotations

from datetime import datetime

from outagewatch.store import StoredSubscription
from watcher.dispatch import PushMessage
from watcher.types import Snapshot


class FakeFeed:
    def __init__(self, snapshot: Snapshot, version: str = "v1"):
        self.snapshot = snapshot
        self.version = version

    async def fetch_status_version(self) -> str | None:
        return self.version

    async def fetch_snapshot(self) -> Snapshot:
        return self.snapshot


class FakeStateStore:
    def __init__(self):
        self.snapshot: Snapshot | None = None
        self.version: str | None = None

    async def load(self) -> Snapshot | None:
        return self.snapshot

    async def save(self, snapshot: Snapshot, fetched_at: datetime) -> None:
        self.snapshot = snapshot

    def last_version(self) -> str | None:
        return self.version

    def save_version(self, version: str) -> None:
        self.version = version


class FakeSubs:
    def __init__(self, subs: list[StoredSubscription] | None = None):
        self.subs = {s.id: s for s in (subs or [])}

    def upsert(self, sub: StoredSubscription) -> None:
        self.subs[sub.id] = sub

    def delete(self, sub_id: str) -> None:
        self.subs.pop(sub_id, None)

    def delete_by_token(self, token: str) -> int:
        doomed = [k for k, s in self.subs.items() if s.token == token]
        for k in doomed:
            del self.subs[k]
        return len(doomed)

    def list_all(self) -> list[StoredSubscription]:
        return list(self.subs.values())

    def list_for_device(self, token: str) -> list[StoredSubscription]:
        return [s for s in self.subs.values() if s.token == token]


class FakeSender:
    def __init__(self, dead_tokens: set[str] | None = None):
        self.sent: list[PushMessage] = []
        self.dead = dead_tokens or set()

    async def send(self, message: PushMessage) -> bool:
        if message.token in self.dead:
            return False
        self.sent.append(message)
        return True


class FakeEtaHistory:
    def __init__(self):
        self.entries: dict[str, list] = {}

    def append(self, outage_id: str, eta, observed_at) -> None:
        self.entries.setdefault(outage_id, []).append(
            {"eta": eta.isoformat() if eta else None, "observed_at": observed_at.isoformat()}
        )

    def get(self, outage_id: str) -> list:
        return self.entries.get(outage_id, [])


class FakeSlo:
    def __init__(self):
        self.events: list[dict] = []

    def log_dispatch(self, outage_id, kind, feed_updated_at, fetched_at, sent_count) -> None:
        self.events.append(
            {"outage_id": outage_id, "kind": kind, "sent_count": sent_count}
        )
