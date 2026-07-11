"""One end-to-end poll cycle: fetch, diff, match, notify, record.

Dependencies are injected so tests run the whole pipeline with fakes and the
production entrypoint wires GCS/Firestore/FCM.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import UTC, datetime, time
from typing import Any, Protocol
from zoneinfo import ZoneInfo

from outagewatch.rules import alert_for_change, should_send
from outagewatch.store import StoredSubscription
from watcher.dispatch import PushMessage, PushSender, dispatch_all
from watcher.matcher import match_subscriptions
from watcher.poller import run_cycle
from watcher.types import Snapshot

logger = logging.getLogger(__name__)


class Feed(Protocol):
    async def fetch_status_version(self) -> str | None: ...
    async def fetch_snapshot(self) -> Snapshot: ...


class StateStore(Protocol):
    async def load(self) -> Snapshot | None: ...
    async def save(self, snapshot: Snapshot, fetched_at: datetime) -> None: ...
    def last_version(self) -> str | None: ...
    def save_version(self, version: str) -> None: ...


class SubscriptionSource(Protocol):
    def list_all(self) -> list[StoredSubscription]: ...
    def delete_by_token(self, token: str) -> int: ...


class EtaHistorySink(Protocol):
    def append(self, outage_id: str, eta: datetime | None, observed_at: datetime) -> None: ...


class SloSink(Protocol):
    def log_dispatch(
        self,
        outage_id: str,
        kind: str,
        feed_updated_at: datetime | None,
        fetched_at: datetime,
        sent_count: int,
    ) -> None: ...


@dataclass
class PollOutcome:
    skipped_unchanged: bool = False
    first_run: bool = False
    items: int = 0
    changes: int = 0
    alerts: int = 0
    pushes_sent: int = 0
    dead_tokens: list[str] = field(default_factory=list)


async def poll_once(
    feed: Feed,
    state: StateStore,
    subs: SubscriptionSource,
    sender: PushSender,
    eta_history: EtaHistorySink | None = None,
    slo: SloSink | None = None,
    now: datetime | None = None,
) -> PollOutcome:
    outcome = PollOutcome()

    version = await feed.fetch_status_version()
    if version is not None and version == state.last_version():
        logger.info("feed version %s unchanged, skipping", version)
        outcome.skipped_unchanged = True
        return outcome

    result = await run_cycle(feed.fetch_snapshot, state.load, state.save)
    if version is not None:
        state.save_version(version)
        # Record the raw feed responses on every version change: a real outage
        # day's recording becomes the regression suite.
        raw = getattr(feed, "last_raw", None)
        record = getattr(state, "record_raw", None)
        if raw and record is not None:
            try:
                record(version, raw)
            except Exception:
                logger.exception("raw snapshot recording failed (non-fatal)")
    outcome.first_run = result.first_run
    outcome.items = len(result.snapshot)
    outcome.changes = len(result.changes)
    if result.first_run or not result.changes:
        return outcome

    all_subs = subs.list_all()
    now = now or datetime.now(UTC)

    for change in result.changes:
        if eta_history is not None and change.item.eta != (
            change.previous.eta if change.previous else None
        ):
            eta_history.append(change.item.id, change.item.eta, now)

        alert = alert_for_change(change)
        if alert is None:
            continue
        outcome.alerts += 1

        matched = match_subscriptions(change.item, [s.to_matcher() for s in all_subs])
        matched_ids = {m.id for m in matched}
        messages: list[PushMessage] = []
        for sub in all_subs:
            if sub.id not in matched_ids:
                continue
            if not sub.psps_warnings and alert.kind == "psps_warning":
                continue
            local = now.astimezone(_zone(sub.tz))
            if not should_send(alert, local, _t(sub.quiet_start), _t(sub.quiet_end)):
                continue
            messages.append(
                PushMessage(
                    token=sub.token,
                    title=alert.title,
                    body=alert.body,
                    data=_alert_data(alert, change.item.payload),
                    collapse_key=f"outage-{alert.outage_id}",
                )
            )

        report = await dispatch_all(sender, messages)
        outcome.pushes_sent += report.sent
        outcome.dead_tokens.extend(report.dead_tokens)
        if slo is not None and messages:
            slo.log_dispatch(
                alert.outage_id, alert.kind, change.item.updated_at, result.fetched_at, report.sent
            )

    for token in set(outcome.dead_tokens):
        subs.delete_by_token(token)

    return outcome


def _alert_data(alert, payload: dict[str, Any]) -> dict[str, str]:
    return {
        "outage_id": alert.outage_id,
        "kind": alert.kind,
        "cause": str(payload.get("OUTAGE_CAUSE") or ""),
        "customers": str(payload.get("EST_CUSTOMERS") or ""),
    }


_DEFAULT_ZONE = ZoneInfo("America/Los_Angeles")


def _zone(name: str | None) -> ZoneInfo:
    """Never let one subscription's bad tz crash the whole poll cycle."""
    if not name:
        return _DEFAULT_ZONE
    try:
        return ZoneInfo(name)
    except Exception:
        logger.warning("unknown tz %r, using Pacific", name)
        return _DEFAULT_ZONE


def _t(value: str | None) -> time | None:
    if not value:
        return None
    hh, _, mm = value.partition(":")
    try:
        return time(int(hh), int(mm or 0))
    except ValueError:
        return None
