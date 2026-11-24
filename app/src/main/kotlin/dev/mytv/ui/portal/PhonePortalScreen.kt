package dev.mytv.ui.portal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.mytv.lan.MiniHttpServer
import dev.mytv.lan.PortalWebServer
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.theme.MyTvColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

data class PhonePortalUiState(
    val address: String? = null,
    val error: String? = null,
    /** Dernier événement (connexion en cours, erreur du portail...), affiché sur la TV. */
    val status: String? = null,
    val statusIsError: Boolean = false,
    val savedName: String? = null,
)

/**
 * Pilote le serveur local de saisie. Le serveur appelle [submit] sur son propre thread (jamais
 * le thread principal), d'où le runBlocking : une seule saisie à la fois, c'est voulu.
 */
@HiltViewModel
class PhonePortalViewModel @Inject constructor(
    private val submitter: PortalFormSubmitter,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(PhonePortalUiState())
    val state: StateFlow<PhonePortalUiState> = _state.asStateFlow()

    private var server: PortalWebServer? = null
    fun start() {
        if (server != null) return
        val s = PortalWebServer(onSubmit = ::submit, onEvent = { text -> _state.update { it.copy(status = text, statusIsError = true) } })
        val port = s.start()
        val ip = MiniHttpServer.localAddress(context)
        if (port == null || ip == null) {
            s.stop()
            _state.update { it.copy(error = "Impossible d'ouvrir la saisie : la TV doit être connectée au réseau local (Wi-Fi ou Ethernet).") }
            return
        }
        server = s
        _state.update { it.copy(address = "http://$ip:$port/?pin=${s.pin}") }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private fun submit(form: Map<String, String>): Pair<Boolean, String> {
        _state.value.savedName?.let { return true to "Le portail « $it » est déjà ajouté." }
        _state.update { it.copy(status = "Connexion au portail…", statusIsError = false) }
        val outcome = runBlocking { submitter.submit(form) }
        _state.update { it.copy(status = outcome.message, statusIsError = !outcome.ok, savedName = outcome.savedName) }
        return outcome.ok to if (outcome.ok) "${outcome.message}\nLa TV ouvre l'accueil, tu peux poser ton téléphone." else outcome.message
    }

    override fun onCleared() = stop()
}

/**
 * Écran TV : QR code à scanner avec le téléphone, ou adresse à taper. Le portail est testé et
 * enregistré dès que le téléphone l'envoie ; la TV passe alors à l'accueil.
 */
@Composable
fun PhonePortalScreen(
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    viewModel: PhonePortalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cancelFocus = remember { FocusRequester() }
    // La saisie n'existe que pendant que cet écran est affiché.
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    LaunchedEffect(Unit) { cancelFocus.tryRequestFocus() }
    LaunchedEffect(state.savedName) {
        if (state.savedName != null) {
            delay(1_500) // le temps de lire « Portail ajouté ✓ »
            onSaved()
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(MyTvColors.Background)
            .padding(horizontal = 48.dp, vertical = 27.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ajouter depuis un téléphone", style = MaterialTheme.typography.headlineMedium)
            Text("Sur le même Wi-Fi que la TV", style = MaterialTheme.typography.titleMedium, color = MyTvColors.Accent)
            if (state.error != null) {
                Text(state.error!!, color = MyTvColors.Error, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(
                    "Scanne le QR code avec le téléphone, puis colle le lien ou le message du fournisseur. Sans QR code : ${state.address ?: "…"}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "La page charge sans fin ? Le téléphone doit être sur ce Wi-Fi (pas un Wi-Fi « invité »), sans données mobiles ni VPN.",
                    color = MyTvColors.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.statusIsError) MyTvColors.Error else MyTvColors.Success,
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text("Annuler") }
        }
        state.address?.let { QrCode(it, Modifier.size(300.dp)) }
    }
}

/**
 * QR code dessiné en vectoriel, directement sur le canevas : aucune image (Bitmap) n'est créée,
 * le tracé est calculé une seule fois puis réutilisé.
 */
@Composable
private fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix: BitMatrix = remember(content) {
        // Taille 0 : une cellule par module (matrice minimale), la mise à l'échelle se fait au dessin.
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    }
    Box(modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Spacer(
            Modifier
                .fillMaxSize()
                // drawWithCache : le tracé n'est recalculé que si la taille change, pas à chaque image.
                .drawWithCache {
                    val cell = size.minDimension / matrix.width
                    val path = Path()
                    for (y in 0 until matrix.height) {
                        for (x in 0 until matrix.width) {
                            if (matrix[x, y]) path.addRect(Rect(Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f)))
                        }
                    }
                    onDrawBehind { drawPath(path, Color.Black) }
                },
        )
    }
}
