package com.wunderhand.network

/**
 * The second step of signing in, for a login that has two-step sign-in on.
 *
 * Turning it on and off lives on the web's account screen. The app only ever
 * asks for the code.
 */

/** What a right password led to. */
sealed interface SignIn {
    /** Signed in. The token is stored; what matters next is `me()`. */
    data object SignedIn : SignIn

    /** Two-step sign-in is on for this login, so the password alone is not a
     *  session yet. The code goes back through `signIn(code, from, kind)`,
     *  carrying this. */
    data class NeedsCode(val pending: Pending) : SignIn
}

/**
 * A sign-in that has had its password and wants a code.
 *
 * Holds whatever cookie pairs the password step set, to be handed straight
 * back — the server names that cookie differently by scheme
 * (`better-auth.two_factor`, or `__Secure-`-prefixed over https), so nothing
 * here reads it or rebuilds it. Carried by hand for this one exchange: the
 * client keeps no cookie jar, on purpose, and this is let go of as soon as
 * the code is answered.
 *
 * Only the networking layer can make one, so there is no way to ask for a
 * code without an answer from the password step.
 */
@ConsistentCopyVisibility
data class Pending internal constructor(
    internal val cookie: String,
    /** What this login can be asked for. `["totp"]` today. */
    val methods: List<String>,
) {
    companion object {
        /** For previews and for tests above this module, which have no server
         *  to get one from. Only an answer from the password step makes a
         *  `Pending` that a server will finish a sign-in for. */
        fun forPreview(cookie: String = "better-auth.two_factor=preview"): Pending =
            Pending(cookie, listOf("totp"))
    }
}

/** Which kind of code is being sent, and so which endpoint takes it. */
enum class TwoFactorCode {
    /** The six digits the authenticator app shows, good for 30 seconds. */
    Authenticator,

    /** One of the ten codes saved when two-step was turned on. Each works once. */
    Backup,
    ;

    internal val path: String
        get() = when (this) {
            Authenticator -> "api/auth/two-factor/verify-totp"
            Backup -> "api/auth/two-factor/verify-backup-code"
        }

    /** What the web does to a typed code before it asks — `tidyCode` in
     *  lib/auth/two-step.ts. An authenticator shows "123 456" and people type
     *  the space; a backup code has hyphens that belong to it. */
    internal fun tidied(raw: String): String = when (this) {
        Authenticator -> raw.filterNot { it.isWhitespace() || it == '-' }
        Backup -> raw.trim()
    }
}

/**
 * Why the code step refused, and so where it leaves the person. A wrong code
 * is worth another go on the same screen; a sign-in that is spent or has
 * lapsed has to begin again at the password.
 *
 * The web decides the same four ways in `signInWithCode` (lib/actions/auth.ts),
 * and this says it in the same words.
 */
enum class TwoFactorRefusal {
    /** Wrong code, or a backup code that is not one of theirs. The next code
     *  the authenticator shows may well work. */
    WrongCode,

    /** Ten wrong in a row: the code step is paused on this login. */
    Locked,

    /** Five wrong against this one sign-in, or its ten minutes lapsed.
     *  Nothing more can be done with it — back to the password. */
    StartAgain,

    /** Three requests in ten seconds is the server's limit on these two
     *  endpoints. Nothing is wrong with the code; it came too quickly. */
    TooFast,
}
