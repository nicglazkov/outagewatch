"""Push dispatch abstraction. Concrete senders (FCM, web push) live in the product layer."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol


@dataclass(frozen=True)
class PushMessage:
    token: str
    title: str
    body: str
    data: dict[str, str] = field(default_factory=dict)
    collapse_key: str | None = None


class PushSender(Protocol):
    async def send(self, message: PushMessage) -> bool:
        """Send one push. Returns False (without raising) if the token is dead."""
        ...


@dataclass
class DispatchReport:
    sent: int = 0
    dead_tokens: list[str] = field(default_factory=list)


async def dispatch_all(sender: PushSender, messages: list[PushMessage]) -> DispatchReport:
    report = DispatchReport()
    for msg in messages:
        ok = await sender.send(msg)
        if ok:
            report.sent += 1
        else:
            report.dead_tokens.append(msg.token)
    return report
