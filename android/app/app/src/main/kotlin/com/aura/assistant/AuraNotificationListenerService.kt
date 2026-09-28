package com.aura.assistant

import android.service.notification.NotificationListenerService

/**
 * This service reads and stores no notification content. Its only job is to
 * exist as an *enabled* notification-listener component: that's the
 * permission Android requires before [android.media.session.MediaSessionManager]
 * will hand back the phone's currently-active media sessions, which is what
 * real play/pause/next/previous control needs (see MainActivity.controlActiveMedia).
 *
 * The user has to turn this on manually under
 * Settings > Apps > Special access > Notification access > Aura — no app
 * can grant this permission to itself.
 */
class AuraNotificationListenerService : NotificationListenerService()
