plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("gg.essential.multi-version.root")
}

preprocess.strictExtraMappings.set(true)
preprocess {
    val fabric_26_02_00 = createNode("26.2-fabric",    26_02_00, null)
    val fabric_01_19_04 = createNode("1.19.4-fabric",  1_19_04, "official")
    val fabric_01_14_04 = createNode("1.14.4-fabric",  1_14_04, "official")
    fabric_26_02_00.link(fabric_01_19_04)
    fabric_01_19_04.link(fabric_01_14_04)

    val neoforge_26_02_00 = createNode("26.2-neoforge",    26_02_00, null)
    val neoforge_01_21_11 = createNode("1.21.11-neoforge", 1_21_11, "official")
    fabric_26_02_00.link(neoforge_26_02_00)
    neoforge_26_02_00.link(neoforge_01_21_11)
}

subprojects {
    afterEvaluate {
        tasks.findByName("preprocessTestCode")?.enabled = false
    }
}
