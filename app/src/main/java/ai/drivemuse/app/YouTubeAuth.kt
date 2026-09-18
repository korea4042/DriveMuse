package ai.drivemuse.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ai.drivemuse.domain.Policy
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Raised when a call needs the user but the app only holds public credentials. */
class UserAuthRequiredException(message: String = "Google 계정 연결이 필요합니다") : IllegalStateException(message)

sealed interface AuthState {
    data class Authorized(val token: String) : AuthState
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthState
    data object Unavailable : AuthState
}

/**
 * Technical design v1.2 §6.4. An Android OAuth client has no client secret, so the whole
 * exchange happens on the device and DriveMuse never operates a server.
 *
 * Access tokens live for about an hour and are deliberately kept in memory only: persisting
 * them buys nothing because Play Services re-issues silently, and it would create a secret
 * on disk that connection teardown has to chase.
 */
class YouTubeAuth(private val context: Context) {

    private val scope = Scope(Policy.SCOPE_READONLY)

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    /**
     * Silent when a prior grant is still valid. Returns NeedsConsent instead of showing UI so
     * the caller decides — §8.5 forbids consent screens while the car session is active.
     */
    suspend fun authorize(): AuthState {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(scope)).build()
        val result = runCatching { Identity.getAuthorizationClient(context).authorize(request).awaitResult() }
            .getOrElse { return AuthState.Unavailable }
        return resolve(result)
    }

    /** Continuation after the consent PendingIntent returns. */
    fun fromIntent(data: Intent?): AuthState {
        val intent = data ?: return AuthState.Unavailable
        val result = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent) }
            .getOrElse { return AuthState.Unavailable }
        return resolve(result)
    }

    private fun resolve(result: AuthorizationResult): AuthState {
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) return AuthState.NeedsConsent(pending)
        val token = result.accessToken
        return if (token.isNullOrBlank()) AuthState.Unavailable else AuthState.Authorized(token)
    }
}

/** In-memory cache so a drive session does not re-authorize on every request. */
class TokenStore {
    @Volatile private var token: String? = null
    @Volatile private var issuedAt = 0L

    fun current(): String? {
        val value = token ?: return null
        // Refresh well before the nominal hour so a long tunnel does not strand a request.
        return if (System.currentTimeMillis() - issuedAt > 45 * 60 * 1000L) { clear(); null } else value
    }

    fun put(value: String) { token = value; issuedAt = System.currentTimeMillis() }
    fun clear() { token = null; issuedAt = 0 }
}

/**
 * Local teardown only. Play Services holds the grant itself, so a full revoke happens in the
 * Google account's third-party access settings; the UI says so rather than implying otherwise.
 */
fun revocationHelpUri(): String = "https://myaccount.google.com/permissions"
