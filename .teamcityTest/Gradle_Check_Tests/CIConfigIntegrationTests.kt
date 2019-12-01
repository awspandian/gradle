import Gradle_Check.model.GradleBuildBucketProvider
import Gradle_Check.model.StatisticBasedGradleBuildBucketProvider
import common.JvmVendor
import common.JvmVersion
import common.NoBuildCache
import common.Os
import configurations.FunctionalTest
import configurations.StagePasses
import jetbrains.buildServer.configs.kotlin.v2018_2.Project
import jetbrains.buildServer.configs.kotlin.v2018_2.buildSteps.GradleBuildStep
import model.CIBuildModel
import model.GradleSubProject
import model.SpecificBuild
import model.Stage
import model.StageNames
import model.TestCoverage
import model.TestType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import projects.FunctionalTestProject
import projects.RootProject
import projects.StageProject
import java.io.File

class CIConfigIntegrationTests {
    private val model = CIBuildModel(buildScanTags = listOf("Check"))
    private val gradleBuildBucketProvider = StatisticBasedGradleBuildBucketProvider(model, File("./test-class-data.json").absoluteFile)
    private val rootProject = RootProject(model, gradleBuildBucketProvider)
    @Test
    fun configurationTreeCanBeGenerated() {
        assertEquals(rootProject.subProjects.size, model.stages.size + 1)
        assertEquals(rootProject.buildTypes.size, model.stages.size)
    }

    @Test
    fun macBuildsHasEmptyRepoMirrorUrlsParam() {
        val readyForRelease = rootProject.searchBuildProject("Gradle_Check_Stage_ReadyforRelease")
        val macBuilds = readyForRelease.subProjects.filter { it.name.contains("Macos") }.flatMap { (it as FunctionalTestProject).functionalTests }
        assertTrue(macBuilds.isNotEmpty())
        assertTrue(macBuilds.all { it.params.findRawParam("env.REPO_MIRROR_URLS")!!.value == "" })
    }

    @Test
    fun macOSBuildsSubset() {
        val readyForRelease = rootProject.subProjects.find { it.name.contains(StageNames.READY_FOR_RELEASE.stageName) }!!
        val macOS = readyForRelease.subProjects.find { it.name.contains("Macos") }!!

        macOS.buildTypes.forEach { buildType ->
            assertFalse(Os.macos.ignoredSubprojects.any { subproject ->
                buildType.name.endsWith("($subproject)")
            })
        }
    }

    @Test
    fun configurationsHaveDependencies() {
        val stagePassConfigs = rootProject.buildTypes
        stagePassConfigs.forEach {
            val stageNumber = stagePassConfigs.indexOf(it) + 1
            println(it.id)
            it.dependencies.items.forEach {
                println("--> " + it.buildTypeId)
            }
            if (stageNumber <= model.stages.size) {
                val stage = model.stages[stageNumber - 1]
                val prevStage = if (stageNumber > 1) model.stages[stageNumber - 2] else null
                var functionalTestCount = 0

                if (stage.runsIndependent) {
                    return@forEach
                }

                stage.functionalTests.forEach { testCoverage ->
                    functionalTestCount += gradleBuildBucketProvider.createFunctionalTestsFor(stage, testCoverage).size
                    if (testCoverage.testType == TestType.soak) {
                        functionalTestCount++
                    }
                }

                // hacky way to consider deferred tests
                val deferredTestCount = if (stage.stageName == StageNames.READY_FOR_NIGHTLY) 5 else 0
                assertEquals(
                    stage.specificBuilds.size + functionalTestCount + stage.performanceTests.size + (if (prevStage != null) 1 else 0) + deferredTestCount,
                    it.dependencies.items.size, stage.stageName.stageName)
            } else {
                assertEquals(2, it.dependencies.items.size) // Individual Performance Worker
            }
        }
    }

    class SubProjectBucketProvider(private val model: CIBuildModel) : GradleBuildBucketProvider {
        override fun createFunctionalTestsFor(stage: Stage, testConfig: TestCoverage) =
            model.subProjects.map { it.createFunctionalTestsFor(model, stage, testConfig) }

        override fun createDeferredFunctionalTestsFor(stage: Stage) = emptyList<FunctionalTest>()
    }

