package com.remmi.browser.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.security.PanicWipeManager
import com.remmi.browser.security.PanicWipeState
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.launch

@Composable
fun EmergencyWipeRecoveryScreen(
  onRecoveryComplete: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val wipeState by PanicWipeManager.state.collectAsState()

  // Automatically trigger resume on launch
  LaunchedEffect(Unit) {
    PanicWipeManager.checkAndResumePendingWipe(context)
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .systemBarsPadding()
      .padding(20.dp),
    contentAlignment = Alignment.Center
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .wrapContentHeight(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      border = BorderStroke(1.5.dp, ThemeCyber.colors.dangerRed)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Icon(
          imageVector = Icons.Default.Warning,
          contentDescription = "Emergency Panic Recovery",
          tint = ThemeCyber.colors.dangerRed,
          modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = "EMERGENCY SANITIZATION RECOVERY",
          fontFamily = ThemeCyber.fontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 16.sp,
          color = ThemeCyber.colors.dangerRed,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "An interrupted panic wipe was detected on startup. Normal browser initialization and tab restoration are strictly blocked until zero-trace logical sanitization completes.",
          fontSize = 13.sp,
          color = ThemeCyber.colors.textMuted,
          textAlign = TextAlign.Center,
          lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        when (val state = wipeState) {
          is PanicWipeState.InProgress -> {
            LinearProgressIndicator(
              progress = { state.progress },
              color = ThemeCyber.colors.dangerRed,
              trackColor = ThemeCyber.colors.surfaceBorder,
              modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
              text = state.phaseDescription,
              color = ThemeCyber.colors.textPrimary,
              fontSize = 13.sp,
              fontFamily = ThemeCyber.fontFamily,
              textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
              text = "${(state.progress * 100).toInt()}%",
              color = ThemeCyber.colors.dangerRed,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Live Telemetry Breakdown
            if (state.verifiedSteps.isNotEmpty()) {
              LazyColumn(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(max = 160.dp)
                  .background(ThemeCyber.colors.surfaceLight, RoundedCornerShape(8.dp))
                  .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                items(state.verifiedSteps) { step ->
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Icon(
                      imageVector = if (step.isSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel,
                      contentDescription = null,
                      tint = if (step.isSuccess) ThemeCyber.colors.successGreen else ThemeCyber.colors.dangerRed,
                      modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text = step.details,
                      fontSize = 11.sp,
                      color = ThemeCyber.colors.textPrimary,
                      fontFamily = ThemeCyber.fontFamily
                    )
                  }
                }
              }
            }
          }

          is PanicWipeState.Completed -> {
            Icon(
              imageVector = Icons.Default.CheckCircle,
              contentDescription = "Success",
              tint = ThemeCyber.colors.successGreen,
              modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
              text = "Cryptographic destruction & logical sanitization verified.",
              color = ThemeCyber.colors.successGreen,
              fontWeight = FontWeight.SemiBold,
              fontSize = 13.sp,
              textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
              onClick = {
                PanicWipeManager.clearWipeMarker(context)
                onRecoveryComplete()
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = ThemeCyber.colors.successGreen,
                contentColor = Color.Black
              ),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
            ) {
              Text(
                text = "CONTINUE TO SECURE BROWSER",
                fontWeight = FontWeight.Bold,
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 13.sp
              )
            }
          }

          is PanicWipeState.Failed -> {
            Icon(
              imageVector = Icons.Default.Error,
              contentDescription = "Failed",
              tint = ThemeCyber.colors.dangerRed,
              modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
              text = state.error,
              color = ThemeCyber.colors.dangerRed,
              fontWeight = FontWeight.SemiBold,
              fontSize = 13.sp,
              textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
              text = "You can retry sanitization or force reset the recovery marker to proceed to the browser.",
              color = ThemeCyber.colors.textMuted,
              fontSize = 12.sp,
              textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Button(
                onClick = {
                  scope.launch {
                    PanicWipeManager.executeWipe(context, wipeVault = false)
                  }
                },
                colors = ButtonDefaults.buttonColors(
                  containerColor = ThemeCyber.colors.dangerRed,
                  contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(46.dp)
              ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "RETRY SANITIZATION",
                  fontWeight = FontWeight.Bold,
                  fontFamily = ThemeCyber.fontFamily,
                  fontSize = 13.sp
                )
              }

              OutlinedButton(
                onClick = {
                  PanicWipeManager.clearWipeMarker(context)
                  onRecoveryComplete()
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(44.dp)
              ) {
                Text(
                  text = "RESET & PROCEED TO BROWSER",
                  color = ThemeCyber.colors.textPrimary,
                  fontWeight = FontWeight.SemiBold,
                  fontFamily = ThemeCyber.fontFamily,
                  fontSize = 12.sp
                )
              }
            }
          }

          else -> {
            CircularProgressIndicator(
              color = ThemeCyber.colors.dangerRed,
              modifier = Modifier.size(36.dp),
              strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = "Initializing recovery protocol...",
              fontSize = 12.sp,
              color = ThemeCyber.colors.textMuted
            )
          }
        }
      }
    }
  }
}
