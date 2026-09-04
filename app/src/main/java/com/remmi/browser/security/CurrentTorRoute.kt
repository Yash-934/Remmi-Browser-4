package com.remmi.browser.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class GhostRoutePhase {
  SHIELD,
  STARTING_TOR,
  VERIFYING_TOR,
  APPLYING_GECKO,
  VERIFYING_GECKO,
  READY,
  FAILED,
  ROTATING
}

/**
 * Single source of truth for the active Tor route in Remmi Browser.
 * Prevents hardcoding of SOCKS ports and enforces stale-generation rejection.
 */
data class TorRouteInfo(
  val host: String = "127.0.0.1",
  val socksPort: Int? = null,
  val isGhostActive: Boolean = false,
  val isVerified: Boolean = false,
  val exitIp: String? = null,
  val failoverDirect: Boolean = false,
  val generation: Long = 0L,
  val phase: GhostRoutePhase = GhostRoutePhase.SHIELD,
)

object CurrentTorRoute {
  private val generationSequence = AtomicLong(1L)
  private val _route = MutableStateFlow(
    TorRouteInfo(
      socksPort = null,
      isGhostActive = false,
      generation = 0L,
      phase = GhostRoutePhase.SHIELD
    )
  )
  val route: StateFlow<TorRouteInfo> = _route.asStateFlow()

  val currentSocksPort: Int?
    get() = _route.value.socksPort

  val isGhostActive: Boolean
    get() = _route.value.isGhostActive

  val isVerified: Boolean
    get() = _route.value.isVerified

  val exitIp: String?
    get() = _route.value.exitIp

  val currentGeneration: Long
    get() = _route.value.generation

  val currentPhase: GhostRoutePhase
    get() = _route.value.phase

  val isReady: Boolean
    get() {
      val r = _route.value
      return r.phase == GhostRoutePhase.READY &&
          r.isGhostActive &&
          r.socksPort != null &&
          r.socksPort > 0 &&
          r.isVerified &&
          !r.failoverDirect &&
          r.generation > 0L
    }

  fun markStartingGhost(): Long {
    val generation = generationSequence.incrementAndGet()
    _route.update {
      TorRouteInfo(
        host = "127.0.0.1",
        socksPort = null,
        isGhostActive = true,
        isVerified = false,
        exitIp = null,
        failoverDirect = false,
        generation = generation,
        phase = GhostRoutePhase.STARTING_TOR,
      )
    }
    return generation
  }

  fun markRotatingGhost(): Long {
    val generation = generationSequence.incrementAndGet()
    _route.update { current ->
      current.copy(
        isGhostActive = true,
        isVerified = false,
        phase = GhostRoutePhase.ROTATING,
        generation = generation
      )
    }
    return generation
  }

  fun setPhase(
    phase: GhostRoutePhase,
    generation: Long
  ): Boolean {
    var accepted = false
    _route.update { current ->
      if (generation != current.generation) {
        return@update current
      }
      accepted = true
      current.copy(phase = phase)
    }
    return accepted
  }

  fun updateRoute(
    socksPort: Int?,
    isGhostActive: Boolean,
    isVerified: Boolean = false,
    exitIp: String? = null,
    failoverDirect: Boolean = false,
    generation: Long
  ): Boolean {
    var accepted = false
    _route.update { current ->
      // Never allow a stale transition to overwrite a newer route.
      if (generation < current.generation) {
        return@update current
      }
      accepted = true
      current.copy(
        host = "127.0.0.1",
        socksPort = socksPort,
        isGhostActive = isGhostActive,
        isVerified = isVerified,
        exitIp = exitIp,
        failoverDirect = failoverDirect,
        generation = generation,
        phase = current.phase // Preserves phase; READY must be explicitly set via setPhase or commitReadyRoute
      )
    }
    return accepted
  }

  fun commitReadyRoute(
    socksPort: Int,
    exitIp: String?,
    generation: Long
  ): Boolean {
    var accepted = false
    _route.update { current ->
      if (generation != current.generation || !current.isGhostActive) {
        return@update current
      }
      accepted = true
      current.copy(
        host = "127.0.0.1",
        socksPort = socksPort,
        isGhostActive = true,
        isVerified = true,
        exitIp = exitIp,
        failoverDirect = false,
        generation = generation,
        phase = GhostRoutePhase.READY
      )
    }
    return accepted
  }

  fun clearRoute(generation: Long? = null): Long {
    val targetGen = generation ?: generationSequence.incrementAndGet()
    var resultGen = targetGen
    _route.update { current ->
      if (targetGen < current.generation) {
        resultGen = current.generation
        return@update current
      }
      TorRouteInfo(
        host = "127.0.0.1",
        socksPort = null,
        isGhostActive = false,
        isVerified = false,
        exitIp = null,
        failoverDirect = true,
        generation = targetGen,
        phase = GhostRoutePhase.SHIELD,
      )
    }
    return resultGen
  }

  fun markShieldActive(generation: Long? = null): Long {
    return clearRoute(generation)
  }
}
