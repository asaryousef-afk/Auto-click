package com.smarttouch.ai

import android.service.notification.NotificationListenerService

/**
 * Exists only to satisfy the OS requirement for MediaSessionManager.getActiveSessions()
 * and addOnActiveSessionsChangedListener() - both require a bound, user-enabled
 * NotificationListenerService component to be passed in, even though this app
 * never reads notification content itself. Nothing here reads notification
 * text/title/etc; it doesn't override onNotificationPosted() or
 * onNotificationRemoved() at all.
 */
class MediaSessionListenerService : NotificationListenerService()
