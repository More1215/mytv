package dev.mytv.data.error

import dev.mytv.data.network.VpnRequiredException
import dev.mytv.data.source.xtream.XtreamAuthException
import dev.mytv.data.source.xtream.XtreamProtocolException
import dev.mytv.domain.result.AppError
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Point unique de traduction exception technique -> erreur métier.
 * Toute la couche data passe par [runCatchingApp] pour que l'UI ne voie jamais d'exception brute.
 */
internal suspend fun <T> runCatchingApp(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: CancellationException) {
    throw e // ne jamais avaler l'annulation des coroutines
} catch (e: Throwable) {
    AppResult.Failure(e.toAppError())
}

internal fun Throwable.toAppError(): AppError = when (this) {
    is VpnRequiredException -> AppError.VpnRequired
    is XtreamAuthException -> AppError.InvalidCredentials
    is XtreamProtocolException -> AppError.InvalidResponse
    is SocketTimeoutException -> AppError.Timeout
    is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLException ->
        AppError.Unreachable
    // Timeout global d'appel OkHttp (callTimeout) : InterruptedIOException("timeout").
    is InterruptedIOException -> AppError.Timeout
    is HttpException -> when (code()) {
        // Beaucoup de panels répondent 401/403 (parfois 512/884) en cas de mauvais identifiants.
        401, 403 -> AppError.InvalidCredentials
        else -> AppError.Server(code())
    }
    // HTML, page de maintenance, JSON tronqué...
    is SerializationException, is IllegalArgumentException -> AppError.InvalidResponse
    else -> AppError.Unknown(message)
}
