package com.remmi.browser.ui.passwords

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import com.remmi.browser.storage.MasterKeyMetadataEntity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.remmi.browser.security.ClipboardManager
import com.remmi.browser.security.PasswordManagerRepository
import com.remmi.browser.security.VaultLockState
import com.remmi.browser.security.crypto.DecryptedPasswordEntry
import com.remmi.browser.security.crypto.PasswordBackupManager
import com.remmi.browser.security.crypto.PasswordCryptoEngine
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PasswordManagerScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val repo = remember { PasswordManagerRepository.getInstance(context) }
  val clipboard = remember { ClipboardManager(context) }

  val lockState by repo.lockState.collectAsState()
  val failedAttempts by repo.failedAttempts.collectAsState()

  // Handle Android System Back and Gesture Back
  BackHandler(enabled = true) {
    onBack()
  }

  // Enforce FLAG_SECURE: Zero screenshot & screen capture leak
  DisposableEffect(Unit) {
    val activity = context as? Activity
    activity?.window?.setFlags(
      WindowManager.LayoutParams.FLAG_SECURE,
      WindowManager.LayoutParams.FLAG_SECURE
    )
    onDispose {
      activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }

  var entries by remember { mutableStateOf<List<DecryptedPasswordEntry>>(emptyList()) }
  var searchQuery by remember { mutableStateOf("") }
  var showAddDialog by remember { mutableStateOf(false) }
  var editingEntry by remember { mutableStateOf<DecryptedPasswordEntry?>(null) }
  var showGeneratorDialog by remember { mutableStateOf(false) }
  var showBackupDialog by remember { mutableStateOf(false) }
  var showSettingsDialog by remember { mutableStateOf(false) }

  // Lifecycle observer: Auto-lock vault on background or exit
  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        repo.lockVault()
        entries = emptyList()
        searchQuery = ""
        editingEntry = null
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      repo.lockVault()
      entries = emptyList()
      searchQuery = ""
      editingEntry = null
    }
  }

  // Refresh decrypted entries on unlock
  LaunchedEffect(lockState) {
    if (lockState is VaultLockState.Unlocked) {
      entries = repo.getDecryptedEntries()
    } else {
      entries = emptyList()
    }
  }

  val refreshEntries = {
    scope.launch {
      entries = repo.getDecryptedEntries()
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
  ) {
    when (val state = lockState) {
      is VaultLockState.CompromisedDevice -> {
        CompromisedDeviceView(onBack = onBack)
      }
      is VaultLockState.Uninitialized -> {
        SetupMasterPasswordView(
          onBack = onBack,
          onSetupSuccess = {
            refreshEntries()
          }
        )
      }
      is VaultLockState.TemporarilyLocked -> {
        TemporarilyLockedView(
          remainingSeconds = state.remainingSeconds,
          totalSeconds = state.totalSeconds,
          failedAttempts = failedAttempts,
          onBack = onBack,
        )
      }
      is VaultLockState.Locked -> {
        LockedVaultView(
          failedAttempts = failedAttempts,
          onBack = onBack,
          onUnlockSuccess = {
            refreshEntries()
          }
        )
      }
      is VaultLockState.Unlocked -> {
        UnlockedVaultView(
          entries = entries,
          searchQuery = searchQuery,
          onSearchChange = { searchQuery = it },
          onBack = onBack,
          onLock = { repo.lockVault() },
          onAddEntry = { showAddDialog = true },
          onEditEntry = { editingEntry = it },
          onDeleteEntry = { entry ->
            scope.launch {
              repo.deleteEntry(entry.id)
              refreshEntries()
              Toast.makeText(context, "Entry removed from vault.", Toast.LENGTH_SHORT).show()
            }
          },
          onOpenGenerator = { showGeneratorDialog = true },
          onOpenBackup = { showBackupDialog = true },
          onOpenSettings = { showSettingsDialog = true },
          onCopyUsername = { user ->
            clipboard.copyWithAutoClear(user, label = "Vault Username", clearAfterMs = 30000)
            Toast.makeText(context, "Username copied (Auto-clears in 30s)", Toast.LENGTH_SHORT).show()
          },
          onCopyPassword = { pass ->
            clipboard.copyWithAutoClear(pass, label = "Vault Password", clearAfterMs = 15000)
            Toast.makeText(context, "Password copied • Auto-clears in 15s", Toast.LENGTH_SHORT).show()
          },
        )
      }
    }

    // Add / Edit Entry Modal Dialog
    if (showAddDialog || editingEntry != null) {
      AddEditPasswordDialog(
        existingEntry = editingEntry,
        onDismiss = {
          showAddDialog = false
          editingEntry = null
        },
        onSave = { url, user, pass, notes, id ->
          scope.launch {
            repo.saveOrUpdateEntry(url, user, pass, notes, id)
            refreshEntries()
            showAddDialog = false
            editingEntry = null
            Toast.makeText(context, "Password saved to vault.", Toast.LENGTH_SHORT).show()
          }
        },
        onOpenGenerator = {
          showGeneratorDialog = true
        }
      )
    }

    // Password Generator Modal
    if (showGeneratorDialog) {
      PasswordGeneratorDialog(
        onDismiss = { showGeneratorDialog = false },
        onUsePassword = { pass ->
          clipboard.copyWithAutoClear(pass, label = "Generated Password", clearAfterMs = 15000)
          Toast.makeText(context, "Password copied to clipboard.", Toast.LENGTH_SHORT).show()
          showGeneratorDialog = false
        }
      )
    }

    // Backup & Restore Dialog
    if (showBackupDialog) {
      BackupRestoreDialog(
        entries = entries,
        dek = (lockState as? VaultLockState.Unlocked)?.dek ?: ByteArray(0),
        onDismiss = { showBackupDialog = false },
        onImportCompleted = {
          refreshEntries()
          Toast.makeText(context, "Backup restored successfully.", Toast.LENGTH_SHORT).show()
        }
      )
    }

    // Vault Security Settings Dialog
    if (showSettingsDialog) {
      VaultSettingsDialog(
        onDismiss = { showSettingsDialog = false },
        onSettingsUpdated = {
          refreshEntries()
        }
      )
    }
  }
}

