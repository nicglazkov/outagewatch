"""Persistence: feed state + raw recordings in GCS, subscriptions + ETA history in Firestore.

State lives in GCS rather than Firestore because storm-day snapshots with
polygons can blow past Firestore's 1MB document limit. Raw feed responses are
recorded on every version change; an outage day's recording becomes the
regression suite.

All GCP clients are wrapped behind small classes so tests can substitute
in-memory fakes.
"""

from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from typing import Any

from watcher.matcher import Subscription
from watcher.serde import snapshot_from_dict, snapshot_to_dict
from watcher.types import Snapshot

logger = logging.getLogger(__name__)

STATE_BLOB = "state/current.json"


class GcsStateStore:
    """Feed snapshot state + raw recording in a GCS bucket."""

    def __init__(self, bucket_name: str):
        from google.cloud import storage

        self._bucket = storage.Client().bucket(bucket_name)

    async def load(self) -> Snapshot | None:
        blob = self._bucket.blob(STATE_BLOB)
        if not blob.exists():
            return None
        return snapshot_from_dict(json.loads(blob.download_as_text()))

    async def save(self, snapshot: Snapshot, fetched_at: datetime) -> None:
        payload = snapshot_to_dict(snapshot)
        payload["fetched_at"] = fetched_at.isoformat()
        self._bucket.blob(STATE_BLOB).upload_from_string(
            json.dumps(payload), content_type="application/json"
        )

    def record_raw(self, version: str, raw_layers: dict[str, Any]) -> None:
        now = datetime.now(UTC)
        path = f"raw/{now:%Y/%m/%d}/{now:%H%M%S}-{version}.json"
        self._bucket.blob(path).upload_from_string(
            json.dumps(raw_layers), content_type="application/json"
        )
        logger.info("recorded raw snapshot %s", path)

    def last_version(self) -> str | None:
        blob = self._bucket.blob("state/version.txt")
        return blob.download_as_text().strip() if blob.exists() else None

    def save_version(self, version: str) -> None:
        self._bucket.blob("state/version.txt").upload_from_string(version)


@dataclass
class StoredSubscription:
    id: str
    token: str
    platform: str = "android"  # android | ios | web
    zip_code: str | None = None
    lat: float | None = None
    lon: float | None = None
    radius_km: float = 1.0
    label: str | None = None
    quiet_start: str | None = None  # "22:00" local
    quiet_end: str | None = None
    tz: str = "America/Los_Angeles"
    psps_warnings: bool = True
    precise: bool = False  # an exact address: alert only inside the outage footprint
    created_at: str | None = None

    def to_matcher(self) -> Subscription:
        return Subscription(
            id=self.id,
            token=self.token,
            zip_code=self.zip_code,
            lat=self.lat,
            lon=self.lon,
            radius_km=self.radius_km,
            precise=self.precise,
        )


class FirestoreSubscriptionStore:
    COLLECTION = "subscriptions"

    def __init__(self, project_id: str):
        from google.cloud import firestore

        self._db = firestore.Client(project=project_id)

    def upsert(self, sub: StoredSubscription) -> None:
        if not sub.created_at:
            sub.created_at = datetime.now(UTC).isoformat()
        self._db.collection(self.COLLECTION).document(sub.id).set(asdict(sub))

    def delete(self, sub_id: str) -> None:
        self._db.collection(self.COLLECTION).document(sub_id).delete()

    def delete_by_token(self, token: str) -> int:
        docs = self._db.collection(self.COLLECTION).where("token", "==", token).stream()
        n = 0
        for doc in docs:
            doc.reference.delete()
            n += 1
        return n

    def list_all(self) -> list[StoredSubscription]:
        return [
            StoredSubscription(**doc.to_dict())
            for doc in self._db.collection(self.COLLECTION).stream()
        ]

    def list_for_device(self, token: str) -> list[StoredSubscription]:
        docs = self._db.collection(self.COLLECTION).where("token", "==", token).stream()
        return [StoredSubscription(**doc.to_dict()) for doc in docs]


class FirestoreEtaHistory:
    """Per-outage ETA history so the app can say 'the estimate slipped twice'."""

    COLLECTION = "eta_history"
    MAX_ENTRIES = 50

    def __init__(self, project_id: str):
        from google.cloud import firestore

        self._db = firestore.Client(project=project_id)

    def append(self, outage_id: str, eta: datetime | None, observed_at: datetime) -> None:
        from google.cloud import firestore

        entry = {
            "eta": eta.isoformat() if eta else None,
            "observed_at": observed_at.isoformat(),
        }
        self._db.collection(self.COLLECTION).document(outage_id).set(
            {"entries": firestore.ArrayUnion([entry])}, merge=True
        )

    def get(self, outage_id: str) -> list[dict[str, Any]]:
        doc = self._db.collection(self.COLLECTION).document(outage_id).get()
        if not doc.exists:
            return []
        return (doc.to_dict() or {}).get("entries", [])[-self.MAX_ENTRIES :]


class FirestoreSloLog:
    """Alert-latency SLO events: feed change observed -> push sent."""

    COLLECTION = "slo_events"

    def __init__(self, project_id: str):
        from google.cloud import firestore

        self._db = firestore.Client(project=project_id)

    def recent_latencies(self, hours: int = 24) -> list[float]:
        """Feed-change-to-push latencies (seconds) for SLO reporting."""
        from google.cloud.firestore_v1 import FieldFilter

        cutoff = datetime.now(UTC).timestamp() - hours * 3600
        cutoff_iso = datetime.fromtimestamp(cutoff, tz=UTC).isoformat()
        docs = (
            self._db.collection(self.COLLECTION)
            .where(filter=FieldFilter("sent_at", ">=", cutoff_iso))
            .stream()
        )
        return [
            d.to_dict()["latency_seconds"]
            for d in docs
            if d.to_dict().get("latency_seconds") is not None
        ]

    def log_dispatch(
        self,
        outage_id: str,
        kind: str,
        feed_updated_at: datetime | None,
        fetched_at: datetime,
        sent_count: int,
    ) -> None:
        sent_at = datetime.now(UTC)
        latency = None
        if feed_updated_at is not None:
            latency = (sent_at - feed_updated_at).total_seconds()
        self._db.collection(self.COLLECTION).add(
            {
                "outage_id": outage_id,
                "kind": kind,
                "feed_updated_at": feed_updated_at.isoformat() if feed_updated_at else None,
                "fetched_at": fetched_at.isoformat(),
                "sent_at": sent_at.isoformat(),
                "latency_seconds": latency,
                "sent_count": sent_count,
            }
        )
