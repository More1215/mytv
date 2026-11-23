package dev.mytv.ui.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.mytv.R
import dev.mytv.domain.model.AccountInfo
import dev.mytv.domain.model.AccountStatus
import dev.mytv.domain.result.AppError
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Traduction des erreurs métier en messages lisibles (la seule place où l'UI interprète AppError). */
@Composable
fun AppError.toMessage(): String = toMessage(LocalContext.current.resources)

/** Même traduction hors Compose (ex. réponse envoyée au téléphone par le serveur local). */
fun AppError.toMessage(res: Resources): String = when (this) {
    AppError.InvalidUrl -> res.getString(R.string.error_invalid_url)
    AppError.InvalidCredentials -> res.getString(R.string.error_invalid_credentials)
    is AppError.AccountUnavailable -> res.getString(R.string.error_account_unavailable, status.lowercase())
    AppError.Unreachable -> res.getString(R.string.error_unreachable)
    AppError.VpnRequired -> res.getString(R.string.error_vpn_required)
    AppError.BackupUnreadable -> res.getString(R.string.error_backup_unreadable)
    AppError.Timeout -> res.getString(R.string.error_timeout)
    AppError.InvalidResponse -> res.getString(R.string.error_invalid_response)
    is AppError.Server -> res.getString(R.string.error_server, httpCode)
    is AppError.Unknown -> res.getString(R.string.error_unknown, message ?: "?")
}

fun AccountStatus.label(): String = when (this) {
    AccountStatus.ACTIVE -> "actif"
    AccountStatus.EXPIRED -> "expiré"
    AccountStatus.BANNED -> "banni"
    AccountStatus.DISABLED -> "désactivé"
    AccountStatus.UNKNOWN -> "inconnu"
}

/** Lignes de résumé du compte, réutilisées par l'écran d'ajout et l'accueil. */
@Composable
fun AccountInfo.summaryLines(): List<String> = buildList {
    add(stringResource(R.string.account_status, status.label()))
    add(
        expiresAtEpochSec?.let {
            stringResource(R.string.account_expires, SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date(it * 1000)))
        } ?: stringResource(R.string.account_unlimited),
    )
    if (maxConnections != null) {
        add(stringResource(R.string.account_connections, (activeConnections ?: 0).toString(), maxConnections.toString()))
    }
    if (isTrial) add(stringResource(R.string.account_trial))
}
