package com.chakamyth.hands

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * A second pair of ears, whose only job is to say whether a HUMAN spoke.
 *
 * This is the owner's design from his website assistant, which sits in a noisy
 * market and does not react to music, crowds or singing — but answers the
 * moment he actually speaks to it. There, the browser's own recogniser runs
 * beside the audio stream with interimResults=false, so it reports only when
 * real WORDS were recognised. Noise never produces a final result. That is the
 * whole trick, and it is a much better signal than voice-activity detection,
 * which fires on any sound at all.
 *
 * The obvious Android translation — start a SpeechRecognizer — does not work,
 * because it opens the microphone and Live Mode is already streaming from it.
 * EXTRA_AUDIO_SOURCE (API 33) solves that: the recogniser can be handed a pipe
 * instead of a microphone, so the SAME PCM already being read for the Live
 * socket is copied here as well. One microphone, two listeners.
 *
 * Nothing here may break Live Mode. Every call is wrapped, every failure is
 * silent and degrades to "I could not tell", and the caller treats not knowing
 * as permission to proceed — being deaf to the owner is far worse than being
 * fooled by the occasional stray phrase.
 */
class ChakaEars(private val context: Context) {

  companion object {
    private const val TAG = "ChakaEars"
    private const val RATE = 16000
  }

  private val main = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  private var writeEnd: ParcelFileDescriptor.AutoCloseOutputStream? = null
  private var readEnd: ParcelFileDescriptor? = null

  @Volatile private var listening = false
  @Volatile var available = false
    private set

  /** What it last recognised, and when — the evidence a person actually spoke. */
  @Volatile var lastWords = ""
    private set
  @Volatile var lastWordsAt = 0L
    private set

  fun start() {
    if (Build.VERSION.SDK_INT < 33) {
      Log.i(TAG, "API ${Build.VERSION.SDK_INT} < 33 — no piped audio source, second ears disabled")
      return
    }
    main.post { runCatching { startOnMain() }.onFailure { Log.e(TAG, "start: ${it.message}") } }
  }

  private fun startOnMain() {
    // On-device where possible: this runs constantly beside a live call, so it
    // must not be a network round trip or a bill.
    recognizer = runCatching {
      if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
      } else null
    }.getOrNull() ?: runCatching {
      if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }.getOrNull()

    if (recognizer == null) {
      Log.w(TAG, "no recogniser on this device — falling back to transcript checking alone")
      return
    }
    available = true
    recognizer?.setRecognitionListener(object : RecognitionListener {
      override fun onResults(results: Bundle?) {
        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          ?.firstOrNull()?.trim().orEmpty()
        // A final result at all means words were recognised. Noise does not get
        // this far — that is precisely why this is a better signal than VAD.
        if (heard.length >= 2) {
          lastWords = heard
          lastWordsAt = System.currentTimeMillis()
          Log.i(TAG, "human speech confirmed: \"${heard.take(60)}\"")
        }
        restart()
      }

      override fun onError(error: Int) {
        // ERROR_NO_MATCH and ERROR_SPEECH_TIMEOUT are the normal outcome of a
        // room full of noise. They are the system telling us nobody spoke.
        restart()
      }

      override fun onReadyForSpeech(params: Bundle?) {}
      override fun onBeginningOfSpeech() {}
      override fun onRmsChanged(rmsdB: Float) {}
      override fun onBufferReceived(buffer: ByteArray?) {}
      override fun onEndOfSpeech() {}
      override fun onPartialResults(partialResults: Bundle?) {}
      override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    listen()
  }

  /** A fresh pipe per listening turn — the recogniser consumes its read end. */
  private fun listen() {
    if (Build.VERSION.SDK_INT < 33) return
    runCatching {
      closePipe()
      val pipe = ParcelFileDescriptor.createPipe()
      readEnd = pipe[0]
      writeEnd = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        // The audio Chaka is already reading, rather than a second microphone.
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
      }
      recognizer?.startListening(intent)
      listening = true
    }.onFailure {
      listening = false
      Log.e(TAG, "listen: ${it.message}")
    }
  }

  private fun restart() {
    listening = false
    main.postDelayed({ if (recognizer != null) listen() }, 250)
  }

  /**
   * The same PCM going up the Live socket, copied here. Cheap and non-blocking:
   * if the recogniser is not currently listening the bytes are dropped, because
   * a stalled write must never slow the microphone thread that keeps the live
   * conversation alive.
   */
  fun feed(pcm: ByteArray, length: Int) {
    if (!listening) return
    runCatching { writeEnd?.write(pcm, 0, length) }
      .onFailure { listening = false }
  }

  /**
   * Did a person say something intelligible in the last [withinMs]?
   *
   * Returns null when we genuinely cannot tell — no recogniser on this device,
   * or it has not had a chance to report. The caller must treat null as "carry
   * on", never as "ignore them".
   */
  fun heardHumanSpeech(withinMs: Long = 6000): Boolean? {
    if (!available) return null
    if (lastWordsAt == 0L) return null
    return System.currentTimeMillis() - lastWordsAt <= withinMs
  }

  private fun closePipe() {
    runCatching { writeEnd?.close() }; writeEnd = null
    runCatching { readEnd?.close() }; readEnd = null
  }

  fun stop() {
    listening = false
    available = false
    main.post {
      runCatching { recognizer?.stopListening() }
      runCatching { recognizer?.destroy() }
      recognizer = null
      closePipe()
    }
  }
}
