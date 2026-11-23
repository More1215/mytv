package dev.mytv.domain.usecase

import dev.mytv.domain.repository.NetworkGuard
import dev.mytv.domain.repository.NetworkProtection
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** État VPN / kill switch, observé par l'UI (écran bloquant, indicateur, arrêt du lecteur). */
class ObserveNetworkProtectionUseCase @Inject constructor(private val guard: NetworkGuard) {
    operator fun invoke(): StateFlow<NetworkProtection> = guard.protection
}
