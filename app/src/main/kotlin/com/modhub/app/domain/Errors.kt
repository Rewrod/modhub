package com.modhub.app.domain

import com.modhub.core.install.ModAlreadyInstalledException
import com.modhub.core.source.SourceAuthException
import com.modhub.core.storage.StorageException
import com.modhub.nexus.NexusNxmLinkRejectedException
import com.modhub.nexus.NexusPremiumRequiredException
import com.modhub.nexus.RateLimitedException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Kinds of error the UI reacts to differently (e.g. offering "Open on Nexus"). */
enum class ErrorKind { PREMIUM_REQUIRED, AUTH, RATE_LIMIT, NETWORK, STORAGE, OTHER }

fun Throwable.kind(): ErrorKind = when (this) {
    is NexusPremiumRequiredException -> ErrorKind.PREMIUM_REQUIRED
    is SourceAuthException, is NexusNxmLinkRejectedException -> ErrorKind.AUTH
    is RateLimitedException -> ErrorKind.RATE_LIMIT
    is UnknownHostException, is SocketTimeoutException -> ErrorKind.NETWORK
    is StorageException, is SecurityException -> ErrorKind.STORAGE
    else -> cause?.takeIf { it !== this }?.kind() ?: ErrorKind.OTHER
}

/** One readable sentence (or two) for a snackbar or dialog. */
fun describe(e: Throwable): String = when (e) {
    is NexusPremiumRequiredException -> NexusPremiumRequiredException.PREMIUM_REQUIRED_MESSAGE
    is UserFacingException -> e.message.orEmpty()
    is UnknownHostException -> "No internet connection (could not reach ${e.message ?: "the server"})."
    is SocketTimeoutException -> "The server took too long to answer. Try again."
    is ModAlreadyInstalledException -> "Already installed at ${e.path}."
    is SecurityException -> "Android denied access: ${e.message ?: "permission missing"}. Check storage access for this game."
    else -> e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
}
