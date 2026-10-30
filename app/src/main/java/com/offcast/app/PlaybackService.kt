package com.offcast.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * El reproductor vive acá y no en la pantalla. Por eso:
 *  - sigue sonando con la pantalla bloqueada o la app en segundo plano (servicio en primer plano),
 *  - muestra los controles en la notificación y la pantalla de bloqueo (-10 s / pausa / +30 s),
 *  - guarda por dónde ibas aunque Android cierre la interfaz.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            saveNow()
            if (exo.isPlaying) handler.postDelayed(this, 5000)
        }
    }

    private val back = SessionCommand(ACTION_BACK, Bundle.EMPTY)
    private val forward = SessionCommand(ACTION_FORWARD, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true) // pausa si se desconectan los auriculares
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(ticker)
                if (isPlaying) handler.postDelayed(ticker, 5000) else saveNow(force = true)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exo.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }?.let {
                        Core.markPlayed(it, exo.duration.coerceAtLeast(0L))
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val oldId = oldPosition.mediaItem?.mediaId
                val newId = newPosition.mediaItem?.mediaId
                if (oldId != null && oldId.isNotEmpty() && oldId != newId) {
                    // Cambió de episodio: guardamos dónde quedó el anterior.
                    Core.saveListen(oldId, oldPosition.positionMs, 0L, force = true)
                } else {
                    saveNow()
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val layout = ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("Retroceder 10 s")
                .setSessionCommand(back)
                .setIconResId(R.drawable.ic_replay_10)
                .build(),
            CommandButton.Builder()
                .setDisplayName("Adelantar 30 s")
                .setSessionCommand(forward)
                .setIconResId(R.drawable.ic_forward_30)
                .build()
        )

        session = MediaSession.Builder(this, NoSkipPlayer(exo))
            .setCallback(Callback())
            .setSessionActivity(openApp)
            .build()
        session?.setCustomLayout(layout)

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.ic_stat) }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Si cerrás la app desde recientes y NO estaba sonando, el servicio se apaga. Si sonaba, sigue. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exo.playWhenReady || exo.mediaItemCount == 0 || exo.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        saveNow(force = true)
        session?.run {
            release()
            session = null
        }
        exo.release()
        super.onDestroy()
    }

    private fun saveNow(force: Boolean = false) {
        val item = exo.currentMediaItem
        if (item != null && item.mediaId.isNotEmpty() && exo.playbackState != Player.STATE_ENDED) {
            Core.saveListen(item.mediaId, exo.currentPosition, exo.duration.coerceAtLeast(0L), force)
        }
        // Al pausar o cerrar, lo que quedó pendiente de escritura se guarda igual.
        if (force) Core.flushListen()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val commands = base.availableSessionCommands.buildUpon().add(back).add(forward).build()
            return MediaSession.ConnectionResult.accept(commands, base.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_BACK -> exo.seekBack()
                ACTION_FORWARD -> exo.seekForward()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Oculta "anterior/siguiente" (no hay playlist): en su lugar van los botones -10 s / +30 s. */
    private class NoSkipPlayer(p: Player) : ForwardingPlayer(p) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .removeAll(
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    }

    companion object {
        const val ACTION_BACK = "offcast.back10"
        const val ACTION_FORWARD = "offcast.forward30"
    }
}