// --- 1. First-Time Master Password Setup Screen ---
@Composable
private fun SetupMasterPasswordView(
  onBack: () -> Unit,
  onSetupSuccess: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val repo = remember { PasswordManagerRepository.getInstance(context) }

  var password by remember { mutableStateOf("") }
  var confirmPassword by remember { mutableStateOf("") }
  var pin by remember { mutableStateOf("") }
  var confirmPin by remember { mutableStateOf("") }
  var enablePin by remember { mutableStateOf(false) }
  var enableBiometric by remember { mutableStateOf(true) }
  var enableAutoWipe by remember { mutableStateOf(true) }
  var isSubmitting by remember { mutableStateOf(false) }

  val passChars = remember(password) { password.toCharArray() }
  val strength = remember(passChars) { PasswordCryptoEngine.evaluatePasswordStrength(passChars) }
  val isPassValid = strength.hasMinLength && strength.hasUppercase && strength.hasLowercase && strength.hasDigit && strength.hasSymbol
  val isPassMatch = password.isNotEmpty() && password == confirmPassword

  val pinChars = remember(pin) { pin.toCharArray() }
  val pinStrength = remember(pinChars) { PasswordCryptoEngine.evaluatePinStrength(pinChars) }
  val isPinFormatValid = !enablePin || (pinStrength.first && pin == confirmPin)

  val canSubmit = isPassValid && isPassMatch && isPinFormatValid && !isSubmitting

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .padding(20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ThemeCyber.colors.textPrimary)
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "Password Vault Setup",
        color = ThemeCyber.colors.textPrimary,
        fontFamily = ThemeCyber.fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
      )
    }

    LazyColumn(
      modifier = Modifier.weight(1f).fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      item {
        Spacer(modifier = Modifier.height(16.dp))

        Box(
          modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(ThemeCyber.colors.primary.copy(alpha = 0.12f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = ThemeCyber.colors.primary,
            modifier = Modifier.size(36.dp)
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = "Zero-Knowledge Master Key",
          color = ThemeCyber.colors.textPrimary,
          fontWeight = FontWeight.Bold,
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 18.sp,
        )
        Text(
          text = "Argon2id + AES-256-GCM Hardware-Backed Encryption",
          color = ThemeCyber.colors.textSecondary,
          fontSize = 12.sp,
          fontFamily = ThemeCyber.fontFamily,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Master Password Input
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text("Master Password", color = ThemeCyber.colors.textSecondary) },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("master_password_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Strength Meter
        LinearProgressIndicator(
          progress = { strength.score / 100f },
          modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(CircleShape),
          color = when {
            strength.score >= 80 -> ThemeCyber.colors.successGreen
            strength.score >= 50 -> ThemeCyber.colors.warningYellow
            else -> ThemeCyber.colors.dangerRed
          },
          trackColor = ThemeCyber.colors.surfaceLight,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
          text = "Strength: ${strength.score}% • ${strength.feedback}",
          color = if (isPassValid) ThemeCyber.colors.successGreen else ThemeCyber.colors.warningYellow,
          fontSize = 11.sp,
          fontFamily = ThemeCyber.fontFamily,
          fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Criteria badges
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          CriteriaTag("12+ chars", strength.hasMinLength)
          CriteriaTag("A-Z", strength.hasUppercase)
          CriteriaTag("a-z", strength.hasLowercase)
          CriteriaTag("0-9", strength.hasDigit)
          CriteriaTag("!@#$", strength.hasSymbol)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Confirm Master Password Input
        OutlinedTextField(
          value = confirmPassword,
          onValueChange = { confirmPassword = it },
          label = { Text("Confirm Master Password", color = ThemeCyber.colors.textSecondary) },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = if (isPassMatch) ThemeCyber.colors.successGreen else ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("confirm_master_password_input")
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Optional PIN Toggle
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = ThemeCyber.colors.surface,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder)
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                  Text("Quick Unlock PIN", color = ThemeCyber.colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = ThemeCyber.fontFamily)
                  Text("4-12 digits for rapid session unlock", color = ThemeCyber.colors.textSecondary, fontSize = 11.sp, fontFamily = ThemeCyber.fontFamily)
                }
              }
              Switch(
                checked = enablePin,
                onCheckedChange = { enablePin = it },
                colors = SwitchDefaults.colors(
                  checkedThumbColor = Color.White,
                  checkedTrackColor = ThemeCyber.colors.primary
                )
              )
            }

            if (enablePin) {
              Spacer(modifier = Modifier.height(12.dp))
              OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) pin = it },
                label = { Text("Master PIN (4-12 digits)", color = ThemeCyber.colors.textSecondary) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = ThemeCyber.colors.primary,
                  unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
                  focusedTextColor = ThemeCyber.colors.textPrimary,
                  unfocusedTextColor = ThemeCyber.colors.textPrimary,
                ),
                modifier = Modifier.fillMaxWidth()
              )

              Spacer(modifier = Modifier.height(8.dp))

              OutlinedTextField(
                value = confirmPin,
                onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) confirmPin = it },
                label = { Text("Confirm Master PIN", color = ThemeCyber.colors.textSecondary) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = if (pin.isNotEmpty() && pin == confirmPin) ThemeCyber.colors.successGreen else ThemeCyber.colors.primary,
                  unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
                  focusedTextColor = ThemeCyber.colors.textPrimary,
                  unfocusedTextColor = ThemeCyber.colors.textPrimary,
                ),
                modifier = Modifier.fillMaxWidth()
              )

              if (pin.isNotEmpty()) {
                val errorMsg = if (!pinStrength.first) pinStrength.second else if (pin != confirmPin) "PINs do not match" else "Valid PIN"
                val color = if (isPinFormatValid) ThemeCyber.colors.successGreen else ThemeCyber.colors.dangerRed
                Text(
                  text = errorMsg,
                  color = color,
                  fontSize = 11.sp,
                  fontFamily = ThemeCyber.fontFamily,
                  modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Biometric Toggle
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = ThemeCyber.colors.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enable Biometric Unlock", color = ThemeCyber.colors.textPrimary, fontSize = 13.5.sp, fontFamily = ThemeCyber.fontFamily)
          }
          Switch(
            checked = enableBiometric,
            onCheckedChange = { enableBiometric = it },
            colors = SwitchDefaults.colors(
              checkedThumbColor = Color.White,
              checkedTrackColor = ThemeCyber.colors.primary,
            )
          )
        }

        // Auto-Wipe Toggle
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, contentDescription = null, tint = ThemeCyber.colors.dangerRed)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Wipe after 10 failed attempts", color = ThemeCyber.colors.textPrimary, fontSize = 13.5.sp, fontFamily = ThemeCyber.fontFamily)
          }
          Switch(
            checked = enableAutoWipe,
            onCheckedChange = { enableAutoWipe = it },
            colors = SwitchDefaults.colors(
              checkedThumbColor = Color.White,
              checkedTrackColor = ThemeCyber.colors.dangerRed,
            )
          )
        }

        Spacer(modifier = Modifier.height(16.dp))
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Button(
      onClick = {
        if (canSubmit) {
          isSubmitting = true
          scope.launch {
            val pinToSave = if (enablePin && pin.isNotEmpty()) pin.toCharArray() else null
            val success = repo.setupMasterPassword(
              password = password.toCharArray(),
              pin = pinToSave,
              enableBiometrics = enableBiometric,
              autoWipe = enableAutoWipe,
            )
            isSubmitting = false
            if (success) {
              onSetupSuccess()
            } else {
              Toast.makeText(context, "Password setup failed. Check requirements.", Toast.LENGTH_SHORT).show()
            }
          }
        }
      },
      enabled = canSubmit,
      colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .height(50.dp)
        .testTag("initialize_vault_button")
    ) {
      if (isSubmitting) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
      } else {
        Text("SECURE & INITIALIZE VAULT", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = ThemeCyber.fontFamily)
      }
    }
  }
}

@Composable
private fun CriteriaTag(label: String, met: Boolean) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .border(
        width = 1.dp,
        color = if (met) ThemeCyber.colors.successGreen else ThemeCyber.colors.surfaceBorder,
        shape = RoundedCornerShape(8.dp)
      )
      .background(
        if (met) ThemeCyber.colors.successGreen.copy(alpha = 0.08f) else Color.Transparent,
        RoundedCornerShape(8.dp)
      )
      .padding(horizontal = 8.dp, vertical = 4.dp)
  ) {
    Icon(
      if (met) Icons.Default.Check else Icons.Default.Close,
      contentDescription = null,
      tint = if (met) ThemeCyber.colors.successGreen else ThemeCyber.colors.textSecondary,
      modifier = Modifier.size(12.dp)
    )
    Spacer(modifier = Modifier.width(4.dp))
    Text(
      text = label,
      color = if (met) ThemeCyber.colors.successGreen else ThemeCyber.colors.textSecondary,
      fontSize = 11.sp,
      fontFamily = ThemeCyber.fontFamily,
      fontWeight = FontWeight.Medium,
    )
  }
}

