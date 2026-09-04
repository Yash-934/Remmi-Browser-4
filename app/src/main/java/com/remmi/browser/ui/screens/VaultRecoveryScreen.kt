package com.remmi.browser.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.ui.theme.ThemeCyber

import kotlin.system.exitProcess

@Composable
fun VaultRecoveryScreen(
  onProceedToWipe: () -> Unit
) {
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
          contentDescription = "Vault Recovery Required",
          tint = ThemeCyber.colors.dangerRed,
          modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = "VAULT UNAVAILABLE",
          fontFamily = ThemeCyber.fontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 16.sp,
          color = ThemeCyber.colors.dangerRed,
          textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Your encrypted vault could not be opened. We could not safely recover it on this device.",
          fontSize = 13.sp,
          color = ThemeCyber.colors.textMuted,
          textAlign = TextAlign.Center,
          lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        val context = androidx.compose.ui.platform.LocalContext.current
        OutlinedButton(
          onClick = { 
            (context as? android.app.Activity)?.recreate() 
          },
          colors = ButtonDefaults.outlinedButtonColors(
            contentColor = ThemeCyber.colors.textPrimary
          ),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
        ) {
          Text(
            text = "TRY AGAIN",
            fontWeight = FontWeight.Bold,
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 13.sp
          )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Button(
          onClick = onProceedToWipe,
          colors = ButtonDefaults.buttonColors(
            containerColor = ThemeCyber.colors.dangerRed,
            contentColor = Color.White
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
        ) {
          Text(
            text = "RESET VAULT",
            fontWeight = FontWeight.Bold,
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 13.sp
          )
        }
      }
    }
  }
}