    @Test
    fun canDeactivateBuildCacheAndAdjustCIModel() {
        val m = CIBuildModel(
            projectPrefix = "Gradle_BuildCacheDeactivated_",
            parentBuildCache = NoBuildCache,
            childBuildCache = NoBuildCache,
            stages = listOf(
                Stage(StageNames.QUICK_FEEDBACK,
                    specificBuilds = listOf(
                        SpecificBuild.CompileAll,
                        SpecificBuild.SanityCheck,
                        SpecificBuild.BuildDistributions),
                    functionalTests = listOf(
                        TestCoverage(1, TestType.quick, Os.linux, JvmVersion.java8),
                        TestCoverage(2, TestType.quick, Os.windows, JvmVersion.java11, vendor = JvmVendor.openjdk)),
                    omitsSlowProjects = true)
            )
        )
        val p = RootProject(m, SubProjectBucketProvider(m))
        printTree(p)
        assertTrue(p.subProjects.size == 1)
    }

    private
    fun Project.searchBuildProject(id: String): StageProject = (subProjects.find { it.id!!.value == id } as StageProject)

    private
    val largeSubProjectRegex = """\((\w+(_\d+)?)\)""".toRegex()

    /**
     * Test Coverage - AllVersionsCrossVersion Java8 Oracle Linux (core_2) -> core_2
     */
    private
    fun FunctionalTest.getSubProjectSplitName() = largeSubProjectRegex.find(this.name)!!.groupValues[1]

    private
    fun FunctionalTest.getGradleTasks(): String {
        val runnerStep = this.steps.items.find { it.name == "GRADLE_RUNNER" } as GradleBuildStep
        return runnerStep.tasks!!
    }

    private
    fun FunctionalTest.getGradleParams(): String {
        val runnerStep = this.steps.items.find { it.name == "GRADLE_RUNNER" } as GradleBuildStep
        return runnerStep.gradleParams!!
    }

    @Test
    fun canSplitLargeProjects() {
        fun assertAllSplitsArePresent(subProjectName: String, functionalTests: List<FunctionalTest>) {
            val splitSubProjectNames = functionalTests.map { it.getSubProjectSplitName() }.toSet()
            val expectedProjectNames = (1..functionalTests.size).map {
                if (it == 1) {
                    subProjectName
                } else {
                    "${subProjectName}_$it"
                }
            }.toSet()
            assertEquals(expectedProjectNames, splitSubProjectNames)
        }

        fun assertCorrectParameters(subProjectName: String, functionalTests: List<FunctionalTest>) {
            functionalTests.forEach { assertTrue(it.getGradleTasks().startsWith("clean $subProjectName")) }
            if (functionalTests.size == 1) {
                assertFalse(functionalTests[0].getGradleParams().contains("-PincludeTestClasses"))
                assertFalse(functionalTests[0].getGradleParams().contains("-PexcludeTestClasses"))
            } else {
                functionalTests.forEachIndexed { index, it ->
                    if (index == functionalTests.size - 1) {
                        assertFalse(it.getGradleParams().contains("-PincludeTestClasses"))
                        assertTrue(it.getGradleParams().contains("-PexcludeTestClasses"))
                    } else {
                        assertTrue(it.getGradleParams().contains("-PincludeTestClasses"))
                        assertFalse(it.getGradleParams().contains("-PexcludeTestClasses"))
                    }
                }
            }
        }

        fun assertProjectAreSplitByClassesCorrectly(functionalTests: List<FunctionalTest>) {
            val functionalTestsWithSplit: Map<String, List<FunctionalTest>> = functionalTests.filter { largeSubProjectRegex.containsMatchIn(it.name) }.groupBy { it.getSubProjectSplitName().substringBefore('_') }
            functionalTestsWithSplit.forEach {
                assertAllSplitsArePresent(it.key, it.value)
                assertCorrectParameters(it.key, it.value)
            }
        }

        fun assertProjectAreSplitByGradleVersionCorrectly(testType: TestType, functionalTests: List<FunctionalTest>) {
            (1..6).forEach {
                assertTrue(functionalTests[it - 1].name.contains("gradle $it"))
                assertEquals("clean ${testType}Test", functionalTests[it - 1].getGradleTasks())
                assertTrue(functionalTests[it - 1].getGradleParams().contains("-PtestGradleVersion=$it"))
            }
        }

        fun assertMultiVersionIntegrationTest(functionalTests: List<FunctionalTest>) {
            assertEquals(1, functionalTests.size)
            assertEquals("clean allVersionsIntegMultiVersionTest", functionalTests[0].getGradleTasks())
        }

        for (stageProject in rootProject.subProjects.filterIsInstance<StageProject>()) {
            for (functionalTestProject in stageProject.subProjects.filterIsInstance<FunctionalTestProject>()) {
                when {
                    functionalTestProject.name.contains("AllVersionsCrossVersion") -> {
                        assertProjectAreSplitByGradleVersionCorrectly(TestType.allVersionsCrossVersion, functionalTestProject.functionalTests)
                    }
                    functionalTestProject.name.contains("QuickFeedbackCrossVersion") -> {
                        assertProjectAreSplitByGradleVersionCorrectly(TestType.quickFeedbackCrossVersion, functionalTestProject.functionalTests)
                    }
                    functionalTestProject.name.contains("AllVersionsIntegMultiVersion") -> {
                        assertMultiVersionIntegrationTest(functionalTestProject.functionalTests)
                    }
                    else -> {
                        assertProjectAreSplitByClassesCorrectly(functionalTestProject.functionalTests)
                    }
                }
            }
        }
    }