// --- 2. Locked Vault Screen ---
@Composable
private fun LockedVaultView(
  failedAttempts: Int,
  onBack: () -> Unit,
  onUnlockSuccess: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val repo = remember { PasswordManagerRepository.getInstance(context) }

  var metadata by remember { mutableStateOf<MasterKeyMetadataEntity?>(null) }
  var unlockMode by remember { mutableStateOf("PASSWORD") } // "PASSWORD" or "PIN"
  var credentialInput by remember { mutableStateOf("") }
  var isSubmitting by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val isBiometricAvail = remember { repo.isBiometricAvailable() }

  LaunchedEffect(Unit) {
    metadata = repo.getMasterKeyMetadata()
    if (metadata?.pinEnabled == true) {
      unlockMode = "PIN"
    }
  }

  val hasPin = metadata?.pinEnabled == true

  fun submitUnlock() {
    if (credentialInput.isEmpty() || isSubmitting) return
    isSubmitting = true
    errorMessage = null
    scope.launch {
      val res = if (unlockMode == "PIN") {
        repo.unlockWithMasterPin(credentialInput.toCharArray())
      } else {
        repo.unlockWithMasterPassword(credentialInput.toCharArray())
      }
      isSubmitting = false
      if (res.isSuccess) {
        onUnlockSuccess()
      } else {
        errorMessage = res.exceptionOrNull()?.message ?: "Authentication failed."
      }
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ThemeCyber.colors.textPrimary)
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "Password Vault",
        color = ThemeCyber.colors.textPrimary,
        fontFamily = ThemeCyber.fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
      )
    }

    Spacer(modifier = Modifier.height(28.dp))

    Box(
      modifier = Modifier
        .size(72.dp)
        .clip(CircleShape)
        .background(ThemeCyber.colors.primary.copy(alpha = 0.12f)),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        Icons.Default.Lock,
        contentDescription = null,
        tint = ThemeCyber.colors.primary,
        modifier = Modifier.size(38.dp)
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = "Vault Locked",
      color = ThemeCyber.colors.textPrimary,
      fontWeight = FontWeight.Bold,
      fontFamily = ThemeCyber.fontFamily,
      fontSize = 22.sp,
    )

    Text(
      text = if (unlockMode == "PIN") "Enter Master PIN to unlock vault" else "Enter Master Password to unlock encrypted vault",
      color = ThemeCyber.colors.textSecondary,
      fontSize = 13.sp,
      fontFamily = ThemeCyber.fontFamily,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
    )

    if (failedAttempts > 0) {
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "Warning: $failedAttempts failed attempts (Auto-wipe at 10)",
        color = ThemeCyber.colors.dangerRed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        fontFamily = ThemeCyber.fontFamily,
      )
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Switch between Password & PIN if PIN enabled
    if (hasPin) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(ThemeCyber.colors.surface)
          .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
      ) {
        Surface(
          modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable {
              unlockMode = "PIN"
              credentialInput = ""
              errorMessage = null
            },
          color = if (unlockMode == "PIN") ThemeCyber.colors.primary.copy(alpha = 0.18f) else Color.Transparent,
          shape = RoundedCornerShape(8.dp)
        ) {
          Text(
            text = "Master PIN",
            color = if (unlockMode == "PIN") ThemeCyber.colors.primary else ThemeCyber.colors.textSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            fontFamily = ThemeCyber.fontFamily,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
          )
        }

        Surface(
          modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable {
              unlockMode = "PASSWORD"
              credentialInput = ""
              errorMessage = null
            },
          color = if (unlockMode == "PASSWORD") ThemeCyber.colors.primary.copy(alpha = 0.18f) else Color.Transparent,
          shape = RoundedCornerShape(8.dp)
        ) {
          Text(
            text = "Master Password",
            color = if (unlockMode == "PASSWORD") ThemeCyber.colors.primary else ThemeCyber.colors.textSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            fontFamily = ThemeCyber.fontFamily,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
    }

    OutlinedTextField(
      value = credentialInput,
      onValueChange = {
        if (unlockMode == "PIN") {
          if (it.length <= 12 && it.all { c -> c.isDigit() }) {
            credentialInput = it
            errorMessage = null
          }
        } else {
          credentialInput = it
          errorMessage = null
        }
      },
      label = { Text(if (unlockMode == "PIN") "Master PIN" else "Master Password", color = ThemeCyber.colors.textSecondary) },
      visualTransformation = PasswordVisualTransformation(),
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
      keyboardOptions = KeyboardOptions(
        keyboardType = if (unlockMode == "PIN") KeyboardType.NumberPassword else KeyboardType.Password,
        imeAction = ImeAction.Done
      ),
      keyboardActions = KeyboardActions(onDone = { submitUnlock() }),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ThemeCyber.colors.primary,
        unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
        focusedTextColor = ThemeCyber.colors.textPrimary,
        unfocusedTextColor = ThemeCyber.colors.textPrimary,
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("unlock_credential_input")
    )

    if (errorMessage != null) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = errorMessage!!,
        color = ThemeCyber.colors.dangerRed,
        fontSize = 12.sp,
        fontFamily = ThemeCyber.fontFamily,
      )
    }

    Spacer(modifier = Modifier.height(20.dp))

    Button(
      onClick = { submitUnlock() },
      enabled = credentialInput.isNotEmpty() && !isSubmitting,
      colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .height(50.dp)
        .testTag("unlock_vault_button")
    ) {
      if (isSubmitting) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
      } else {
        Text("UNLOCK VAULT", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = ThemeCyber.fontFamily)
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Biometric Unlock Trigger
    val isBiometricConfigured = metadata?.biometricEnabled == true && metadata?.biometricWrappedDek != null
    if (isBiometricAvail && isBiometricConfigured) {
      OutlinedButton(
        onClick = {
          val activity = context as? FragmentActivity
          if (activity != null) {
            scope.launch {
              val cipherRes = repo.prepareBiometricDecryptCipher()
              if (cipherRes.isSuccess) {
                val cipher = cipherRes.getOrThrow()
                val executor = ContextCompat.getMainExecutor(activity)
                val prompt = BiometricPrompt(
                  activity,
                  executor,
                  object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                      val cryptoCipher = result.cryptoObject?.cipher
                      if (cryptoCipher != null) {
                        scope.launch {
                          val unlockRes = repo.unlockWithBiometric(cryptoCipher)
                          if (unlockRes.isSuccess) {
                            onUnlockSuccess()
                          } else {
                            errorMessage = unlockRes.exceptionOrNull()?.message ?: "Biometric decryption failed."
                          }
                        }
                      }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                      if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        errorMessage = "Biometric error: $errString"
                      }
                    }

                    override fun onAuthenticationFailed() {
                      errorMessage = "Biometric authentication failed."
                    }
                  }
                )

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                  .setTitle("Unlock Password Vault")
                  .setSubtitle("Authenticate using hardware biometrics")
                  .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                  .setNegativeButtonText(if (hasPin) "Use Master PIN" else "Use Master Password")
                  .build()

                prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
              } else {
                errorMessage = cipherRes.exceptionOrNull()?.message ?: "Biometric initialization failed."
              }
            }
          }
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ThemeCyber.colors.primary),
        border = BorderStroke(1.dp, ThemeCyber.colors.primary)
      ) {
        Icon(Icons.Default.Fingerprint, contentDescription = null, tint = ThemeCyber.colors.primary)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Unlock with Biometrics",
          fontFamily = ThemeCyber.fontFamily,
          fontWeight = FontWeight.SemiBold
        )
      }
    }
  }
}

