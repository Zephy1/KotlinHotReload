package org.zephy.kotlinhotreload.internal.remap

import net.fabricmc.tinyremapper.IMappingProvider

object TinyMappingAdapter {
    fun toMappingProvider(direction: ProGuardMappings.Direction): IMappingProvider =
        IMappingProvider { acceptor ->
            direction.classes.forEach { (src, dst) -> acceptor.acceptClass(src, dst) }
            direction.methods.forEach { (member, dst) ->
                acceptor.acceptMethod(IMappingProvider.Member(member.owner, member.name, member.descriptor), dst)
            }
            direction.fields.forEach { (member, dst) ->
                acceptor.acceptField(IMappingProvider.Member(member.owner, member.name, member.descriptor), dst)
            }
        }
}
