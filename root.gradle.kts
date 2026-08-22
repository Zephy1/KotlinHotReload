plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("gg.essential.multi-version.root")
}

preprocess.strictExtraMappings.set(true)
preprocess {
    val fabric_26_02_00 = createNode("26.2-fabric",    26_02_00, null)
    val fabric_01_21_11 = createNode("1.21.11-fabric", 1_21_11, "official")
    val fabric_01_21_10 = createNode("1.21.10-fabric", 1_21_10, "official")
    val fabric_01_19_04 = createNode("1.19.4-fabric",  1_19_04, "official")
    val fabric_01_18_02 = createNode("1.18.2-fabric",  1_18_02, "official")
    val fabric_01_15_02 = createNode("1.15.2-fabric",  1_15_02, "official")

    fabric_26_02_00.link(fabric_01_21_11)
    fabric_01_21_11.link(fabric_01_21_10)
    fabric_01_21_10.link(fabric_01_19_04)
    fabric_01_19_04.link(fabric_01_18_02)
    fabric_01_18_02.link(fabric_01_15_02)

//    val neoforge26_02 = createNode("26.2-neoforge", 26_02_00, null)
//    fabric26_02.link(neoforge26_02)
}

subprojects {
    afterEvaluate {
        tasks.findByName("preprocessTestCode")?.enabled = false
    }
}
