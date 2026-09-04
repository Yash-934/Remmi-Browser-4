package com.remmi.browser.security

import org.json.JSONObject

object CredentialAutofillScript {

  /**
   * Generates a safe JavaScript snippet to autofill username and password fields
   * without string interpolation vulnerabilities or injection attacks.
   *
   * Uses standard JSON string literal encoding via JSONObject.quote to ensure
   * quotes, newlines, backslashes, control characters, script tags, and Unicode
   * are escaped according to the ECMAScript standard.
   */
  fun generateSafeAutofillScript(username: String, password: String): String {
    val quotedUser = JSONObject.quote(username)
    val quotedPass = JSONObject.quote(password)

    return """
      (function() {
        try {
          if (window !== window.top) {
             console.error('Autofill blocked: Script must run in top-level context');
             return;
          }
          var u = $quotedUser;
          var p = $quotedPass;
          var inputs = document.querySelectorAll('input');
          var userInput = null, passInput = null;
          for (var i = 0; i < inputs.length; i++) {
            var t = (inputs[i].type || '').toLowerCase();
            var n = (inputs[i].name || '').toLowerCase();
            var id = (inputs[i].id || '').toLowerCase();
            var ac = (inputs[i].autocomplete || '').toLowerCase();
            if (t === 'password') {
              passInput = inputs[i];
            } else if (t === 'text' || t === 'email' || ac.includes('user') || ac.includes('email') || n.includes('user') || n.includes('email') || id.includes('user') || id.includes('email') || n.includes('login') || id.includes('login')) {
              if (!userInput) userInput = inputs[i];
            }
          }
          if (userInput) { 
            userInput.value = u; 
            userInput.dispatchEvent(new Event('input', {bubbles:true})); 
            userInput.dispatchEvent(new Event('change', {bubbles:true}));
          }
          if (passInput) { 
            passInput.value = p; 
            passInput.dispatchEvent(new Event('input', {bubbles:true})); 
            passInput.dispatchEvent(new Event('change', {bubbles:true}));
          }
        } catch (e) {
          console.error('Autofill execution error: ' + e);
        }
      })();
    """.trimIndent()
  }
}