// --- 3. Temporarily Locked (Rate Limited) Screen ---
@Composable
private fun TemporarilyLockedView(
  remainingSeconds: Int,
  totalSeconds: Int,
  failedAttempts: Int,
  onBack: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      Icons.Default.Timer,
      contentDescription = null,
      tint = ThemeCyber.colors.dangerRed,
      modifier = Modifier.size(72.dp)
    )

    Spacer(modifier = Modifier.height(20.dp))

    Text(
      text = "Vault Temporarily Locked",
      color = ThemeCyber.colors.dangerRed,
      fontWeight = FontWeight.Bold,
      fontFamily = ThemeCyber.fontFamily,
      fontSize = 22.sp,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = "Rate limiting protection active due to $failedAttempts failed attempts.",
      color = ThemeCyber.colors.textSecondary,
      fontSize = 13.sp,
      fontFamily = ThemeCyber.fontFamily,
      textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
      text = "${remainingSeconds}s",
      color = ThemeCyber.colors.dangerRed,
      fontSize = 48.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = ThemeCyber.fontFamily,
    )

    Spacer(modifier = Modifier.height(16.dp))

    LinearProgressIndicator(
      progress = { (remainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f) },
      modifier = Modifier
        .fillMaxWidth(0.7f)
        .height(8.dp)
        .clip(CircleShape),
      color = ThemeCyber.colors.dangerRed,
      trackColor = ThemeCyber.colors.surfaceLight,
    )

    Spacer(modifier = Modifier.height(32.dp))

    OutlinedButton(
      onClick = onBack,
      shape = RoundedCornerShape(12.dp)
    ) {
      Text("Return to Browser", color = ThemeCyber.colors.textPrimary, fontFamily = ThemeCyber.fontFamily)
    }
  }
}

// --- 4. Compromised Device Security Block Screen ---
@Composable
private fun CompromisedDeviceView(onBack: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      Icons.Default.Warning,
      contentDescription = null,
      tint = ThemeCyber.colors.dangerRed,
      modifier = Modifier.size(80.dp)
    )

    Spacer(modifier = Modifier.height(20.dp))

    Text(
      text = "Hardware Isolation Active",
      color = ThemeCyber.colors.dangerRed,
      fontWeight = FontWeight.Bold,
      fontFamily = ThemeCyber.fontFamily,
      fontSize = 22.sp,
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
      text = "Root binary, debugger, or emulator environment detected. To prevent memory scraping and key extraction, Vault access is isolated on this runtime.",
      color = ThemeCyber.colors.textSecondary,
      fontSize = 13.sp,
      fontFamily = ThemeCyber.fontFamily,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 16.dp)
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(
      onClick = onBack,
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.dangerRed)
    ) {
      Text("Exit to Browser", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = ThemeCyber.fontFamily)
    }
  }
}

// --- 5. Unlocked Vault Main Dashboard ---
@Composable
private fun UnlockedVaultView(
  entries: List<DecryptedPasswordEntry>,
  searchQuery: String,
  onSearchChange: (String) -> Unit,
  onBack: () -> Unit,
  onLock: () -> Unit,
  onAddEntry: () -> Unit,
  onEditEntry: (DecryptedPasswordEntry) -> Unit,
  onDeleteEntry: (DecryptedPasswordEntry) -> Unit,
  onOpenGenerator: () -> Unit,
  onOpenBackup: () -> Unit,
  onOpenSettings: () -> Unit,
  onCopyUsername: (String) -> Unit,
  onCopyPassword: (String) -> Unit,
) {
  val context = LocalContext.current
  val repo = remember { PasswordManagerRepository.getInstance(context) }
  val isFortKnox = remember { repo.isFortKnoxInstalled() }

  val filteredEntries = remember(entries, searchQuery) {
    if (searchQuery.isBlank()) entries
    else {
      entries.filter {
        it.url.contains(searchQuery, ignoreCase = true) ||
            it.username.contains(searchQuery, ignoreCase = true) ||
            it.notes.contains(searchQuery, ignoreCase = true)
      }
    }
  }

  val securityScore = remember(entries) {
    repo.calculateSecurityScore(entries, null, true)
  }

  Scaffold(
    containerColor = ThemeCyber.colors.background,
    floatingActionButton = {
      FloatingActionButton(
        onClick = onAddEntry,
        containerColor = ThemeCyber.colors.primary,
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.testTag("add_password_fab")
      ) {
        Icon(Icons.Default.Add, contentDescription = "Add Password")
      }
    }
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp)
    ) {
      // Top Navigation Bar
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ThemeCyber.colors.textPrimary)
          }
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "Password Vault",
            color = ThemeCyber.colors.textPrimary,
            fontFamily = ThemeCyber.fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
          )
        }

        Row {
          IconButton(onClick = onOpenGenerator, modifier = Modifier.testTag("open_generator_btn")) {
            Icon(Icons.Default.VpnKey, contentDescription = "Generator", tint = ThemeCyber.colors.primary)
          }
          IconButton(onClick = onOpenBackup, modifier = Modifier.testTag("open_backup_btn")) {
            Icon(Icons.Default.Security, contentDescription = "Backup", tint = ThemeCyber.colors.torPurple)
          }
          IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings_btn")) {
            Icon(Icons.Default.Settings, contentDescription = "Vault Settings", tint = ThemeCyber.colors.textSecondary)
          }
          IconButton(onClick = onLock, modifier = Modifier.testTag("lock_vault_btn")) {
            Icon(Icons.Default.Lock, contentDescription = "Lock", tint = ThemeCyber.colors.dangerRed)
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Fort Knox Priority Banner if installed
      if (isFortKnox) {
        Card(
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
          modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
              Text("Autofill Active", color = ThemeCyber.colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, fontFamily = ThemeCyber.fontFamily)
              Text("Built-in autofill & save prompts enabled.", color = ThemeCyber.colors.textSecondary, fontSize = 12.sp, fontFamily = ThemeCyber.fontFamily)
            }
          }
        }
      }

      // Security Score Card
      Card(
        colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier.padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column {
            Text(
              "VAULT HEALTH SCORE",
              color = ThemeCyber.colors.textSecondary,
              fontSize = 11.sp,
              fontFamily = ThemeCyber.fontFamily,
              fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "${securityScore.score}% • ${securityScore.securityGrade}",
              color = if (securityScore.score >= 80) ThemeCyber.colors.successGreen else ThemeCyber.colors.warningYellow,
              fontWeight = FontWeight.Bold,
              fontSize = 18.sp,
              fontFamily = ThemeCyber.fontFamily,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = "${securityScore.totalAccounts} accounts • ${securityScore.weakPasswordsCount} weak • ${securityScore.reusedPasswordsCount} reused",
              color = ThemeCyber.colors.textSecondary,
              fontSize = 12.sp,
              fontFamily = ThemeCyber.fontFamily,
            )
          }

          CircularProgressIndicator(
            progress = { securityScore.score / 100f },
            modifier = Modifier.size(48.dp),
            color = if (securityScore.score >= 80) ThemeCyber.colors.successGreen else ThemeCyber.colors.warningYellow,
            trackColor = ThemeCyber.colors.surfaceLight,
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // In-Memory Search Bar
      OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        placeholder = { Text("Search passwords & sites...", color = ThemeCyber.colors.textSecondary, fontSize = 13.5.sp, fontFamily = ThemeCyber.fontFamily) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = ThemeCyber.colors.primary) },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { onSearchChange("") }) {
              Icon(Icons.Default.Close, contentDescription = "Clear", tint = ThemeCyber.colors.textSecondary)
            }
          }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = ThemeCyber.colors.primary,
          unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
          focusedTextColor = ThemeCyber.colors.textPrimary,
          unfocusedTextColor = ThemeCyber.colors.textPrimary,
          focusedContainerColor = ThemeCyber.colors.surface,
          unfocusedContainerColor = ThemeCyber.colors.surface,
        ),
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(modifier = Modifier.height(14.dp))

      // Credentials List
      if (filteredEntries.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
              Icons.Default.Key,
              contentDescription = null,
              tint = ThemeCyber.colors.textMuted,
              modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = if (searchQuery.isEmpty()) "Vault is empty.\nTap + to save a password." else "No passwords found for '$searchQuery'",
              color = ThemeCyber.colors.textSecondary,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 14.sp,
              textAlign = TextAlign.Center
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          items(filteredEntries, key = { it.id }) { entry ->
            PasswordEntryCard(
              entry = entry,
              onEdit = { onEditEntry(entry) },
              onDelete = { onDeleteEntry(entry) },
              onCopyUsername = { onCopyUsername(entry.username) },
              onCopyPassword = { onCopyPassword(entry.password) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PasswordEntryCard(
  entry: DecryptedPasswordEntry,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onCopyUsername: () -> Unit,
  onCopyPassword: () -> Unit,
) {
  val context = LocalContext.current
  val repo = remember { PasswordManagerRepository.getInstance(context) }
  var isExpanded by remember { mutableStateOf(false) }
  var isPasswordVisible by remember { mutableStateOf(false) }

  val host = PasswordCryptoEngine.extractCanonicalHost(entry.url)
  val initials = host.take(2).uppercase().ifEmpty { "PW" }

  Card(
    colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
    shape = RoundedCornerShape(16.dp),
    border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
    modifier = Modifier
      .fillMaxWidth()
      .clickable { isExpanded = !isExpanded }
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Initial / Icon badge
        Box(
          modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(ThemeCyber.colors.primary.copy(alpha = 0.12f)),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = initials,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = ThemeCyber.colors.primary,
            fontFamily = ThemeCyber.fontFamily
          )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = host,
            color = ThemeCyber.colors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            fontFamily = ThemeCyber.fontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = entry.username.ifEmpty { "(No username)" },
            color = ThemeCyber.colors.textSecondary,
            fontSize = 13.sp,
            fontFamily = ThemeCyber.fontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        Row {
          IconButton(onClick = onCopyUsername, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy User", tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(18.dp))
          }
          IconButton(onClick = onCopyPassword, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.Key, contentDescription = "Copy Pass", tint = ThemeCyber.colors.primary, modifier = Modifier.size(18.dp))
          }
        }
      }

      if (isExpanded) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ThemeCyber.colors.surfaceLight)
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text(
            text = if (isPasswordVisible) entry.password else "••••••••••••••••",
            color = ThemeCyber.colors.textPrimary,
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
          )
          IconButton(
            onClick = {
              if (isPasswordVisible) {
                isPasswordVisible = false
              } else {
                val activity = context as? FragmentActivity
                if (activity != null && repo.isBiometricAvailable()) {
                  showBiometricAuthPrompt(
                    activity = activity,
                    title = "Reveal Password",
                    subtitle = "Authenticate to view plaintext password",
                    onSuccess = { isPasswordVisible = true },
                    onError = {
                      isPasswordVisible = true
                    }
                  )
                } else {
                  isPasswordVisible = true
                }
              }
            },
            modifier = Modifier.size(32.dp)
          ) {
            Icon(
              if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
              contentDescription = "Toggle Pass",
              tint = ThemeCyber.colors.textSecondary,
              modifier = Modifier.size(18.dp)
            )
          }
        }

        if (entry.notes.isNotBlank()) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "Notes: ${entry.notes}",
            color = ThemeCyber.colors.textSecondary,
            fontSize = 12.sp,
            fontFamily = ThemeCyber.fontFamily,
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          TextButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Edit", color = ThemeCyber.colors.primary, fontSize = 13.sp, fontFamily = ThemeCyber.fontFamily)
          }
          TextButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = ThemeCyber.colors.dangerRed, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Delete", color = ThemeCyber.colors.dangerRed, fontSize = 13.sp, fontFamily = ThemeCyber.fontFamily)
          }
        }
      }
    }
  }
}

