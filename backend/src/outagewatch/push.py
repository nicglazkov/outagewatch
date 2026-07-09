"""FCM push sender. One Firebase Admin app per process, ADC on Cloud Run."""

from __future__ import annotations

import asyncio
import logging

from watcher.dispatch import PushMessage

logger = logging.getLogger(__name__)


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
        try:
            await asyncio.to_thread(messaging.send, msg)
            return True
        except messaging.UnregisteredError:
            logger.info("dead token, dropping: %s...", message.token[:12])
            return False
        except Exception:
            logger.exception("FCM send failed for token %s...", message.token[:12])
            return True  # transient: do not treat the token as dead
