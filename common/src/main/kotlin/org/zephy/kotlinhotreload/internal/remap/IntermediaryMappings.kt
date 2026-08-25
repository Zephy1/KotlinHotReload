package org.zephy.kotlinhotreload.internal.remap

import net.fabricmc.mappingio.format.tiny.Tiny2FileReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.zephy.kotlinhotreload.internal.remapDescriptorClasses
import java.io.File
import java.nio.file.Files

object MojmapToIntermediary {
    fun compose(officialToObf: ProGuardMappings.Direction, intermediaryTiny: File): ProGuardMappings.Direction {
        val tree = MemoryMappingTree()
        Files.newBufferedReader(intermediaryTiny.toPath()).use { reader ->
            Tiny2FileReader.read(reader, tree)
        }

        require(tree.getNamespaceId("official") != MappingTreeView.NULL_NAMESPACE_ID) {
            "Tiny mappings file ${intermediaryTiny.path} is missing the 'official' namespace."
        }

        val interNs = tree.getNamespaceId("intermediary")
        require(interNs != MappingTreeView.NULL_NAMESPACE_ID) {
            "Tiny mappings file ${intermediaryTiny.path} is missing the 'intermediary' namespace."
        }

        val obfToIntermediaryClass = LinkedHashMap<String, String>()
        val intermediaryMethodNames = LinkedHashMap<ProGuardMappings.Member, String>()
        val intermediaryFieldNames = LinkedHashMap<ProGuardMappings.Member, String>()

        for (cls in tree.classes) {
            val obfOwner = cls.srcName
            val intermediaryOwner = cls.getDstName(interNs) ?: obfOwner
            obfToIntermediaryClass[obfOwner] = intermediaryOwner

            for (method in cls.methods) {
                val srcDesc = method.srcDesc ?: continue
                val srcName = method.srcName
                val dstName = method.getDstName(interNs) ?: srcName
                val dstDesc = method.getDstDesc(interNs) ?: srcDesc
                intermediaryMethodNames[ProGuardMappings.Member(obfOwner, srcName, srcDesc)] = dstName
                intermediaryMethodNames[ProGuardMappings.Member(intermediaryOwner, dstName, dstDesc)] = dstName
            }

            for (field in cls.fields) {
                val srcDesc = field.srcDesc ?: continue
                val srcName = field.srcName
                val dstName = field.getDstName(interNs) ?: srcName
                val dstDesc = field.getDstDesc(interNs) ?: srcDesc
                intermediaryFieldNames[ProGuardMappings.Member(obfOwner, srcName, srcDesc)] = dstName
                intermediaryFieldNames[ProGuardMappings.Member(intermediaryOwner, dstName, dstDesc)] = dstName
            }
        }

        val officialToIntermediaryClass = buildMap {
            officialToObf.classes.forEach { (official, obf) ->
                put(official, obfToIntermediaryClass[obf] ?: obf)
            }

            val alreadyTargeted = values.toHashSet()
            obfToIntermediaryClass.values.forEach { intermediary ->
                if (intermediary !in alreadyTargeted) put(intermediary, intermediary)
            }
        }

        val methods = LinkedHashMap<ProGuardMappings.Member, String>()
        for ((member, obfName) in officialToObf.methods) {
            val obfOwner = officialToObf.classes[member.owner] ?: member.owner
            val obfDescriptor = remapDescriptorClasses(member.descriptor, officialToObf.classes)
            methods[member] = intermediaryMethodNames[ProGuardMappings.Member(obfOwner, obfName, obfDescriptor)] ?: obfName
        }
        for (cls in tree.classes) {
            val interOwner = cls.getDstName(interNs) ?: cls.srcName
            for (method in cls.methods) {
                val srcDesc = method.srcDesc ?: continue
                val interName = method.getDstName(interNs) ?: method.srcName
                val interDesc = method.getDstDesc(interNs) ?: srcDesc
                methods[ProGuardMappings.Member(interOwner, interName, interDesc)] = interName
            }
        }

        val fields = LinkedHashMap<ProGuardMappings.Member, String>()
        for ((member, obfName) in officialToObf.fields) {
            val obfOwner = officialToObf.classes[member.owner] ?: member.owner
            val obfDescriptor = remapDescriptorClasses(member.descriptor, officialToObf.classes)
            fields[member] = intermediaryFieldNames[ProGuardMappings.Member(obfOwner, obfName, obfDescriptor)] ?: obfName
        }
        for (cls in tree.classes) {
            val interOwner = cls.getDstName(interNs) ?: cls.srcName
            for (field in cls.fields) {
                val srcDesc = field.srcDesc ?: continue
                val interName = field.getDstName(interNs) ?: field.srcName
                val interDesc = field.getDstDesc(interNs) ?: srcDesc
                fields[ProGuardMappings.Member(interOwner, interName, interDesc)] = interName
            }
        }

        return ProGuardMappings.Direction(officialToIntermediaryClass, methods, fields)
    }
}