    @Test
    fun canDeferSlowTestsToLaterStage() {
        val slowSubProjects = model.subProjects.filter { it.containsSlowTests }.map { it.name }

        fun FunctionalTest.isSlow(): Boolean = slowSubProjects.any { name.contains(it) }
        fun Project.subprojectContainsSlowTests(id: String): Boolean = searchBuildProject(id).functionalTests.any(FunctionalTest::isSlow)

        assertTrue(!rootProject.subprojectContainsSlowTests("Gradle_Check_Stage_QuickFeedbackLinuxOnly"))
        assertTrue(!rootProject.subprojectContainsSlowTests("Gradle_Check_Stage_QuickFeedback"))
        assertTrue(!rootProject.subprojectContainsSlowTests("Gradle_Check_Stage_ReadyforMerge"))
        assertTrue(rootProject.subprojectContainsSlowTests("Gradle_Check_Stage_ReadyforNightly"))
        assertTrue(rootProject.subprojectContainsSlowTests("Gradle_Check_Stage_ReadyforRelease"))
    }

    @Test
    fun onlyReadyForNightlyTriggerHasUpdateBranchStatus() {
        val triggerNameToTasks = rootProject.buildTypes.map { it.uuid to ((it as StagePasses).steps.items[0] as GradleBuildStep).tasks }.toMap()

        assertEquals(mapOf(
            "Gradle_Check_Stage_QuickFeedbackLinuxOnly_Trigger" to "createBuildReceipt",
            "Gradle_Check_Stage_QuickFeedback_Trigger" to "createBuildReceipt",
            "Gradle_Check_Stage_BranchBuildAccept_Trigger" to "createBuildReceipt",
            "Gradle_Check_Stage_MasterAccept_Trigger" to "createBuildReceipt updateBranchStatus",
            "Gradle_Check_Stage_ReleaseAccept_Trigger" to "createBuildReceipt",
            "Gradle_Check_Stage_HistoricalPerformance_Trigger" to "createBuildReceipt",
            "Gradle_Check_Stage_Experimental_Trigger" to "createBuildReceipt",
            "Gradle_Check_Stage_ExperimentalWindows10_Trigger" to "createBuildReceipt"),
            triggerNameToTasks)
    }

    @Test
    fun allSubprojectsAreListed() {
        val knownSubProjectNames = CIBuildModel().subProjects.map { it.asDirectoryName() }
        subProjectFolderList().forEach {
            assertTrue(
                it.name in knownSubProjectNames,
                "Not defined: $it"
            )
        }
    }

    @Test
    fun testsAreCorrectlyConfiguredForAllSubProjects() {
        CIBuildModel().subProjects.filter {
            !listOf(
                "soak", // soak test
                "distributions", // build distributions
                "docs", // sanity check
                "architectureTest" // sanity check
            ).contains(it.name)
        }.forEach {
            val dir = getSubProjectFolder(it)
            assertEquals(it.unitTests, File(dir, "src/test").isDirectory, "${it.name}'s unitTests is wrong!")
            assertEquals(it.functionalTests, File(dir, "src/integTest").isDirectory, "${it.name}'s functionalTests is wrong!")
            assertEquals(it.crossVersionTests, File(dir, "src/crossVersionTest").isDirectory, "${it.name}'s crossVersionTests is wrong!")
        }
    }

