package app.voicereader

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Keeps TTS alive in the background and exposes lock-screen / headset controls. */
class PlaybackService : MediaSessionService() {

    private val app get() = application as App

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        app.session?.let { if (it !in sessions) addSession(it) } ?: stopSelf(startId)
        // After a process kill there is no session to restore, so don't let the system revive an empty service.
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = app.session
}
