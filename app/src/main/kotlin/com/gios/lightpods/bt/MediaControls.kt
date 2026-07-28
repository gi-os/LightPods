package com.gios.lightpods.bt

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * Transport and volume for whatever is playing through the earbuds.
 *
 * All of this is public API and needs no permission: `dispatchMediaKeyEvent` reaches
 * the active media session the same way the buttons on a wired headset do. It is the
 * only earbud control the Light Phone III can actually offer — the listening modes
 * live behind AAP, which the platform will not let us open. See the README.
 */
class MediaControls(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val isPlaying: Boolean get() = audio.isMusicActive

    fun playPause() = press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    fun next() = press(KeyEvent.KEYCODE_MEDIA_NEXT)

    fun previous() = press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    fun volumeUp() = adjust(AudioManager.ADJUST_RAISE)

    fun volumeDown() = adjust(AudioManager.ADJUST_LOWER)

    /** Current media volume as a fraction, for drawing the meter. */
    fun volume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    /** A media key is a press and a release; sending only the down event does nothing. */
    private fun press(keyCode: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun adjust(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }
}
