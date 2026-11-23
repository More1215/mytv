package dev.mytv.ui.navigation

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.ResumePoint
import dev.mytv.domain.model.VodItem
import dev.mytv.domain.repository.NetworkProtection
import dev.mytv.domain.usecase.ObserveActivePortalUseCase
import dev.mytv.domain.usecase.ObserveNetworkProtectionUseCase
import dev.mytv.domain.usecase.UpgradePortalToHttpsUseCase
import dev.mytv.domain.usecase.VodKind
import dev.mytv.player.PlaybackViewModel
import dev.mytv.player.VodPlayerViewModel
import dev.mytv.ui.guide.GuideScreen
import dev.mytv.ui.home.HomeScreen
import dev.mytv.ui.home.HomeSection
import dev.mytv.ui.live.LiveScreen
import dev.mytv.ui.player.PlayerScreen
import dev.mytv.ui.portal.AddPortalScreen
import dev.mytv.ui.privacy.VpnRequiredScreen
import dev.mytv.ui.search.SearchScreen
import dev.mytv.ui.settings.BackupScreen
import dev.mytv.ui.settings.PlayerSettingsScreen
import dev.mytv.ui.settings.PortalsScreen
import dev.mytv.ui.settings.SettingsScreen
import dev.mytv.ui.settings.SettingsSection
import dev.mytv.ui.theme.MyTvColors
import dev.mytv.ui.vod.MovieDetailsScreen
import dev.mytv.ui.vod.SeriesDetailsScreen
import dev.mytv.ui.vod.VodBrowseScreen
import dev.mytv.ui.vod.VodPlayerScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private object Routes {
    const val ADD_PORTAL = "add_portal"
    const val HOME = "home"
    const val LIVE = "live"
    const val PLAYER = "player"
    const val GUIDE = "guide/{categoryId}"
    const val VOD_BROWSE = "vod/{kind}"
    const val MOVIE = "movie/{id}"
    const val SERIES = "series/{id}"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val SETTINGS_PORTALS = "settings/portals"
    const val SETTINGS_PLAYER = "settings/player"
    const val SETTINGS_BACKUP = "settings/backup"
    val VOD_PLAYER = "vodplayer?" + listOf(
        VodPlayerViewModel.ARG_KEY, VodPlayerViewModel.ARG_EXT, VodPlayerViewModel.ARG_TITLE,
        VodPlayerViewModel.ARG_SUBTITLE, VodPlayerViewModel.ARG_IMAGE, VodPlayerViewModel.ARG_SERIES,
        VodPlayerViewModel.ARG_FROM_START,
    ).joinToString("&") { "$it={$it}" }

    /** Écrans où le direct doit jouer (aperçu ou plein écran). Ailleurs, il est coupé. */
    val LIVE_ROUTES = setOf(LIVE, PLAYER, GUIDE)

    fun guide(categoryId: String) = "guide/${Uri.encode(categoryId)}"
    fun vodBrowse(kind: VodKind) = "vod/${if (kind == VodKind.SERIES) "series" else "movies"}"
    fun details(kind: VodKind, id: Long) = if (kind == VodKind.SERIES) "series/$id" else "movie/$id"

    fun vodPlayer(item: VodItem, fromStart: Boolean): String {
        fun enc(v: String?) = Uri.encode(v.orEmpty())
        return "vodplayer?${VodPlayerViewModel.ARG_KEY}=${enc(item.key.storageKey)}" +
            "&${VodPlayerViewModel.ARG_EXT}=${enc(item.containerExtension)}" +
            "&${VodPlayerViewModel.ARG_TITLE}=${enc(item.title)}" +
            "&${VodPlayerViewModel.ARG_SUBTITLE}=${enc(item.subtitle)}" +
            "&${VodPlayerViewModel.ARG_IMAGE}=${enc(item.imageUrl)}" +
            "&${VodPlayerViewModel.ARG_SERIES}=${enc(item.seriesId?.toString())}" +
            "&${VodPlayerViewModel.ARG_FROM_START}=$fromStart"
    }
}

