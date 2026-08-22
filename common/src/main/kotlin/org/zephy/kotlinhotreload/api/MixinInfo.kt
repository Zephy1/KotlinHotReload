package org.zephy.kotlinhotreload.api

import org.zephy.kotlinhotreload.internal.MixinLoadState
import org.zephy.kotlinhotreload.internal.MixinStaging
import org.zephy.kotlinhotreload.internal.MixinStatus

object MixinInfo {
    fun statusesFor(projectName: String, declaredMixinClassNames: List<String>): List<MixinStatus> =
        MixinStaging.statusFor(projectName, declaredMixinClassNames)

    fun statusOf(projectName: String, mixinClassName: String): MixinStatus =
        MixinStaging.statusOf(projectName, mixinClassName)

    fun allLoaded(projectName: String, declaredMixinClassNames: List<String>): Boolean =
        statusesFor(projectName, declaredMixinClassNames).all {
            it.state == MixinLoadState.LOADED
        }
}