// --- 6. Add / Edit Password Dialog ---
@Composable
private fun AddEditPasswordDialog(
  existingEntry: DecryptedPasswordEntry?,
  onDismiss: () -> Unit,
  onSave: (url: String, user: String, pass: String, notes: String, id: Long) -> Unit,
  onOpenGenerator: () -> Unit,
) {
  var url by remember { mutableStateOf(existingEntry?.url ?: "https://") }
  var username by remember { mutableStateOf(existingEntry?.username ?: "") }
  var password by remember { mutableStateOf(existingEntry?.password ?: "") }
  var notes by remember { mutableStateOf(existingEntry?.notes ?: "") }
  var isPassVisible by remember { mutableStateOf(false) }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = ThemeCyber.colors.surface,
      border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = if (existingEntry != null) "Edit Password" else "Add New Password",
          color = ThemeCyber.colors.textPrimary,
          fontWeight = FontWeight.Bold,
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          label = { Text("Site URL or Name", color = ThemeCyber.colors.textSecondary) },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
          ),
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text("Username / Email", color = ThemeCyber.colors.textSecondary) },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
          ),
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text("Password", color = ThemeCyber.colors.textSecondary) },
          visualTransformation = if (isPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            Row {
              IconButton(onClick = { isPassVisible = !isPassVisible }) {
                Icon(if (isPassVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null, tint = ThemeCyber.colors.textSecondary)
              }
              IconButton(onClick = {
                password = PasswordCryptoEngine.generatePassword(20)
              }) {
                Icon(Icons.Default.Refresh, contentDescription = "Generate", tint = ThemeCyber.colors.primary)
              }
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
          ),
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = notes,
          onValueChange = { notes = it },
          label = { Text("Notes (Encrypted)", color = ThemeCyber.colors.textSecondary) },
          maxLines = 3,
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
          ),
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          TextButton(onClick = onDismiss) {
            Text("Cancel", color = ThemeCyber.colors.textSecondary, fontFamily = ThemeCyber.fontFamily)
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = {
              if (url.isNotBlank() && password.isNotBlank()) {
                onSave(url, username, password, notes, existingEntry?.id ?: 0L)
              }
            },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary)
          ) {
            Text("Save", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = ThemeCyber.fontFamily)
          }
        }
      }
    }
  }
}

