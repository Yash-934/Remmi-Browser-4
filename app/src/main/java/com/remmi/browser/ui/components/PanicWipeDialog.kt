package com.remmi.browser.ui.components

import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.remmi.browser.security.PanicWipeManager
import com.remmi.browser.security.PanicWipeState
import com.remmi.browser.security.PasswordManagerRepository
import com.remmi.browser.security.WipePhase
import com.remmi.browser.security.WipeStepTelemetry
import com.remmi.browser.security.WipeVerifier
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.launch

@Composable
fun PanicWipeDialog(
  onDismiss: () -> Unit,
  onWipeExecuted: () -> Unit = {},
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val vaultRepo = remember { PasswordManagerRepository.getInstance(context) }
  val wipeState by PanicWipeManager.state.collectAsState()

  var wipeVault by remember { mutableStateOf(false) }
  var credentialInput by remember { mutableStateOf("") }
  var credentialError by remember { mutableStateOf<String?>(null) }
  var isAuthenticating by remember { mutableStateOf(false) }

  val canUseBiometrics = remember {
    vaultRepo.isBiometricAvailable()
  }

  // Reset panic wipe state when dialog opens
  LaunchedEffect(Unit) {
    PanicWipeManager.resetState()
  }

  val isWiping = wipeState is PanicWipeState.InProgress || isAuthenticating

  fun proceedWithWipe() {
    isAuthenticating = false
    credentialError = null
    scope.launch {
      val success = PanicWipeManager.executeWipe(
        context = context,
        wipeVault = wipeVault,
        onTabsClosed = {
          onWipeExecuted()
        }
      )
      if (success) {
        Toast.makeText(context, "Panic wipe executed. Closing Remmi Browser...", Toast.LENGTH_SHORT).show()
        onDismiss()
        kotlinx.coroutines.delay(600)
        val activity = context as? android.app.Activity
        activity?.finishAffinity()
        kotlin.system.exitProcess(0)
      }
    }
  }

  fun authenticateAndWipe() {
    if (isWiping) return
    isAuthenticating = true
    credentialError = null

    scope.launch {
      val metadata = vaultRepo.getMasterKeyMetadata()
      if (metadata == null) {
        // Vault uninitialized; direct safety wipe
        proceedWithWipe()
        return@launch
      }

      val inputChars = credentialInput.toCharArray()
      if (inputChars.isEmpty()) {
        isAuthenticating = false
        credentialError = "Enter your Master Password or PIN to confirm wipe."
        return@launch
      }

      // Check credential against Password or PIN
      val verified = vaultRepo.verifyMasterCredentialForDestructiveAction(
        password = inputChars,
        pin = inputChars,
      )

      if (verified) {
        proceedWithWipe()
      } else {
        isAuthenticating = false
        credentialError = "Authentication failed: Incorrect password or PIN."
      }
    }
  }

  fun launchBiometricPrompt() {
    val fragmentActivity = context as? FragmentActivity
    if (fragmentActivity == null) {
      authenticateAndWipe()
      return
    }

    val executor = ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(
      fragmentActivity,
      executor,
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          proceedWithWipe()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
            Toast.makeText(context, "Biometric failed: $errString", Toast.LENGTH_SHORT).show()
          }
        }

        override fun onAuthenticationFailed() {
          Toast.makeText(context, "Biometric authentication failed", Toast.LENGTH_SHORT).show()
        }
      }
    )

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
      .setTitle("Authorize Panic Wipe")
      .setSubtitle("Confirm emergency destruction of browser data")
      .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
      .setNegativeButtonText("Use PIN / Password")
      .build()

    prompt.authenticate(promptInfo)
  }

  Dialog(onDismissRequest = { if (!isWiping) onDismiss() }) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
      modifier = Modifier
        .fillMaxWidth()
        .padding(4.dp)
        .border(1.5.dp, ThemeCyber.colors.dangerRed.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Warning Icon Banner
        Box(
          modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ThemeCyber.colors.dangerRed.copy(alpha = 0.12f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            Icons.Default.Warning,
            contentDescription = "Panic Warning",
            tint = ThemeCyber.colors.dangerRed,
            modifier = Modifier.size(32.dp)
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = "EMERGENCY DATA WIPE",
          color = ThemeCyber.colors.dangerRed,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = ThemeCyber.fontFamily,
          letterSpacing = 0.8.sp,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
          text = "This action will immediately and irreversibly purge all active tabs, web cache, cookies, browsing history, download records, and defense logs.",
          color = ThemeCyber.colors.textSecondary,
          fontSize = 13.sp,
          textAlign = TextAlign.Center,
          lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Checkbox: Wipe Vault
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
          color = ThemeCyber.colors.background,
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
          shape = RoundedCornerShape(12.dp)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Checkbox(
              checked = wipeVault,
              onCheckedChange = { if (!isWiping) wipeVault = it },
              enabled = !isWiping,
              colors = CheckboxDefaults.colors(
                checkedColor = ThemeCyber.colors.dangerRed,
                uncheckedColor = ThemeCyber.colors.textMuted
              )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
              Text(
                text = "Also purge Password Vault",
                color = ThemeCyber.colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
              )
              Text(
                text = "Permanently deletes all saved logins & master keys",
                color = ThemeCyber.colors.textMuted,
                fontSize = 11.sp
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (wipeState is PanicWipeState.InProgress) {
          val progressInfo = wipeState as PanicWipeState.InProgress
          Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            LinearProgressIndicator(
              progress = { progressInfo.progress },
              color = ThemeCyber.colors.dangerRed,
              trackColor = ThemeCyber.colors.surfaceBorder,
              modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = progressInfo.phaseDescription,
              color = ThemeCyber.colors.textPrimary,
              fontSize = 12.sp,
              fontFamily = ThemeCyber.fontFamily,
              textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "${(progressInfo.progress * 100).toInt()}%",
              color = ThemeCyber.colors.dangerRed,
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp
            )
          }
        } else {
          // PIN / Master Password Authentication Entry
          OutlinedTextField(
            value = credentialInput,
            onValueChange = {
              credentialInput = it
              credentialError = null
            },
            enabled = !isWiping,
            label = { Text("Master Password or PIN", fontSize = 12.sp) },
            placeholder = { Text("Enter credential to authorize", fontSize = 12.sp) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
              keyboardType = KeyboardType.Password,
              imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
              onDone = {
                if (credentialInput.isNotBlank()) authenticateAndWipe()
              }
            ),
            isError = credentialError != null,
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ThemeCyber.colors.dangerRed,
              unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
              errorBorderColor = ThemeCyber.colors.dangerRed,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          )

          if (credentialError != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = credentialError!!,
              color = ThemeCyber.colors.dangerRed,
              fontSize = 12.sp,
              textAlign = TextAlign.Center
            )
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action Buttons
        if (wipeState !is PanicWipeState.InProgress) {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            // Biometric Option if available
            if (canUseBiometrics) {
              Button(
                onClick = { launchBiometricPrompt() },
                enabled = !isWiping,
                colors = ButtonDefaults.buttonColors(
                  containerColor = ThemeCyber.colors.surfaceLight,
                  contentColor = ThemeCyber.colors.textPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ThemeCyber.colors.primary),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(46.dp)
              ) {
                Icon(Icons.Default.Fingerprint, null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  "Authenticate with Biometrics",
                  fontSize = 13.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = ThemeCyber.colors.primary
                )
              }
            }

            // Confirm Button
            Button(
              onClick = {
                if (credentialInput.isNotBlank()) {
                  authenticateAndWipe()
                } else if (canUseBiometrics) {
                  launchBiometricPrompt()
                } else {
                  authenticateAndWipe()
                }
              },
              enabled = !isWiping,
              colors = ButtonDefaults.buttonColors(
                containerColor = ThemeCyber.colors.dangerRed,
                contentColor = Color.White
              ),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
            ) {
              if (isAuthenticating) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
              } else {
                Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  "EXECUTE PANIC WIPE",
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold,
                  fontFamily = ThemeCyber.fontFamily
                )
              }
            }

            // Cancel Button
            TextButton(
              onClick = onDismiss,
              enabled = !isWiping,
              modifier = Modifier.fillMaxWidth()
            ) {
              Text("Cancel", color = ThemeCyber.colors.textMuted, fontSize = 13.sp)
            }
          }
        }
      }
    }
  }
}
