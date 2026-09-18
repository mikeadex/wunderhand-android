package com.wunderhand.core

/**
 * Something a screen is waiting for.
 *
 * Loading is drawn as a skeleton in the shape of what is coming, never a
 * spinner, and a failure is a plain sentence — the web's rules, kept here.
 */
sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>

    val valueOrNull: T? get() = (this as? Loaded<T>)?.value
}