// --- 7. Maximum-Security Password Generator Dialog ---
@Composable
private fun PasswordGeneratorDialog(
  onDismiss: () -> Unit,
  onUsePassword: (String) -> Unit,
) {
  var length by remember { mutableFloatStateOf(20f) }
  var includeUpper by remember { mutableStateOf(true) }
  var includeLower by remember { mutableStateOf(true) }
  var includeDigits by remember { mutableStateOf(true) }
  var includeSymbols by remember { mutableStateOf(true) }
  var excludeAmbiguous by remember { mutableStateOf(true) }

  var generatedPassword by remember {
    mutableStateOf(PasswordCryptoEngine.generatePassword(20, true, true, true, true, true))
  }

  val regenerate = {
    generatedPassword = PasswordCryptoEngine.generatePassword(
      length = length.toInt(),
      includeUpper = includeUpper,
      includeLower = includeLower,
      includeDigits = includeDigits,
      includeSymbols = includeSymbols,
      excludeAmbiguous = excludeAmbiguous,
    )
  }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = ThemeCyber.colors.surface,
      border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "Password Generator",
          color = ThemeCyber.colors.textPrimary,
          fontWeight = FontWeight.Bold,
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Generated Output Display
        Card(
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surfaceLight),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = generatedPassword,
            color = ThemeCyber.colors.primary,
            fontFamily = ThemeCyber.fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(14.dp).fillMaxWidth()
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
          text = "Length: ${length.toInt()} characters",
          color = ThemeCyber.colors.textPrimary,
          fontSize = 13.sp,
          fontFamily = ThemeCyber.fontFamily
        )

        Slider(
          value = length,
          onValueChange = {
            length = it
            regenerate()
          },
          valueRange = 12f..64f,
          colors = SliderDefaults.colors(
            thumbColor = ThemeCyber.colors.primary,
            activeTrackColor = ThemeCyber.colors.primary,
          )
        )

        // Options
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          GeneratorCheckbox("A-Z", includeUpper) { includeUpper = it; regenerate() }
          GeneratorCheckbox("a-z", includeLower) { includeLower = it; regenerate() }
          GeneratorCheckbox("0-9", includeDigits) { includeDigits = it; regenerate() }
          GeneratorCheckbox("!@#$", includeSymbols) { includeSymbols = it; regenerate() }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Checkbox(
            checked = excludeAmbiguous,
            onCheckedChange = { excludeAmbiguous = it; regenerate() },
            colors = CheckboxDefaults.colors(checkedColor = ThemeCyber.colors.primary)
          )
          Text("Exclude ambiguous (l, 1, I, O, 0)", color = ThemeCyber.colors.textSecondary, fontSize = 12.sp, fontFamily = ThemeCyber.fontFamily)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          IconButton(onClick = regenerate) {
            Icon(Icons.Default.Refresh, contentDescription = "Regenerate", tint = ThemeCyber.colors.primary)
          }

          Row {
            TextButton(onClick = onDismiss) {
              Text("Cancel", color = ThemeCyber.colors.textSecondary, fontFamily = ThemeCyber.fontFamily)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = { onUsePassword(generatedPassword) },
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary)
            ) {
              Text("Copy & Use", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = ThemeCyber.fontFamily)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun GeneratorCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Checkbox(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = CheckboxDefaults.colors(checkedColor = ThemeCyber.colors.primary)
    )
    Text(label, color = ThemeCyber.colors.textPrimary, fontSize = 12.sp, fontFamily = ThemeCyber.fontFamily)
  }
}

// --- 8. Encrypted Backup & Restore Dialog ---
@Composable
private fun BackupRestoreDialog(
  entries: List<DecryptedPasswordEntry>,
  dek: ByteArray,
  onDismiss: () -> Unit,
  onImportCompleted: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val repo = remember { PasswordManagerRepository.getInstance(context) }
  val clipboard = remember { ClipboardManager(context) }

  var isExportMode by remember { mutableStateOf(true) }
  var backupPassword by remember { mutableStateOf("") }
  var importPayload by remember { mutableStateOf("") }
  var isProcessing by remember { mutableStateOf(false) }
  var processingStatus by remember { mutableStateOf("") }

  Dialog(onDismissRequest = { if (!isProcessing) onDismiss() }) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = ThemeCyber.colors.surface,
      border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = if (isExportMode) "Encrypted Vault Backup" else "Restore from Backup",
          color = ThemeCyber.colors.textPrimary,
          fontWeight = FontWeight.Bold,
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 18.sp
        )

        if (isProcessing) {
          Spacer(modifier = Modifier.height(20.dp))
          CircularProgressIndicator(
            color = ThemeCyber.colors.primary,
            modifier = Modifier.size(36.dp),
            strokeWidth = 3.dp
          )
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = processingStatus.ifEmpty { "Processing cryptographic operations..." },
            color = ThemeCyber.colors.textPrimary,
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
          OutlinedButton(
            onClick = { if (!isProcessing) isExportMode = true },
            enabled = !isProcessing,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(
              containerColor = if (isExportMode) ThemeCyber.colors.primary.copy(alpha = 0.12f) else Color.Transparent,
              contentColor = if (isExportMode) ThemeCyber.colors.primary else ThemeCyber.colors.textSecondary
            )
          ) {
            Text("Export", fontFamily = ThemeCyber.fontFamily)
          }
          Spacer(modifier = Modifier.width(8.dp))
          OutlinedButton(
            onClick = { if (!isProcessing) isExportMode = false },
            enabled = !isProcessing,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(
              containerColor = if (!isExportMode) ThemeCyber.colors.primary.copy(alpha = 0.12f) else Color.Transparent,
              contentColor = if (!isExportMode) ThemeCyber.colors.primary else ThemeCyber.colors.textSecondary
            )
          ) {
            Text("Import", fontFamily = ThemeCyber.fontFamily)
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isExportMode) {
          Text(
            text = "Export ${entries.size} entries encrypted with AES-256-GCM + Argon2id.",
            color = ThemeCyber.colors.textSecondary,
            fontSize = 12.sp,
            fontFamily = ThemeCyber.fontFamily,
            textAlign = TextAlign.Center
          )

          Spacer(modifier = Modifier.height(12.dp))

          OutlinedTextField(
            value = backupPassword,
            onValueChange = { backupPassword = it },
            enabled = !isProcessing,
            label = { Text("Backup Password", color = ThemeCyber.colors.textSecondary) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ThemeCyber.colors.primary,
              unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
              focusedTextColor = ThemeCyber.colors.textPrimary,
              unfocusedTextColor = ThemeCyber.colors.textPrimary,
            ),
            modifier = Modifier.fillMaxWidth()
          )

          Spacer(modifier = Modifier.height(16.dp))

          Button(
            onClick = {
              if (!isProcessing) {
                isProcessing = true
                processingStatus = "Deriving Argon2id keys (AES-256-GCM)..."
                scope.launch(Dispatchers.IO) {
                  val db = com.remmi.browser.storage.RemmiDatabase.getDatabaseAsync(context)
                  val entities = db.passwordEntryDao().getAllEntriesList()
                  val backupJson = PasswordBackupManager.exportEncryptedBackup(
                    entries = entities,
                    exportPassword = if (backupPassword.isNotBlank()) backupPassword.toCharArray() else null,
                    dek = dek
                  )
                  withContext(Dispatchers.Main) {
                    isProcessing = false
                    clipboard.copyWithAutoClear(backupJson, label = "Encrypted Vault Backup", clearAfterMs = 60000)
                    Toast.makeText(context, "Encrypted backup copied to clipboard.", Toast.LENGTH_LONG).show()
                    onDismiss()
                  }
                }
              }
            },
            enabled = !isProcessing,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
            modifier = Modifier.fillMaxWidth()
          ) {
            if (isProcessing) {
              CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
              Text("Generate & Copy Backup", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = ThemeCyber.fontFamily)
            }
          }
        } else {
          OutlinedTextField(
            value = importPayload,
            onValueChange = { importPayload = it },
            enabled = !isProcessing,
            label = { Text("Paste Encrypted Backup JSON", color = ThemeCyber.colors.textSecondary) },
            maxLines = 4,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ThemeCyber.colors.primary,
              unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
              focusedTextColor = ThemeCyber.colors.textPrimary,
              unfocusedTextColor = ThemeCyber.colors.textPrimary,
            ),
            modifier = Modifier.fillMaxWidth()
          )

          Spacer(modifier = Modifier.height(10.dp))

          OutlinedTextField(
            value = backupPassword,
            onValueChange = { backupPassword = it },
            enabled = !isProcessing,
            label = { Text("Backup Password", color = ThemeCyber.colors.textSecondary) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ThemeCyber.colors.primary,
              unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
              focusedTextColor = ThemeCyber.colors.textPrimary,
              unfocusedTextColor = ThemeCyber.colors.textPrimary,
            ),
            modifier = Modifier.fillMaxWidth()
          )

          Spacer(modifier = Modifier.height(16.dp))

          Button(
            onClick = {
              if (importPayload.isNotBlank() && !isProcessing) {
                isProcessing = true
                processingStatus = "Verifying HMAC seal and decrypting..."
                scope.launch {
                  try {
                    val (backupDek, restoredEntries) = PasswordBackupManager.importEncryptedBackup(
                      backupJsonString = importPayload,
                      exportPassword = if (backupPassword.isNotBlank()) backupPassword.toCharArray() else null,
                      currentDek = dek
                    )

                    // Merge entries
                    for (item in restoredEntries) {
                      repo.saveOrUpdateEntry(
                        url = String(PasswordCryptoEngine.decryptAesGcm(backupDek, item.siteUrlEncrypted, item.iv, item.authTag), java.nio.charset.StandardCharsets.UTF_8),
                        username = String(PasswordCryptoEngine.decryptAesGcm(backupDek, item.usernameEncrypted, item.iv, item.authTag), java.nio.charset.StandardCharsets.UTF_8),
                        password = String(PasswordCryptoEngine.decryptAesGcm(backupDek, item.passwordEncrypted, item.iv, item.authTag), java.nio.charset.StandardCharsets.UTF_8),
                        notes = String(PasswordCryptoEngine.decryptAesGcm(backupDek, item.notesEncrypted, item.iv, item.authTag), java.nio.charset.StandardCharsets.UTF_8),
                      )
                    }
                    isProcessing = false
                    onImportCompleted()
                    onDismiss()
                  } catch (e: Exception) {
                    isProcessing = false
                    Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                  }
                }
              }
            },
            enabled = !isProcessing,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
            modifier = Modifier.fillMaxWidth()
          ) {
            if (isProcessing) {
              CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
              Text("Verify & Restore", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = ThemeCyber.fontFamily)
            }
          }
        }
      }
    }
  }
}

