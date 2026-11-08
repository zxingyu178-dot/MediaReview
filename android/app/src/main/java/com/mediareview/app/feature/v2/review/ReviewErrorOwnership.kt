package com.mediareview.app.feature.v2.review

/** GSY error callbacks may be observed after the kernel has already been released. */
internal fun acceptsReviewError(
    currentSource: Boolean,
    ownedAttachedHost: Boolean,
    currentListener: Boolean,
    hostInError: Boolean,
    loadedUrl: String,
    kernelUrl: String?,
): Boolean = currentSource && ownedAttachedHost && currentListener &&
    loadedUrl.isNotEmpty() &&
    (kernelUrl == loadedUrl || (hostInError && kernelUrl == null))
