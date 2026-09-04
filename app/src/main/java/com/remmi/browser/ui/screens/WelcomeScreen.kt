package com.remmi.browser.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.remmi.browser.R
import com.remmi.browser.util.DefaultBrowserHelper
import com.remmi.browser.ui.components.GlitchText
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun WelcomeScreen(
  onEnterBrowser: () -> Unit,
) {
  val context = LocalContext.current
  val activity = context as? Activity
  val accentColor = ThemeCyber.colors.primary

  var isDefault by remember {
    mutableStateOf(DefaultBrowserHelper.isDefaultBrowser(context))
  }

  val roleLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) {
    isDefault = DefaultBrowserHelper.isDefaultBrowser(context)
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(24.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      // Header & Cyber Branding
      Column {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(bottom = 12.dp)
        ) {
          Image(
            painter = painterResource(id = R.drawable.ic_remmi_panda),
            contentDescription = "Remmi Browser Logo",
            modifier = Modifier
              .size(36.dp)
          )
          Spacer(modifier = Modifier.width(12.dp))
          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(ThemeCyber.colors.successGreen)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "REMMI BROWSER",
                color = ThemeCyber.colors.successGreen,
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
              )
            }
            Text(
              text = "SECURE CYBERSPACE MATRIX",
              color = ThemeCyber.colors.textSecondary,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 9.sp,
            )
          }
        }

        GlitchText(
          text = "ZERO COMPROMISE\nPRIVACY MATRIX",
          fontSize = 24.sp,
          color = accentColor,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "Tor-grade anonymity, dual-tier anti-fingerprinting, and native Brave adblocking combined in a hardened Android client.",
          color = ThemeCyber.colors.textSecondary,
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 12.sp,
          lineHeight = 18.sp,
        )
      }

      // Feature Matrix Cards
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FeatureRow(
          icon = Icons.Default.VpnKey,
          title = "1-Tap Tor Onion Routing",
          desc = "Full 3-hop circuit SOCKS5 remote DNS proxy with fail-closed clearnet leak prevention.",
          color = ThemeCyber.colors.torPurple,
        )

        FeatureRow(
          icon = Icons.Default.Fingerprint,
          title = "Dual-Tier Anti-Fingerprint",
          desc = "FPP + Full RFP. Uniform Tor UA, canvas noise jitter, UTC spoofing & hardware concurrency 2.",
          color = accentColor,
        )

        FeatureRow(
          icon = Icons.Default.Block,
          title = "Native Rust Adblocker",
          desc = "High-speed 110,000+ rule EasyList + EasyPrivacy sub-millisecond request filter.",
          color = ThemeCyber.colors.secondary,
        )

        FeatureRow(
          icon = Icons.Default.Security,
          title = "Zero Trace & Encrypted Storage",
          desc = "WebRTC neutralized, cookies isolated per site, HTTPS-only network guard.",
          color = ThemeCyber.colors.successGreen,
        )
      }

      // Action Buttons (Set as Default Browser + Enter)
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!isDefault && activity != null) {
          OutlinedButton(
            onClick = {
              DefaultBrowserHelper.requestSetDefaultBrowser(activity, roleLauncher)
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(46.dp)
              .testTag("set_default_browser_welcome_button")
          ) {
            Icon(
              Icons.Default.OpenInBrowser,
              contentDescription = null,
              tint = accentColor,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "SET AS DEFAULT BROWSER",
              color = accentColor,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
          }
        } else if (isDefault) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(6.dp))
              .background(ThemeCyber.colors.successContainer)
              .padding(vertical = 8.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
          ) {
            Icon(Icons.Default.Check, null, tint = ThemeCyber.colors.successGreen, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              "REMMI IS YOUR DEFAULT BROWSER",
              color = ThemeCyber.colors.successGreen,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }

        Button(
          onClick = onEnterBrowser,
          colors = ButtonDefaults.buttonColors(
            containerColor = accentColor,
            contentColor = if (ThemeCyber.colors.isLight) Color.White else ThemeCyber.colors.backgroundDarker,
          ),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("enter_browser_button")
        ) {
          Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "ENTER CYBERSPACE",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}

@Composable
private fun FeatureRow(
  icon: ImageVector,
  title: String,
  desc: String,
  color: Color,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(ThemeCyber.colors.surface)
      .border(0.8.dp, color.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
      .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(32.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(color.copy(alpha = 0.15f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(18.dp),
      )
    }

    Spacer(modifier = Modifier.width(12.dp))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        color = ThemeCyber.colors.textPrimary,
        fontFamily = ThemeCyber.fontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = desc,
        color = ThemeCyber.colors.textSecondary,
        fontFamily = ThemeCyber.fontFamily,
        fontSize = 10.sp,
        lineHeight = 14.sp,
      )
    }
  }
}