// --- 9. Vault Security Settings Dialog ---
@Composable
private fun VaultSettingsDialog(
  onDismiss: () -> Unit,
  onSettingsUpdated: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val repo = remember { PasswordManagerRepository.getInstance(context) }
  val isBiometricAvail = remember { repo.isBiometricAvailable() }

  var metadata by remember { mutableStateOf<MasterKeyMetadataEntity?>(null) }
  var isPinSectionOpen by remember { mutableStateOf(false) }
  var pinAction by remember { mutableStateOf("SETUP") } // "SETUP", "CHANGE", "REMOVE"

  var masterPasswordInput by remember { mutableStateOf("") }
  var newPinInput by remember { mutableStateOf("") }
  var confirmPinInput by remember { mutableStateOf("") }
  var isProcessing by remember { mutableStateOf(false) }
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var isError by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    metadata = repo.getMasterKeyMetadata()
  }

  val hasPin = metadata?.pinEnabled == true
  val isBiometricEnabled = metadata?.biometricEnabled == true && metadata?.biometricWrappedDek != null
  val isAutoWipeEnabled = metadata?.autoWipeEnabled ?: true

  Dialog(onDismissRequest = { if (!isProcessing) onDismiss() }) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = ThemeCyber.colors.surface,
      border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier
          .padding(20.dp)
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Vault Security Settings",
              color = ThemeCyber.colors.textPrimary,
              fontWeight = FontWeight.Bold,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 17.sp
            )
          }
          IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = ThemeCyber.colors.textSecondary)
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (statusMessage != null) {
          Text(
            text = statusMessage!!,
            color = if (isError) ThemeCyber.colors.dangerRed else ThemeCyber.colors.successGreen,
            fontSize = 12.sp,
            fontFamily = ThemeCyber.fontFamily,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 12.dp)
          )
        }

        // --- PIN Security Section ---
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = ThemeCyber.colors.surfaceLight,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder)
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                  Text("Quick Unlock PIN", color = ThemeCyber.colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, fontFamily = ThemeCyber.fontFamily)
                  Text(
                    text = if (hasPin) "Status: Enabled (Active)" else "Status: Disabled",
                    color = if (hasPin) ThemeCyber.colors.successGreen else ThemeCyber.colors.textSecondary,
                    fontSize = 11.5.sp,
                    fontFamily = ThemeCyber.fontFamily
                  )
                }
              }

              if (!isPinSectionOpen) {
                TextButton(
                  onClick = {
                    isPinSectionOpen = true
                    pinAction = if (hasPin) "CHANGE" else "SETUP"
                    masterPasswordInput = ""
                    newPinInput = ""
                    confirmPinInput = ""
                    statusMessage = null
                  }
                ) {
                  Text(if (hasPin) "Manage" else "Enable", color = ThemeCyber.colors.primary, fontSize = 12.5.sp, fontFamily = ThemeCyber.fontFamily)
                }
              }
            }

            if (isPinSectionOpen) {
              Spacer(modifier = Modifier.height(12.dp))

              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasPin) {
                  OutlinedButton(
                    onClick = { pinAction = "CHANGE"; statusMessage = null },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                      containerColor = if (pinAction == "CHANGE") ThemeCyber.colors.primary.copy(alpha = 0.15f) else Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp)
                  ) {
                    Text("Change PIN", fontSize = 11.sp, fontFamily = ThemeCyber.fontFamily)
                  }
                  OutlinedButton(
                    onClick = { pinAction = "REMOVE"; statusMessage = null },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                      containerColor = if (pinAction == "REMOVE") ThemeCyber.colors.dangerRed.copy(alpha = 0.15f) else Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp)
                  ) {
                    Text("Remove PIN", color = ThemeCyber.colors.dangerRed, fontSize = 11.sp, fontFamily = ThemeCyber.fontFamily)
                  }
                }
              }

              Spacer(modifier = Modifier.height(10.dp))

              OutlinedTextField(
                value = masterPasswordInput,
                onValueChange = { masterPasswordInput = it },
                label = { Text("Master Password (Required to Verify)", color = ThemeCyber.colors.textSecondary, fontSize = 12.sp) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = ThemeCyber.colors.primary,
                  unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
                  focusedTextColor = ThemeCyber.colors.textPrimary,
                  unfocusedTextColor = ThemeCyber.colors.textPrimary,
                ),
                modifier = Modifier.fillMaxWidth()
              )

              if (pinAction != "REMOVE") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                  value = newPinInput,
                  onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) newPinInput = it },
                  label = { Text("New PIN (4-12 digits)", color = ThemeCyber.colors.textSecondary, fontSize = 12.sp) },
                  visualTransformation = PasswordVisualTransformation(),
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                  singleLine = true,
                  shape = RoundedCornerShape(10.dp),
                  colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ThemeCyber.colors.primary,
                    unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
                    focusedTextColor = ThemeCyber.colors.textPrimary,
                    unfocusedTextColor = ThemeCyber.colors.textPrimary,
                  ),
                  modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                  value = confirmPinInput,
                  onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) confirmPinInput = it },
                  label = { Text("Confirm New PIN", color = ThemeCyber.colors.textSecondary, fontSize = 12.sp) },
                  visualTransformation = PasswordVisualTransformation(),
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                  singleLine = true,
                  shape = RoundedCornerShape(10.dp),
                  colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (newPinInput.isNotEmpty() && newPinInput == confirmPinInput) ThemeCyber.colors.successGreen else ThemeCyber.colors.primary,
                    unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
                    focusedTextColor = ThemeCyber.colors.textPrimary,
                    unfocusedTextColor = ThemeCyber.colors.textPrimary,
                  ),
                  modifier = Modifier.fillMaxWidth()
                )
              }

              Spacer(modifier = Modifier.height(12.dp))

              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { isPinSectionOpen = false }) {
                  Text("Cancel", color = ThemeCyber.colors.textSecondary, fontFamily = ThemeCyber.fontFamily)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                  onClick = {
                    if (masterPasswordInput.isNotEmpty() && !isProcessing) {
                      isProcessing = true
                      scope.launch {
                        val isPassValid = repo.verifyMasterCredentialForDestructiveAction(password = masterPasswordInput.toCharArray())
                        if (!isPassValid) {
                          isProcessing = false
                          statusMessage = "Invalid Master Password."
                          isError = true
                          return@launch
                        }

                        if (pinAction == "REMOVE") {
                          val ok = repo.removeMasterPin()
                          isProcessing = false
                          if (ok) {
                            statusMessage = "PIN removed successfully."
                            isError = false
                            isPinSectionOpen = false
                            metadata = repo.getMasterKeyMetadata()
                            onSettingsUpdated()
                          } else {
                            statusMessage = "Failed to remove PIN."
                            isError = true
                          }
                        } else {
                          if (newPinInput.isNotEmpty() && newPinInput == confirmPinInput && PasswordCryptoEngine.isPinValid(newPinInput.toCharArray())) {
                            val res = repo.setupMasterPin(newPinInput.toCharArray())
                            isProcessing = false
                            if (res.isSuccess) {
                              statusMessage = "PIN updated successfully."
                              isError = false
                              isPinSectionOpen = false
                              metadata = repo.getMasterKeyMetadata()
                              onSettingsUpdated()
                            } else {
                              statusMessage = "Failed to set PIN: ${res.exceptionOrNull()?.message}"
                              isError = true
                            }
                          } else {
                            isProcessing = false
                            statusMessage = "PIN must be 4-12 digits and matching."
                            isError = true
                          }
                        }
                      }
                    }
                  },
                  enabled = !isProcessing && masterPasswordInput.isNotEmpty(),
                  colors = ButtonDefaults.buttonColors(
                    containerColor = if (pinAction == "REMOVE") ThemeCyber.colors.dangerRed else ThemeCyber.colors.primary
                  ),
                  shape = RoundedCornerShape(10.dp)
                ) {
                  if (isProcessing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                  } else {
                    Text(if (pinAction == "REMOVE") "Confirm Remove" else "Save PIN", fontFamily = ThemeCyber.fontFamily, fontWeight = FontWeight.Bold)
                  }
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Biometrics Section ---
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = ThemeCyber.colors.surfaceLight,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Fingerprint, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(20.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                Text("Biometric Hardware Key", color = ThemeCyber.colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, fontFamily = ThemeCyber.fontFamily)
                Text(
                  text = if (!isBiometricAvail) "Hardware unavailable" else if (isBiometricEnabled) "Active • Hardware Enclave" else "Disabled",
                  color = if (isBiometricEnabled) ThemeCyber.colors.successGreen else ThemeCyber.colors.textSecondary,
                  fontSize = 11.5.sp,
                  fontFamily = ThemeCyber.fontFamily
                )
              }
            }

            Switch(
              checked = isBiometricEnabled,
              enabled = isBiometricAvail && !isProcessing,
              onCheckedChange = { enable ->
                if (enable) {
                  val activity = context as? FragmentActivity
                  if (activity != null) {
                    scope.launch {
                      val cipherRes = repo.prepareBiometricEncryptCipher()
                      if (cipherRes.isSuccess) {
                        val cipher = cipherRes.getOrThrow()
                        val executor = ContextCompat.getMainExecutor(activity)
                        val prompt = BiometricPrompt(
                          activity,
                          executor,
                          object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                              val cryptoCipher = result.cryptoObject?.cipher
                              if (cryptoCipher != null) {
                                scope.launch {
                                  val ok = repo.enableBiometricsWithCipher(cryptoCipher)
                                  if (ok) {
                                    statusMessage = "Biometrics enabled."
                                    isError = false
                                    metadata = repo.getMasterKeyMetadata()
                                    onSettingsUpdated()
                                  } else {
                                    statusMessage = "Biometric setup failed."
                                    isError = true
                                  }
                                }
                              }
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                              statusMessage = "Biometric auth error: $errString"
                              isError = true
                            }
                          }
                        )

                        val promptInfo = BiometricPrompt.PromptInfo.Builder()
                          .setTitle("Enable Biometric Unlock")
                          .setSubtitle("Authenticate with biometrics to store hardware-wrapped key")
                          .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                          .setNegativeButtonText("Cancel")
                          .build()

                        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                      } else {
                        statusMessage = cipherRes.exceptionOrNull()?.message ?: "Cipher generation error."
                        isError = true
                      }
                    }
                  }
                } else {
                  scope.launch {
                    val ok = repo.disableBiometrics()
                    if (ok) {
                      statusMessage = "Biometrics disabled."
                      isError = false
                      metadata = repo.getMasterKeyMetadata()
                      onSettingsUpdated()
                    }
                  }
                }
              },
              colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ThemeCyber.colors.primary
              )
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Auto-Wipe Section ---
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = ThemeCyber.colors.surfaceLight,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Security, contentDescription = null, tint = ThemeCyber.colors.dangerRed, modifier = Modifier.size(20.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                Text("Auto-Wipe on 10 Attempts", color = ThemeCyber.colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, fontFamily = ThemeCyber.fontFamily)
                Text(
                  text = "Purges vault after 10 failed unlock attempts",
                  color = ThemeCyber.colors.textSecondary,
                  fontSize = 11.5.sp,
                  fontFamily = ThemeCyber.fontFamily
                )
              }
            }

            Switch(
              checked = isAutoWipeEnabled,
              onCheckedChange = { enable ->
                scope.launch {
                  val ok = repo.setAutoWipe(enable)
                  if (ok) {
                    metadata = repo.getMasterKeyMetadata()
                    onSettingsUpdated()
                  }
                }
              },
              colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ThemeCyber.colors.dangerRed
              )
            )
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
          onClick = onDismiss,
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text("Done", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = ThemeCyber.fontFamily)
        }
      }
    }
  }
}

