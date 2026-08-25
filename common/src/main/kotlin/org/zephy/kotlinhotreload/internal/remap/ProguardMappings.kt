package org.zephy.kotlinhotreload.internal.remap

import net.fabricmc.mappingio.format.proguard.ProGuardFileReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.zephy.kotlinhotreload.internal.remapDescriptorClasses
import java.io.File
import java.nio.file.Files

class ProGuardMappings private constructor(
    val officialToObf: Direction,
    val obfToOfficial: Direction,
) {
    data class Member(val owner: String, val name: String, val descriptor: String)

    data class Direction(
        val classes: Map<String, String>,
        val methods: Map<Member, String>,
        val fields: Map<Member, String>,
    )

    companion object {
        private const val NS_OFFICIAL = "official"
        private const val NS_OBF = "obf"

        fun parse(mappingFile: File): ProGuardMappings {
            val tree = MemoryMappingTree()
            Files.newBufferedReader(mappingFile.toPath()).use { reader ->
                ProGuardFileReader.read(reader, NS_OFFICIAL, NS_OBF, tree)
            }

            val obfNs = tree.getNamespaceId(NS_OBF)
            require(obfNs != MappingTreeView.NULL_NAMESPACE_ID) {
                "Couldn't register '$NS_OBF' namespace while reading ${mappingFile.path}."
            }

            val classes = LinkedHashMap<String, String>()
            val methodsOfficialToObf = LinkedHashMap<Member, String>()
            val methodsObfToOfficial = LinkedHashMap<Member, String>()
            val fieldsOfficialToObf = LinkedHashMap<Member, String>()
            val fieldsObfToOfficial = LinkedHashMap<Member, String>()

            for (cls in tree.classes) {
                val official = cls.srcName
                val obf = cls.getDstName(obfNs) ?: official
                classes[official] = obf

                for (m in cls.methods) {
                    val officialDesc = m.srcDesc ?: continue
                    val obfName = m.getDstName(obfNs) ?: m.srcName
                    val obfDesc = m.getDstDesc(obfNs) ?: officialDesc
                    methodsOfficialToObf[Member(official, m.srcName, officialDesc)] = obfName
                    methodsObfToOfficial[Member(obf, obfName, obfDesc)] = m.srcName
                }

                for (f in cls.fields) {
                    val officialDesc = f.srcDesc ?: continue
                    val obfName = f.getDstName(obfNs) ?: f.srcName
                    val obfDesc = f.getDstDesc(obfNs) ?: officialDesc
                    fieldsOfficialToObf[Member(official, f.srcName, officialDesc)] = obfName
                    fieldsObfToOfficial[Member(obf, obfName, obfDesc)] = f.srcName
                }
            }

            return ProGuardMappings(
                officialToObf = Direction(classes, methodsOfficialToObf, fieldsOfficialToObf),
                obfToOfficial = Direction(
                    classes.entries.associate { (official, obf) -> obf to official },
                    methodsObfToOfficial,
                    fieldsObfToOfficial,
                ),
            )
        }
    }
}

fun ProGuardMappings.Direction.inverted(): ProGuardMappings.Direction {
    val invertedClasses = classes.entries.associate { (src, dst) -> dst to src }

    fun invertMembers(members: Map<ProGuardMappings.Member, String>): LinkedHashMap<ProGuardMappings.Member, String> {
        val result = LinkedHashMap<ProGuardMappings.Member, String>()
        for ((member, dstName) in members) {
            val dstOwner = classes[member.owner] ?: member.owner
            val dstDescriptor = remapDescriptorClasses(member.descriptor, classes)
            result[ProGuardMappings.Member(dstOwner, dstName, dstDescriptor)] = member.name
        }
        return result
    }

    return ProGuardMappings.Direction(
        classes = invertedClasses,
        methods = invertMembers(methods),
        fields = invertMembers(fields),
    )
}
