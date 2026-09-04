package com.remmi.browser.reader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TtsPlayState {
  IDLE,
  PLAYING,
  PAUSED,
  STOPPED,
  ERROR
}

data class SpeechState(
  val playState: TtsPlayState = TtsPlayState.IDLE,
  val currentParagraphIndex: Int = 0,
  val totalParagraphs: Int = 0,
  val speechRate: Float = 1.0f, // 0.75x, 1.0x, 1.25x, 1.5x, 2.0x
  val isReady: Boolean = false,
  val currentTextSnippet: String = "",
)

class ReaderSpeechManager(private val context: Context) : TextToSpeech.OnInitListener {

  private val _state = MutableStateFlow(SpeechState())
  val state: StateFlow<SpeechState> = _state.asStateFlow()

  private var tts: TextToSpeech? = null
  private var paragraphsToSpeak: List<ReaderParagraph> = emptyList()
  private var currentIndex = 0
  private var isInitialized = false

  init {
    try {
      tts = TextToSpeech(context.applicationContext, this)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize TTS", e)
    }
  }

  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      val langResult = tts?.setLanguage(Locale.getDefault())
      if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
        tts?.setLanguage(Locale.US)
      }
      isInitialized = true
      _state.value = _state.value.copy(isReady = true)

      tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
          _state.value = _state.value.copy(playState = TtsPlayState.PLAYING)
        }

        override fun onDone(utteranceId: String?) {
          val nextIndex = currentIndex + 1
          if (nextIndex < paragraphsToSpeak.size) {
            currentIndex = nextIndex
            speakCurrentParagraph()
          } else {
            _state.value = _state.value.copy(
              playState = TtsPlayState.STOPPED,
              currentParagraphIndex = 0
            )
          }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
          _state.value = _state.value.copy(playState = TtsPlayState.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
          Log.w(TAG, "TTS Utterance error: $errorCode")
          _state.value = _state.value.copy(playState = TtsPlayState.ERROR)
        }
      })
    } else {
      Log.e(TAG, "TTS Initialization failed with status: $status")
    }
  }

  fun setArticle(paragraphs: List<ReaderParagraph>) {
    paragraphsToSpeak = paragraphs.filter { it.text.isNotBlank() }
    currentIndex = 0
    _state.value = _state.value.copy(
      totalParagraphs = paragraphsToSpeak.size,
      currentParagraphIndex = 0,
      currentTextSnippet = paragraphsToSpeak.firstOrNull()?.text?.take(80) ?: ""
    )
  }

  fun playFrom(index: Int = 0) {
    if (!isInitialized || paragraphsToSpeak.isEmpty()) return
    currentIndex = index.coerceIn(0, paragraphsToSpeak.size - 1)
    tts?.setSpeechRate(_state.value.speechRate)
    speakCurrentParagraph()
  }

  fun togglePlayPause() {
    when (_state.value.playState) {
      TtsPlayState.PLAYING -> pause()
      TtsPlayState.PAUSED, TtsPlayState.STOPPED, TtsPlayState.IDLE -> {
        if (paragraphsToSpeak.isNotEmpty()) {
          playFrom(currentIndex)
        }
      }
      TtsPlayState.ERROR -> playFrom(currentIndex)
    }
  }

  fun pause() {
    tts?.stop()
    _state.value = _state.value.copy(playState = TtsPlayState.PAUSED)
  }

  fun stop() {
    tts?.stop()
    currentIndex = 0
    _state.value = _state.value.copy(
      playState = TtsPlayState.STOPPED,
      currentParagraphIndex = 0
    )
  }

  fun next() {
    if (currentIndex + 1 < paragraphsToSpeak.size) {
      currentIndex++
      playFrom(currentIndex)
    }
  }

  fun previous() {
    if (currentIndex > 0) {
      currentIndex--
      playFrom(currentIndex)
    }
  }

  fun setSpeechRate(rate: Float) {
    _state.value = _state.value.copy(speechRate = rate)
    tts?.setSpeechRate(rate)
  }

  private fun speakCurrentParagraph() {
    if (currentIndex >= paragraphsToSpeak.size) return
    val paragraph = paragraphsToSpeak[currentIndex]
    _state.value = _state.value.copy(
      playState = TtsPlayState.PLAYING,
      currentParagraphIndex = paragraph.index,
      currentTextSnippet = paragraph.text.take(80)
    )

    val params = Bundle()
    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "reader_p_$currentIndex")
    tts?.speak(paragraph.text, TextToSpeech.QUEUE_FLUSH, params, "reader_p_$currentIndex")
  }

  fun shutdown() {
    try {
      tts?.stop()
      tts?.shutdown()
    } catch (e: Exception) {
      Log.e(TAG, "Error shutting down TTS", e)
    }
  }

  companion object {
    private const val TAG = "ReaderSpeechManager"
  }
}