// Biometric Crypto Prompt Helper (Unwraps DEK via Keystore Hardware Key)
private fun showBiometricCryptoPrompt(
  activity: FragmentActivity,
  iv: ByteArray,
  title: String = "Password Vault",
  subtitle: String = "Authenticate with biometrics to unlock vault",
  onAuthenticated: (Cipher) -> Unit,
  onError: (String) -> Unit = {},
) {
  try {
    val key = PasswordCryptoEngine.getOrCreateBiometricKeystoreKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val gcmSpec = GCMParameterSpec(128, iv)
    cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
      activity,
      executor,
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          val cryptoCipher = result.cryptoObject?.cipher
          if (cryptoCipher != null) {
            onAuthenticated(cryptoCipher)
          } else {
            onError("Biometric cryptographic cipher unavailable")
          }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
            onError("Biometric authentication: $errString")
          }
        }

        override fun onAuthenticationFailed() {
          onError("Biometric authentication failed. Please retry.")
        }
      }
    )

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setNegativeButtonText("Use Master Password")
      .build()

    prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
  } catch (e: Exception) {
    onError("Biometric cipher init failed: ${e.message}")
  }
}

// Biometric Generic Verification Prompt Helper
private fun showBiometricAuthPrompt(
  activity: FragmentActivity,
  title: String = "Password Vault",
  subtitle: String = "Authenticate using biometrics",
  negativeButtonText: String = "Cancel",
  onSuccess: () -> Unit,
  onError: (String) -> Unit = {},
) {
  try {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
      activity,
      executor,
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          onSuccess()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
            onError(errString.toString())
          }
        }

        override fun onAuthenticationFailed() {
          onError("Biometric authentication failed")
        }
      }
    )

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setNegativeButtonText(negativeButtonText)
      .build()

    prompt.authenticate(promptInfo)
  } catch (e: Exception) {
    onError("Biometric prompt failed: ${e.message}")
  }
}
