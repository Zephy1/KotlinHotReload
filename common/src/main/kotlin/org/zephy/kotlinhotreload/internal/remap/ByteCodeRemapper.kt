package org.zephy.kotlinhotreload.internal.remap

import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.zephy.kotlinhotreload.internal.MIXIN_ANNOTATION_DESC
import org.zephy.kotlinhotreload.internal.remapDescriptorClasses
import java.io.File
import java.nio.file.Files

object BytecodeRemapper {
    private const val MIXIN_AT_ANNOTATION_DESC = "Lorg/spongepowered/asm/mixin/injection/At;"
    private val INJECTION_ANNOTATIONS = setOf(
        "Lorg/spongepowered/asm/mixin/injection/Inject;",
        "Lorg/spongepowered/asm/mixin/injection/Redirect;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyExpressionValue;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lorg/spongepowered/asm/mixin/gen/Accessor;",
        "Lorg/spongepowered/asm/mixin/gen/Invoker;",
    )
    private val MIXIN_OWNER_REGEX = Regex("^L([^;]+);")
    private val MIXIN_METHOD_REGEX = Regex("^L[^;]+;([A-Za-z0-9_$<>]+)\\((.*)\\)(.+)$")

    fun remapDirectory(
        inputDir: File,
        outputDir: File,
        mapping: IMappingProvider,
        classpathJars: List<File>,
    ) {
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()

        val remapper = TinyRemapper.newRemapper()
            .withMappings(mapping)
            .extension(MixinExtension())
            .build()
        try {
            classpathJars.forEach { remapper.readClassPathAsync(it.toPath()) }
            val inputTag = remapper.createInputTag()
            remapper.readInputsAsync(inputTag, inputDir.toPath())

            OutputConsumerPath.Builder(outputDir.toPath()).build().use { consumer ->
                remapper.apply(consumer, inputTag)
            }
        } finally {
            remapper.finish()
        }
    }

