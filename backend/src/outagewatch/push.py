"""FCM push sender. One Firebase Admin app per process, ADC on Cloud Run."""

from __future__ import annotations

import asyncio
import hashlib
import logging

from watcher.dispatch import PushMessage

logger = logging.getLogger(__name__)


def _tok(token: str) -> str:
    """A short, non-reversible token fingerprint for logs (never log the token)."""
    return hashlib.sha256(token.encode()).hexdigest()[:8]


class FcmSender:
    def __init__(self, project_id: str):
        import firebase_admin

        if not firebase_admin._apps:
            firebase_admin.initialize_app(options={"projectId": project_id})

    async def send(self, message: PushMessage) -> bool:
        """Send one push. Returns False when the token is dead (unregistered)."""
        from firebase_admin import messaging

        msg = messaging.Message(
            token=message.token,
            notification=messaging.Notification(title=message.title, body=message.body),
            data=message.data,
            android=messaging.AndroidConfig(
                priority="high", collapse_key=message.collapse_key
            ),
            apns=messaging.APNSConfig(
                headers={"apns-collapse-id": message.collapse_key} if message.collapse_key else None
            ),
        )
        from firebase_admin import exceptions as fb_exceptions

        try:
            await asyncio.to_thread(messaging.send, msg)
            return True
        except (messaging.UnregisteredError, messaging.SenderIdMismatchError):
            logger.info("dead token, dropping %s", _tok(message.token))
            return False
        except fb_exceptions.InvalidArgumentError:
            # A malformed/junk token: permanently invalid, so prune it too.
            logger.info("invalid token, dropping %s", _tok(message.token))
            return False
        except Exception:
            logger.exception("FCM send failed (transient) %s", _tok(message.token))
            return True  # transient: do not treat the token as dead

    async def validate(self, token: str) -> bool:
        """Dry-run send to check a token without delivering anything. Returns
        False only when FCM says the token is permanently dead/invalid, so the
        prune sweep can drop junk tokens that never receive a real push. A
        transient error returns True (keep the token)."""
        from firebase_admin import exceptions as fb_exceptions
        from firebase_admin import messaging

        msg = messaging.Message(token=token, data={"ping": "1"})
        try:
            await asyncio.to_thread(messaging.send, msg, dry_run=True)
            return True
        except (messaging.UnregisteredError, messaging.SenderIdMismatchError,
                fb_exceptions.InvalidArgumentError):
            return False
        except Exception:
            logger.exception("FCM validate failed (transient) %s", _tok(token))
            return True