/** Décide de l'écran de départ : formulaire si aucun portail, sinon l'accueil. Lu une seule fois. */
@HiltViewModel
class StartViewModel @Inject constructor(
    observeActivePortal: ObserveActivePortalUseCase,
    upgradeToHttps: UpgradePortalToHttpsUseCase,
    observeProtection: ObserveNetworkProtectionUseCase,
) : ViewModel() {

    val protection: StateFlow<NetworkProtection> = observeProtection()

    /** null tant que la base n'a pas répondu (quelques ms), pour éviter un flash du formulaire. */
    val startRoute: StateFlow<String?> = flow {
        val portal = observeActivePortal().first()
        emit(if (portal == null) Routes.ADD_PORTAL else Routes.HOME)
        // Une fois par lancement, en arrière-plan : si le portail propose désormais HTTPS, on bascule.
        // On attend le VPN : sans lui, le kill switch ferait échouer la tentative.
        if (portal != null) {
            protection.first { !it.blocked }
            upgradeToHttps(portal)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun MyTvNavHost(startViewModel: StartViewModel = hiltViewModel()) {
    val startRoute by startViewModel.startRoute.collectAsStateWithLifecycle()
    val route = startRoute
    if (route == null) {
        Box(Modifier.fillMaxSize().background(MyTvColors.Background))
        return
    }

    // Lecteur Live partagé par tous les écrans : attaché à l'activité, pas à un écran.
    val activity = LocalActivity.current as ComponentActivity
    val playback: PlaybackViewModel = hiltViewModel(activity)

    // Relance de la dernière chaîne : une seule fois par lancement de l'appli.
    var autoStartDone by rememberSaveable { mutableStateOf(false) }

    // Kill switch : sans VPN, seul l'écran « VPN requis » existe. Les écrans de l'appli ne sont
    // même pas composés (aucun focus ne peut s'y égarer, aucune requête ne peut être tentée).
    // Au retour du VPN, la navigation repart de l'accueil.
    val protection by startViewModel.protection.collectAsStateWithLifecycle()
    if (protection.blocked) {
        VpnRequiredScreen()
        return
    }

    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val onLiveScreen = currentRoute in Routes.LIVE_ROUTES

    // Le direct ne joue que sur les écrans Live (aperçu, plein écran, guide) : en allant à
    // l'accueil ou dans les films, il est coupé (son et réseau) et repris au retour.
    LaunchedEffect(onLiveScreen) {
        if (onLiveScreen) playback.onAppStarted() else playback.onAppStopped()
    }
    // Appli en arrière-plan (bouton Home du Fire TV) : on coupe ; au retour, on ne reprend le
    // direct que si l'on était sur un écran Live.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.onAppStopped() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (onLiveScreen) playback.onAppStarted() }

    NavHost(
        navController = navController,
        startDestination = route,
        // Pas d'animation entre écrans : les fondus de la navigation appliquent une transparence
        // que la SurfaceView vidéo ne supporte pas (elle « perce » alors tout l'écran en noir).
        // C'est aussi plus réactif sur le Fire TV Stick.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.ADD_PORTAL) {
            AddPortalScreen(
                // Retour depuis l'accueil quitte l'appli au lieu de revenir au formulaire.
                onPortalSaved = { navController.restartAt(Routes.HOME) },
                onRestore = { navController.navigate(Routes.SETTINGS_BACKUP) },
            )
        }
        composable(Routes.HOME) {
            LaunchedEffect(Unit) {
                if (autoStartDone) return@LaunchedEffect
                autoStartDone = true
                val last = playback.lastWatched() ?: return@LaunchedEffect
                // Accueil -> Live -> plein écran : Retour ramène à la liste des chaînes, puis à l'accueil.
                navController.navigate(Routes.LIVE)
                navController.navigate(Routes.PLAYER)
                playback.play(last.first, last.second)
            }
            HomeScreen(
                onSection = { section ->
                    navController.navigate(
                        when (section) {
                            HomeSection.LIVE -> Routes.LIVE
                            HomeSection.MOVIES -> Routes.vodBrowse(VodKind.MOVIES)
                            HomeSection.SERIES -> Routes.vodBrowse(VodKind.SERIES)
                            HomeSection.SEARCH -> Routes.SEARCH
                        },
                    )
                },
                onResume = { point -> navController.openResume(point) },
                onChangePortal = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.LIVE) {
            LiveScreen(
                playback = playback,
                onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                onOpenGuide = { navController.navigate(Routes.guide(it)) },
                onChangePortal = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.PLAYER) {
            PlayerScreen(playback = playback, onBack = { navController.popBackStack() })
        }
        composable(
            Routes.GUIDE,
            arguments = listOf(navArgument("categoryId") { type = NavType.StringType }),
        ) {
            GuideScreen(
                playback = playback,
                onOpenPlayer = {
                    navController.navigate(Routes.PLAYER) {
                        // Retour depuis le lecteur ramène à la liste, pas au guide.
                        popUpTo(Routes.LIVE)
                    }
                },
            )
        }

        // ---------------------------------------------------------------- VOD
        composable(Routes.VOD_BROWSE, arguments = listOf(navArgument("kind") { type = NavType.StringType })) {
            VodBrowseScreen(
                onOpen = { kind, id -> navController.navigate(Routes.details(kind, id)) },
                onSearch = { navController.navigate(Routes.SEARCH) },
            )
        }
        composable(Routes.MOVIE, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            MovieDetailsScreen(onPlay = { item, fromStart -> navController.navigate(Routes.vodPlayer(item, fromStart)) })
        }
        composable(Routes.SERIES, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            SeriesDetailsScreen(onPlay = { item, fromStart -> navController.navigate(Routes.vodPlayer(item, fromStart)) })
        }
        // ---------------------------------------------------------------- Réglages
        composable(Routes.SETTINGS) {
            SettingsScreen(onSection = { section ->
                navController.navigate(
                    when (section) {
                        SettingsSection.PORTALS -> Routes.SETTINGS_PORTALS
                        SettingsSection.PLAYER -> Routes.SETTINGS_PLAYER
                        SettingsSection.BACKUP -> Routes.SETTINGS_BACKUP
                    },
                )
            })
        }
        composable(Routes.SETTINGS_PORTALS) {
            PortalsScreen(
                // Nouveau portail actif : on repart d'un accueil neuf (tous les écrans rechargés).
                onPortalActivated = { navController.restartAt(Routes.HOME) },
                onAddPortal = { navController.navigate(Routes.ADD_PORTAL) },
                onNoPortalLeft = { navController.restartAt(Routes.ADD_PORTAL) },
            )
        }
        composable(Routes.SETTINGS_PLAYER) { PlayerSettingsScreen() }
        composable(Routes.SETTINGS_BACKUP) {
            // Après une restauration, l'accueil est recréé pour relire portails, favoris et reprises ;
            // sinon simple retour (ex. installation neuve : on revient au formulaire de portail).
            BackupScreen(onDone = { restored ->
                if (restored) navController.restartAt(Routes.HOME) else navController.popBackStack()
            })
        }
        composable(Routes.SEARCH) {
            SearchScreen(onOpen = { kind, id -> navController.navigate(Routes.details(kind, id)) })
        }
        composable(
            Routes.VOD_PLAYER,
            arguments = listOf(
                VodPlayerViewModel.ARG_KEY, VodPlayerViewModel.ARG_EXT, VodPlayerViewModel.ARG_TITLE,
                VodPlayerViewModel.ARG_SUBTITLE, VodPlayerViewModel.ARG_IMAGE, VodPlayerViewModel.ARG_SERIES,
                VodPlayerViewModel.ARG_FROM_START,
            ).map { name -> navArgument(name) { type = NavType.StringType; defaultValue = "" } },
        ) {
            VodPlayerScreen(onBack = { navController.popBackStack() })
        }
    }
}

/** Vide toute la pile de navigation et repart de [route] (changement de portail, restauration). */
private fun NavHostController.restartAt(route: String) {
    navigate(route) { popUpTo(graph.id) { inclusive = true } }
}

/** « Continuer à regarder » : ouvre la fiche (film, ou série pour un épisode) avec son bouton Reprendre. */
private fun NavHostController.openResume(point: ResumePoint) {
    val id = point.key.substringAfter(':').toLongOrNull() ?: return
    val seriesId = point.seriesId
    when {
        point.key.startsWith("episode:") && seriesId != null -> navigate(Routes.details(VodKind.SERIES, seriesId))
        point.key.startsWith("movie:") -> navigate(Routes.details(VodKind.MOVIES, id))
    }
}