    fun remapMixinStringAnnotations(inputDir: File, mapping: ProGuardMappings.Direction) {
        if (!inputDir.isDirectory) return
        val index = MappingIndex(mapping)

        inputDir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { classFile ->
            val pathLocal = classFile.absolutePath
            val normalized = pathLocal.replace('\\', '/').lowercase()
            if (!normalized.contains("/mixins/")) return@forEach
            val reader = ClassReader(classFile.readBytes())
            val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
            var currentOwner: String? = null
            var mixinTargetOwners: MutableSet<String> = linkedSetOf()

            reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    currentOwner = name
                    mixinTargetOwners = linkedSetOf()
                    super.visit(version, access, name, signature, superName, interfaces)
                }

                override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                    val delegate = super.visitAnnotation(descriptor, visible) ?: return null
                    return remapAnnotation(
                        descriptor = descriptor,
                        delegate = delegate,
                        index = index,
                        currentOwner = currentOwner,
                        mixinTargetOwners = mixinTargetOwners,
                    ) ?: delegate
                }

                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                    return object : MethodVisitor(Opcodes.ASM9, mv) {
                        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                            val delegate = super.visitAnnotation(descriptor, visible) ?: return null
                            return remapAnnotation(
                                descriptor = descriptor,
                                delegate = delegate,
                                index = index,
                                currentOwner = currentOwner,
                                mixinTargetOwners = mixinTargetOwners,
                            ) ?: delegate
                        }
                    }
                }
            }, ClassReader.SKIP_FRAMES)

            classFile.writeBytes(writer.toByteArray())
        }
    }

    private fun remapAnnotation(
        descriptor: String?,
        delegate: AnnotationVisitor,
        index: MappingIndex,
        currentOwner: String?,
        mixinTargetOwners: MutableSet<String>,
    ): AnnotationVisitor? {
        return when (descriptor) {
            MIXIN_ANNOTATION_DESC -> remapMixinAnnotation(delegate, index, mixinTargetOwners)
            in INJECTION_ANNOTATIONS -> remapInjectionAnnotation(delegate, currentOwner, mixinTargetOwners, index)
            else -> null
        }
    }

    private fun remapMixinAnnotation(
        delegate: AnnotationVisitor,
        index: MappingIndex,
        mixinTargetOwners: MutableSet<String>,
    ): AnnotationVisitor {
        return object : AnnotationVisitor(Opcodes.ASM9, delegate) {
            override fun visit(name: String?, value: Any?) {
                if (name == "value" && value is Type) {
                    val mapped = remapMixinType(value, index)
                    mapped.internalName.takeIf { it.isNotBlank() }?.let(mixinTargetOwners::add)
                    super.visit(name, mapped)
                } else {
                    super.visit(name, value)
                }
            }

            override fun visitArray(name: String?): AnnotationVisitor? {
                val base = super.visitArray(name) ?: return null
                if (name != "value") return base
                return object : AnnotationVisitor(Opcodes.ASM9, base) {
                    override fun visit(name: String?, value: Any?) {
                        if (value is Type) {
                            val mapped = remapMixinType(value, index)
                            mapped.internalName.takeIf { it.isNotBlank() }?.let(mixinTargetOwners::add)
                            super.visit(name, mapped)
                        } else {
                            super.visit(name, value)
                        }
                    }
                }
            }
        }
    }

    private fun remapInjectionAnnotation(
        delegate: AnnotationVisitor,
        currentOwner: String?,
        mixinTargetOwners: MutableSet<String>,
        index: MappingIndex,
    ): AnnotationVisitor {
        fun targetOwner(): String = mixinTargetOwners.firstOrNull() ?: currentOwner.orEmpty()

        fun remapArrayValue(arrayName: String, value: Any?): Any? {
            if (value !is String) return value
            return when (arrayName) {
                "method" -> index.remapMemberName(targetOwner(), value)
                "target" -> remapMixinTargetDescriptor(value, index)
                else -> value
            }
        }

        fun remapAtAnnotation(delegateAt: AnnotationVisitor): AnnotationVisitor =
            object : AnnotationVisitor(Opcodes.ASM9, delegateAt) {
                override fun visit(name: String?, value: Any?) {
                    super.visit(name, if (name == "target" && value is String) remapMixinTargetDescriptor(value, index) else value)
                }
            }

        return object : AnnotationVisitor(Opcodes.ASM9, delegate) {
            override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor? {
                val nested = super.visitAnnotation(name, descriptor) ?: return null
                return if (descriptor == MIXIN_AT_ANNOTATION_DESC) remapAtAnnotation(nested) else nested
            }

            override fun visitArray(name: String?): AnnotationVisitor? {
                val base = super.visitArray(name) ?: return null
                if (name == "at") {
                    return object : AnnotationVisitor(Opcodes.ASM9, base) {
                        override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor? {
                            val nested = super.visitAnnotation(name, descriptor) ?: return null
                            return if (descriptor == MIXIN_AT_ANNOTATION_DESC) remapAtAnnotation(nested) else nested
                        }
                    }
                }
                if (name != "method" && name != "target") return base
                val arrayName = name
                return object : AnnotationVisitor(Opcodes.ASM9, base) {
                    override fun visit(name: String?, value: Any?) {
                        super.visit(name, remapArrayValue(arrayName, value))
                    }
                }
            }

            override fun visit(name: String?, value: Any?) {
                val mapped = when (name) {
                    "target" if value is String -> remapMixinTargetDescriptor(value, index)
                    "method" if value is String -> index.remapMemberName(targetOwner(), value)
                    else -> value
                }
                super.visit(name, mapped)
            }
        }
    }

    private fun remapMixinType(type: Type, index: MappingIndex): Type {
        val internal = type.internalName
        val mapped = index.remapClass(internal)
        if (mapped == internal) return type
        return Type.getType("L${mapped};")
    }

    private fun remapMixinTargetDescriptor(target: String, index: MappingIndex): String {
        val owner = MIXIN_OWNER_REGEX.find(target)?.groupValues?.getOrNull(1) ?: return target
        val mappedOwner = index.remapClass(owner)
        val method = MIXIN_METHOD_REGEX.find(target) ?: return target.replaceFirst("L${owner};", "L${mappedOwner};")

        val name = method.groupValues[1]
        val sourceDescriptor = "(${method.groupValues[2]})${method.groupValues[3]}"
        val mappedName = index.remapMemberName(owner, name, sourceDescriptor)
        val mappedDescriptor = index.remapDescriptor(sourceDescriptor)
        return "L${mappedOwner};${mappedName}${mappedDescriptor}"
    }

    fun remapJars(
        inputJars: List<File>,
        outputDir: File,
        mapping: IMappingProvider,
        classpathJars: List<File>,
    ) {
        if (inputJars.isEmpty()) return
        outputDir.mkdirs()

        val remapper = TinyRemapper.newRemapper()
            .withMappings(mapping)
            .extension(MixinExtension())
            .build()
        try {
            classpathJars.forEach { remapper.readClassPathAsync(it.toPath()) }

            val tagsByJar = inputJars.associateWith { remapper.createInputTag() }
            inputJars.forEach { jar -> remapper.readInputsAsync(tagsByJar.getValue(jar), jar.toPath()) }

            inputJars.forEach { jar ->
                val outputJar = File(outputDir, jar.name)
                Files.deleteIfExists(outputJar.toPath())
                createEmptyJar(outputJar)
                OutputConsumerPath.Builder(outputJar.toPath()).build().use { consumer ->
                    remapper.apply(consumer, tagsByJar.getValue(jar))
                }
            }
        } finally {
            remapper.finish()
        }
    }

    private fun createEmptyJar(file: File) {
        file.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(file))).close()
    }
}

private class MappingIndex(private val mapping: ProGuardMappings.Direction) {
    private val classesReverse: Map<String, String> =
        mapping.classes.entries.associate { (src, dst) -> dst to src }

    private val methodsByOwnerAndName: Map<Pair<String, String>, List<Pair<ProGuardMappings.Member, String>>> =
        mapping.methods.entries.groupBy({ (member, _) -> member.owner to member.name }, { it.toPair() })

    private val methodsByOwnerAndDescriptor: Map<Pair<String, String>, List<Pair<ProGuardMappings.Member, String>>> =
        mapping.methods.entries.groupBy({ (member, _) -> member.owner to member.descriptor }, { it.toPair() })

    fun remapClass(internal: String): String = mapping.classes[internal] ?: internal

    fun remapDescriptor(descriptor: String): String = remapDescriptorClasses(descriptor, mapping.classes)

    fun remapMemberName(ownerInternal: String, friendlyName: String, sourceDescriptor: String? = null): String {
        if (ownerInternal.isBlank()) return friendlyName
        val sourceOwner = classesReverse[ownerInternal] ?: ownerInternal

        methodsByOwnerAndName[sourceOwner to friendlyName]?.let { candidates ->
            val exact = sourceDescriptor?.let { desc -> candidates.firstOrNull { it.first.descriptor == desc } }
            return (exact ?: candidates.first()).second
        }

        if (sourceDescriptor != null) {
            methodsByOwnerAndDescriptor[sourceOwner to sourceDescriptor]?.singleOrNull()?.let { return it.second }
        }

        return friendlyName
    }
}
