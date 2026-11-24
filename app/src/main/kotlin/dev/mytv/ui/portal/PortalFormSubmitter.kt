package dev.mytv.ui.portal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.mytv.domain.model.PortalCredentials
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.usecase.AddPortalUseCase
import dev.mytv.domain.util.PortalLinkParser
import dev.mytv.ui.common.toMessage
import javax.inject.Inject

/**
 * Ajout d'un portail à partir d'un formulaire reçu de l'extérieur (téléphone sur le Wi-Fi, ou PC
 * via le code d'accès) : soit un texte collé (« paste »), soit les champs url / user / pass / name.
 */
class PortalFormSubmitter @Inject constructor(
    private val addPortal: AddPortalUseCase,
    @param:ApplicationContext private val context: Context,
) {
    /** [savedName] non null = portail testé et enregistré. */
    class Outcome(val ok: Boolean, val message: String, val savedName: String? = null)

    suspend fun submit(form: Map<String, String>): Outcome {
        val paste = form["paste"].orEmpty()
        val name: String
        val credentials: PortalCredentials
        if (paste.isNotBlank()) {
            credentials = PortalLinkParser.parse(paste) ?: return Outcome(
                false,
                "Je n'ai trouvé ni lien ni identifiants dans ce texte.\n" +
                    "Il doit contenir l'adresse du serveur, le nom d'utilisateur et le mot de passe.",
            )
            name = ""
        } else {
            credentials = PortalCredentials(form["url"].orEmpty(), form["user"].orEmpty(), form["pass"].orEmpty())
            name = form["name"].orEmpty()
        }
        return when (val r = addPortal(name, credentials)) {
            is AppResult.Success -> Outcome(true, "Portail « ${r.value.name} » ajouté ✓", r.value.name)
            is AppResult.Failure -> Outcome(false, r.error.toMessage(context.resources))
        }
    }

    companion object {
        val FORM_FIELDS = listOf("paste", "url", "user", "pass", "name")
    }
}
