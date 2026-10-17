package info.socrtwo.quillpost.web

import info.socrtwo.quillpost.web.spam.BlacklistChecker
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class AuthException(message: String = "Not signed in") : RuntimeException(message)

/**
 * Bearer-token registry. One [AccountContext] (and therefore one IMAP connection, one Bayes
 * model, one analysis cache) is shared by every browser tab signed in to the same account.
 */
class Sessions(private val store: DataStore, private val blacklists: BlacklistChecker) {
    private val tokens = ConcurrentHashMap<String, String>()          // token -> email key
    private val contexts = ConcurrentHashMap<String, AccountContext>() // email key -> context
    private val random = SecureRandom()
    private val idleMs = 24 * 3600_000L

    private fun keyOf(a: AccountDto) = a.email.trim().lowercase() + "|" + a.incomingHost.lowercase() + "|" + a.username.lowercase()

    @Synchronized
    fun open(account: AccountDto): Pair<String, AccountContext> {
        cleanup()
        val k = keyOf(account)
        val existing = contexts[k]
        val ctx = if (existing != null && existing.account.password == account.password && existing.account.incomingPort == account.incomingPort) existing
        else {
            existing?.close()
            AccountContext(account, store, blacklists).also { contexts[k] = it }
        }
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tokens[token] = k
        return token to ctx
    }

    fun get(token: String?): AccountContext {
        val k = token?.let { tokens[it] } ?: throw AuthException()
        return contexts[k] ?: throw AuthException("Session expired; sign in again")
    }

    @Synchronized
    fun close(token: String?) {
        val k = token?.let { tokens.remove(it) } ?: return
        if (tokens.values.none { it == k }) contexts.remove(k)?.close()
    }

    @Synchronized
    fun cleanup() {
        val now = System.currentTimeMillis()
        for ((k, ctx) in contexts.entries.toList()) {
            if (now - ctx.lastUsed > idleMs) {
                contexts.remove(k)?.close()
                tokens.entries.removeIf { it.value == k }
            }
        }
    }
}
