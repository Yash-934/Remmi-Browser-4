package com.remmi.browser.security.autofill

import android.content.Context
import android.util.Log
import com.remmi.browser.security.PasswordManagerRepository
import com.remmi.browser.security.crypto.DecryptedPasswordEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SavePasswordPromptRequest(
  val origin: String,
  val username: String,
  val password: String,
  val onSave: () -> Unit,
  val onDismiss: () -> Unit,
)

data class FillPasswordPromptRequest(
  val tabId: String,
  val origin: String,
  val credentials: List<DecryptedPasswordEntry>,
  val onSelect: (DecryptedPasswordEntry) -> Unit,
  val onDismiss: () -> Unit,
)

/**
 * Remmi Cyber Autofill & GeckoView Credential Delegate.
 * Enforces strict HTTPS verification, zero clearnet leaks, Fort Knox priority triage,
 * and zero-compromise credential security.
 */
class PasswordAutofillCoordinator(
  private val context: Context,
  private val scope: CoroutineScope,
  private val passwordRepo: PasswordManagerRepository,
) {

  private val _savePrompt = MutableStateFlow<SavePasswordPromptRequest?>(null)
  val savePrompt: StateFlow<SavePasswordPromptRequest?> = _savePrompt.asStateFlow()

  private val _fillPrompt = MutableStateFlow<FillPasswordPromptRequest?>(null)
  val fillPrompt: StateFlow<FillPasswordPromptRequest?> = _fillPrompt.asStateFlow()

  private val _showFortKnoxNotice = MutableStateFlow(false)
  val showFortKnoxNotice: StateFlow<Boolean> = _showFortKnoxNotice.asStateFlow()

  companion object {
    private const val TAG = "PasswordAutofillCoordinator"
  }

  init {
    checkFortKnox()
  }

  fun checkFortKnox() {
    if (passwordRepo.isFortKnoxInstalled()) {
      _showFortKnoxNotice.value = true
    }
  }

  fun dismissFortKnoxNotice() {
    _showFortKnoxNotice.value = false
  }

  fun dismissSavePrompt() {
    _savePrompt.value?.onDismiss?.invoke()
    _savePrompt.value = null
  }

  fun dismissFillPrompt() {
    _fillPrompt.value?.onDismiss?.invoke()
    _fillPrompt.value = null
  }

  fun triggerSavePrompt(url: String, user: String, pass: String) {
    if (!url.startsWith("https://", ignoreCase = true)) {
      Log.i(TAG, "Refusing password save on non-HTTPS origin ($url)")
      return
    }
    if (passwordRepo.isFortKnoxInstalled()) {
      Log.i(TAG, "Fort Knox priority active. Suppressing built-in save prompt.")
      return
    }

    _savePrompt.value = SavePasswordPromptRequest(
      origin = url,
      username = user,
      password = pass,
      onSave = {
        scope.launch {
          passwordRepo.saveOrUpdateEntry(
            url = url,
            username = user,
            password = pass,
          )
          _savePrompt.value = null
        }
      },
      onDismiss = {
        _savePrompt.value = null
      }
    )
  }

  fun checkForAutofill(tabId: String, url: String, onSelectCredential: (username: String, password: String) -> Unit) {
    if (!url.startsWith("https://", ignoreCase = true)) return
    if (passwordRepo.isFortKnoxInstalled()) return

    scope.launch(Dispatchers.IO) {
      val matches = passwordRepo.findAutofillCredentialsForUrl(url)
      if (matches.isNotEmpty()) {
        _fillPrompt.value = FillPasswordPromptRequest(
          tabId = tabId,
          origin = try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
          } catch(e: Exception) { url },
          credentials = matches,
          onSelect = { selected ->
            onSelectCredential(selected.username, selected.password)
            _fillPrompt.value = null
          },
          onDismiss = {
            _fillPrompt.value = null
          }
        )
      }
    }
  }
}