    @Test
    fun allSubprojectsDefineTheirUnitTestPropertyCorrectly() {
        val projectsWithUnitTests = CIBuildModel().subProjects.filter { it.unitTests }
        val projectFoldersWithUnitTests = subProjectFolderList().filter {
            File(it, "src/test").exists() &&
                it.name != "docs" && // docs:check is part of Sanity Check
                it.name != "architecture-test" // architectureTest:test is part of Sanity Check
        }
        assertFalse(projectFoldersWithUnitTests.isEmpty())
        projectFoldersWithUnitTests.forEach {
            assertTrue(projectsWithUnitTests.map { it.asDirectoryName() }.contains(it.name), "Contains unit tests: $it")
        }
    }

    @Test
    fun allSubprojectsDefineTheirFunctionTestPropertyCorrectly() {
        val projectsWithFunctionalTests = CIBuildModel().subProjects.filter { it.functionalTests }
        val projectFoldersWithFunctionalTests = subProjectFolderList().filter {
            File(it, "src/integTest").exists() &&
                it.name != "distributions" && // distributions:integTest is part of Build Distributions
                it.name != "soak" // soak tests have their own test category
        }
        assertFalse(projectFoldersWithFunctionalTests.isEmpty())
        projectFoldersWithFunctionalTests.forEach {
            assertTrue(projectsWithFunctionalTests.map { it.asDirectoryName() }.contains(it.name), "Contains functional tests: $it")
        }
    }

    @Test
    fun allSubprojectsDefineTheirCrossVersionTestPropertyCorrectly() {
        val projectsWithCrossVersionTests = CIBuildModel().subProjects.filter { it.crossVersionTests }
        val projectFoldersWithCrossVersionTests = subProjectFolderList().filter { File(it, "src/crossVersionTest").exists() }
        assertFalse(projectFoldersWithCrossVersionTests.isEmpty())
        projectFoldersWithCrossVersionTests.forEach {
            assertTrue(projectsWithCrossVersionTests.map { it.asDirectoryName() }.contains(it.name), "Contains cross-version tests: $it")
        }
    }

    @Test
    fun integTestFolderDoesNotContainCrossVersionTests() {
        val projectFoldersWithFunctionalTests = subProjectFolderList().filter { File(it, "src/integTest").exists() }
        assertFalse(projectFoldersWithFunctionalTests.isEmpty())
        projectFoldersWithFunctionalTests.forEach {
            assertFalse(containsSrcFileWithString(File(it, "src/integTest"), "CrossVersion", listOf("package org.gradle.testkit", "CrossVersionPerformanceTest")))
        }
    }

    @Test
    fun long_ids_are_shortened() {
        val testCoverage = TestCoverage(1, TestType.quickFeedbackCrossVersion, Os.windows, JvmVersion.java11, JvmVendor.oracle)
        val shortenedId = testCoverage.asConfigurationId(CIBuildModel(), "veryLongSubprojectNameLongerThanEverythingWeHave")
        assertTrue(shortenedId.length < 80)
        assertEquals("Gradle_Check_QckFdbckCrssVrsn_1_vryLngSbprjctNmLngrThnEvrythngWHv", shortenedId)

        assertEquals("Gradle_Check_QuickFeedbackCrossVersion_1_iIntegT", testCoverage.asConfigurationId(CIBuildModel(), "internalIntegTesting"))

        assertEquals("Gradle_Check_QuickFeedbackCrossVersion_1_buildCache", testCoverage.asConfigurationId(CIBuildModel(), "buildCache"))

        assertEquals("Gradle_Check_QuickFeedbackCrossVersion_1_0", testCoverage.asConfigurationId(CIBuildModel()))
    }

    private fun containsSrcFileWithString(srcRoot: File, content: String, exceptions: List<String>): Boolean {
        srcRoot.walkTopDown().forEach {
            if (it.extension == "groovy" || it.extension == "java") {
                val text = it.readText()
                if (text.contains(content) && exceptions.all { !text.contains(it) }) {
                    println("Found suspicious test file: $it")
                    return true
                }
            }
        }
        return false
    }

    private fun subProjectFolderList(): List<File> {
        val subprojectFolders = File("../subprojects").listFiles().filter { it.isDirectory }
        assertFalse(subprojectFolders.isEmpty())
        return subprojectFolders
    }

    private fun getSubProjectFolder(subProject: GradleSubProject): File = File("../subprojects/${subProject.asDirectoryName()}")

    private fun printTree(project: Project, indent: String = "") {
        println(indent + project.id + " (Project)")
        project.buildTypes.forEach { bt ->
            println("$indent+- ${bt.id} (Config)")
        }
        project.subProjects.forEach { subProject ->
            printTree(subProject, "$indent   ")
        }
    }
}
