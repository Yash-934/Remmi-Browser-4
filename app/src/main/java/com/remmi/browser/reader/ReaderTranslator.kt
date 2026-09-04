package com.remmi.browser.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.json.JSONArray
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.Scanner

data class TranslationLanguage(
  val code: String,
  val displayName: String,
  val nativeName: String,
)

object ReaderTranslator {
  private const val TAG = "ReaderTranslator"

  val SUPPORTED_LANGUAGES = listOf(
    TranslationLanguage("hi", "Hindi", "हिन्दी"),
    TranslationLanguage("es", "Spanish", "Español"),
    TranslationLanguage("fr", "French", "Français"),
    TranslationLanguage("de", "German", "Deutsch"),
    TranslationLanguage("ja", "Japanese", "日本語"),
    TranslationLanguage("zh-CN", "Chinese", "中文"),
    TranslationLanguage("ru", "Russian", "Русский"),
    TranslationLanguage("ar", "Arabic", "العربية"),
    TranslationLanguage("pt", "Portuguese", "Português"),
    TranslationLanguage("it", "Italian", "Italiano"),
    TranslationLanguage("bn", "Bengali", "বাংলা"),
    TranslationLanguage("mr", "Marathi", "मराठी"),
    TranslationLanguage("ta", "Tamil", "தமிழ்"),
    TranslationLanguage("te", "Telugu", "తెలుగు"),
    TranslationLanguage("gu", "Gujarati", "ગુજરાતી"),
    TranslationLanguage("ko", "Korean", "한국어"),
  )

  /**
   * Translates a single text string to target language
   */
  suspend fun translateText(context: Context, text: String, targetLanguageCode: String, isGhost: Boolean = false): String = withContext(Dispatchers.IO) {
    if (text.isBlank()) return@withContext ""
    if (isGhost && !com.remmi.browser.security.CurrentTorRoute.isReady) {
      Log.w(TAG, "Ghost translation blocked: Tor route is not verified")
      return@withContext text
    }

    val boundedText = if (text.length > 5000) text.take(5000) else text

    try {
      kotlinx.coroutines.withTimeout(10_000L) {
        val runtime = com.remmi.browser.engine.GeckoEngineManager.getInstance(context).runtime
        if (runtime == null) {
          Log.w(TAG, "Ghost client creation failed: Gecko runtime not available")
          return@withTimeout text
        }

        val encoded = URLEncoder.encode(boundedText, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLanguageCode&dt=t&q=$encoded"

        val executor = GeckoWebExecutor(runtime)
        val request = WebRequest.Builder(url)
          .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0")
          .build()

        val response = executor.fetch(request).poll(10000)
        if (response == null || response.statusCode != 200) return@withTimeout text
        
        val bodyStream = response.body ?: return@withTimeout text
        val body = Scanner(bodyStream, "UTF-8").useDelimiter("\\A").next()
        bodyStream.close()

        val jsonArray = JSONArray(body)
          val sentencesArray = jsonArray.optJSONArray(0) ?: return@withTimeout text

          val sb = StringBuilder()
          for (i in 0 until sentencesArray.length()) {
            val sentenceObj = sentencesArray.optJSONArray(i)
            if (sentenceObj != null && sentenceObj.length() > 0) {
              sb.append(sentenceObj.optString(0))
            }
          }
          val result = sb.toString().trim()
          if (result.isNotBlank()) result else text
      }
    } catch (e: Exception) {
      Log.e(TAG, "Translation error for language $targetLanguageCode", e)
      text
    }
  }

  /**
   * Translates full ReaderArticle and returns new article instance with translated content
   */
  suspend fun translateArticle(
    context: Context,
    article: ReaderArticle,
    targetLanguageCode: String,
    isGhost: Boolean = false,
    onProgress: (Int, Int) -> Unit = { _, _ -> }
  ): ReaderArticle = withContext(Dispatchers.IO) {
    if (isGhost && !com.remmi.browser.security.CurrentTorRoute.isReady) {
      Log.w(TAG, "Ghost article translation blocked: Tor route is not verified")
      return@withContext article
    }

    val lang = SUPPORTED_LANGUAGES.firstOrNull { it.code == targetLanguageCode }?.displayName ?: targetLanguageCode

    val translatedTitle = translateText(context, article.title, targetLanguageCode, isGhost)
    val translatedParas = mutableListOf<ReaderParagraph>()

    val boundedParagraphs = article.paragraphs.take(200)
    val total = boundedParagraphs.size
    for ((idx, p) in boundedParagraphs.withIndex()) {
      onProgress(idx + 1, total)
      val translatedText = translateText(context, p.text, targetLanguageCode, isGhost)
      translatedParas.add(
        p.copy(text = translatedText)
      )
    }

    article.copy(
      translatedTitle = translatedTitle,
      translatedParagraphs = translatedParas,
      targetLanguage = lang,
    )
  }

  /**
   * Launch external Google Translate intent for a web URL or text snippet
   */
  fun launchExternalTranslator(context: Context, urlOrText: String, isGhost: Boolean = false) {
    if (isGhost) {
      Log.w(TAG, "External intent blocked in Ghost mode to prevent IP leak via ACTION_VIEW")
      return
    }
    try {
      val targetUri = if (urlOrText.startsWith("http://") || urlOrText.startsWith("https://")) {
        Uri.parse("https://translate.google.com/translate?sl=auto&tl=hi&u=${URLEncoder.encode(urlOrText, "UTF-8")}")
      } else {
        Uri.parse("https://translate.google.com/?sl=auto&tl=hi&text=${URLEncoder.encode(urlOrText, "UTF-8")}&op=translate")
      }
      val intent = Intent(Intent.ACTION_VIEW, targetUri)
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to launch external translator", e)
    }
  }
}
