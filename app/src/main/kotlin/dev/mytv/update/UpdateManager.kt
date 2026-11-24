package dev.mytv.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.mytv.BuildConfig
import dev.mytv.domain.repository.AppUpdate
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.usecase.CheckForUpdateUseCase
import dev.mytv.domain.usecase.DownloadUpdateUseCase
import dev.mytv.ui.common.toMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState
    /** APK téléchargé : l'installateur Android a été (ou doit être) ouvert. */
    data class Ready(val update: AppUpdate, val path: String) : UpdateState
    /**
     * APK téléchargé, mais Android exige d'abord d'autoriser MyTV à installer des applis : on
     * l'explique AVANT d'ouvrir le réglage (sinon le client tombe sur un menu sans savoir quoi faire).
     */
    data class NeedsPermission(val update: AppUpdate, val path: String) : UpdateState
    /** [update] non nul : l'erreur concerne le téléchargement/l'installation (on peut réessayer). */
    data class Failed(val message: String, val update: AppUpdate?) : UpdateState
}

/**
 * Vérifie, télécharge et installe les nouvelles versions publiées sur GitHub.
 * Partagé par l'accueil (bandeau) et les réglages. Vérification automatique (release seulement)
 * à chaque retour sur l'accueil, au plus une fois toutes les [AUTO_CHECK_INTERVAL_MS] : un Fire TV
 * ne quitte jamais l'appli, une vérification au seul lancement ne voyait jamais les nouvelles versions.
 */
@Singleton
class UpdateManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val check: CheckForUpdateUseCase,
    private val download: DownloadUpdateUseCase,
    @param:Named("appScope") private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME

    private var lastAutoCheck = 0L
    private var downloadedPath: String? = null
    private var job: Job? = null

    /**
     * Vérification silencieuse (rien n'est affiché si l'appli est à jour ou hors ligne). Appelée à
     * chaque affichage de l'accueil ; une simple requête HTTP, au plus toutes les 6 h.
     */
    fun autoCheck() {
        if (BuildConfig.DEBUG) return
        // Une mise à jour est déjà proposée ou en cours : on n'y touche pas.
        when (val s = _state.value) {
            is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Ready, is UpdateState.NeedsPermission -> return
            is UpdateState.Failed -> if (s.update != null) return
            else -> Unit
        }
        val now = SystemClock.elapsedRealtime()
        if (lastAutoCheck != 0L && now - lastAutoCheck < AUTO_CHECK_INTERVAL_MS) return
        lastAutoCheck = now
        checkNow()
    }

    fun checkNow() {
        if (job?.isActive == true) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = when (val r = check(currentVersion)) {
                is AppResult.Success -> r.value?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
                is AppResult.Failure -> UpdateState.Failed("Vérification impossible : ${r.error.toMessage(context.resources)}", null)
            }
        }
    }

    /** Bouton « Installer » : télécharge si besoin, puis ouvre l'installateur Android. */
    fun install() {
        when (val s = _state.value) {
            is UpdateState.Ready -> openInstaller(s.update, s.path)
            is UpdateState.NeedsPermission ->
                if (canInstall()) openInstaller(s.update, s.path)
                else if (!start(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))) {
                    _state.value = UpdateState.Failed(PERMISSION_HELP, s.update)
                }
            is UpdateState.Available -> startDownload(s.update)
            is UpdateState.Failed -> {
                val update = s.update ?: return checkNow()
                // Fichier déjà téléchargé (ex. on attendait l'autorisation d'installer) : réutilisé.
                val path = downloadedPath?.takeIf { File(it).exists() && it.endsWith("mytv-${update.version}.apk") }
                if (path != null) openInstaller(update, path) else startDownload(update)
            }
            else -> Unit
        }
    }

    private fun startDownload(update: AppUpdate) {
        if (job?.isActive == true) return
        _state.value = UpdateState.Downloading(update, 0f)
        job = scope.launch {
            var last = -1
            val r = download(update) { p ->
                // Mise à jour de l'écran par paliers de 1 % seulement (pas à chaque bloc lu).
                val percent = (p * 100).toInt()
                if (percent != last) {
                    last = percent
                    _state.value = UpdateState.Downloading(update, p)
                }
            }
            when (r) {
                is AppResult.Success -> {
                    downloadedPath = r.value
                    _state.value = UpdateState.Ready(update, r.value)
                    openInstaller(update, r.value)
                }
                is AppResult.Failure -> _state.value =
                    UpdateState.Failed("Téléchargement impossible : ${r.error.toMessage(context.resources)}", update)
            }
        }
    }

    private fun openInstaller(update: AppUpdate, path: String) {
        // Android 8+ : l'utilisateur doit autoriser MyTV à installer des applis (une seule fois).
        if (!canInstall()) {
            // Le fichier est gardé : une fois l'autorisation donnée, il est réutilisé sans retélécharger.
            _state.value = UpdateState.NeedsPermission(update, path)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", File(path))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!start(intent)) {
            _state.value = UpdateState.Failed("Impossible d'ouvrir l'installateur. Utilise Downloader (code 6651995).", update)
        }
    }

    private fun canInstall() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /**
     * Retour dans l'appli (onResume) : si le client vient d'autoriser MyTV dans le réglage,
     * l'installation se lance toute seule, sans qu'il ait à rappuyer sur « Installer ».
     */
    fun onAppResumed(activity: Activity) {
        this.activity = java.lang.ref.WeakReference(activity)
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        if (canInstall()) openInstaller(s.update, s.path)
    }

    /** Dernière activité affichée : le réglage s'ouvre dans la pile de MyTV, Retour y ramène. */
    private var activity: java.lang.ref.WeakReference<Activity>? = null

    private fun start(intent: Intent): Boolean = try {
        val a = activity?.get()?.takeUnless { it.isFinishing }
        if (a != null) a.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val PERMISSION_HELP =
            "Autorise MyTV : Paramètres → Ma Fire TV → Options pour les développeurs → Installer des applis inconnues → MyTV. " +
                "Ou utilise Downloader (code 6651995)."
    }
}
