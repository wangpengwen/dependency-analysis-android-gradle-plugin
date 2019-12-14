@file:Suppress("UnstableApiUsage")

package com.autonomousapps

import com.autonomousapps.internal.ClassAnalyzer
import com.autonomousapps.internal.asm.ClassReader
import com.autonomousapps.internal.kotlin.dump
import com.autonomousapps.internal.kotlin.filterOutNonPublic
import com.autonomousapps.internal.kotlin.getBinaryAPI
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

interface ClassAnalysisTask : Task {
    @get:OutputFile
    val output: RegularFileProperty
}

// This regex matches a Java FQCN.
// https://stackoverflow.com/questions/5205339/regular-expression-matching-fully-qualified-class-names#comment5855158_5205467
private val JAVA_FQCN_REGEX =
    "(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*\\.)+\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*".toRegex()

/**
 * Produces a report of all classes referenced by a given jar.
 */
@CacheableTask
open class JarAnalysisTask @Inject constructor(
    private val objects: ObjectFactory,
    private val workerExecutor: WorkerExecutor
) : DefaultTask(), ClassAnalysisTask {

    // TODO the result of this against chess/net has some issues. Need to track them down.

    init {
        group = "verification"
        description = "Produces a report of all classes referenced by a given jar"
    }

    @get:Classpath
    val jar: RegularFileProperty = objects.fileProperty()

    /**
     * Java source files. Stubs generated by the kotlin-kapt plugin.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    val kaptJavaStubs: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Android layout XML files.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    val layoutFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:OutputFile
    override val output: RegularFileProperty = objects.fileProperty()

    internal fun layouts(files: List<File>) {
        for (file in files) {
            layoutFiles.from(
                // TODO Gradle 6 can do objects.fileTree().from(file)
                objects.fileCollection().from(file).asFileTree
                    .matching {
                        include { it.path.contains("layout") }
                    }.files
            )
        }
    }

    @TaskAction
    fun action() {
        val reportFile = output.get().asFile

        // Cleanup prior execution
        reportFile.delete()

        val jarFile = jar.get().asFile

        // TODO start
        project.file("${project.buildDir}/dependency-analysis/abi.txt").bufferedWriter().use {
            getBinaryAPI(JarFile(jarFile.path)).filterOutNonPublic().dump(it)
        }
        val abi = project.file("${project.buildDir}/dependency-analysis/abi.txt").bufferedReader().use {
            it.readLines().asSequence().flatMap { line ->
                "L(.+);".toRegex().findAll(line)
            }.flatMap { matchResult ->
                val groupValues = matchResult.groupValues

                return@flatMap if (groupValues.isNotEmpty()) groupValues.subList(1, groupValues.size).asSequence()
                else emptySequence()
            }.toSortedSet()
        }
        println("ABI=\n${abi.joinToString("\n")}")
        // TODO end

        workerExecutor.noIsolation().submit(JarAnalysisWorkAction::class.java) {
            jar = jarFile
            kaptJavaSource = kaptJavaStubs.files
            layouts = layoutFiles.files
            report = reportFile
        }
        workerExecutor.await()

        logger.debug("Report:\n${reportFile.readText()}")
    }
}

interface JarAnalysisParameters : WorkParameters {
    var jar: File
    var kaptJavaSource: Set<File>
    var layouts: Set<File>
    var report: File
}

abstract class JarAnalysisWorkAction : WorkAction<JarAnalysisParameters> {

    private val logger = LoggerFactory.getLogger(JarAnalysisWorkAction::class.java)

    // TODO some jars only have metadata. What to do about them?
    // 1. e.g. kotlin-stdlib-common-1.3.50.jar
    // 2. e.g. legacy-support-v4-1.0.0/jars/classes.jar
    override fun execute() {
        // Analyze class usage in jar file
        val z = ZipFile(parameters.jar)
        val classNames = z.entries().toList()
            .filterNot { it.isDirectory }
            .filter { it.name.endsWith(".class") }
            .map { classEntry -> z.getInputStream(classEntry).use { ClassReader(it.readBytes()) } }
            .collectClassNames(logger)

        // Analyze class usage in layout files
        parameters.layouts.map { layoutFile ->
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(layoutFile)
            document.documentElement.normalize()
            document.getElementsByTagName("*")
        }.flatMap { nodeList ->
            nodeList.map { it.nodeName }.filter { it.contains(".") }
        }.fold(classNames) { set, item -> set.apply { add(item) } }

        parameters.kaptJavaSource
            .flatMap { it.readLines() }
            .flatMap { JAVA_FQCN_REGEX.findAll(it).toList() }
            .map { it.value }
            .map { it.removeSuffix(".class") }
            .fold(classNames) { set, item -> set.apply { add(item) } }

        parameters.report.writeText(classNames.joinToString(separator = "\n"))
    }
}

/**
 * Produces a report of all classes referenced by a given set of class files.
 */
@CacheableTask
open class ClassListAnalysisTask @Inject constructor(
    private val objects: ObjectFactory,
    private val workerExecutor: WorkerExecutor
) : DefaultTask(), ClassAnalysisTask {

    init {
        group = "verification"
        description = "Produces a report of all classes referenced by a given jar"
    }

    /**
     * Class files generated by Kotlin source.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    val kotlinClasses: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Class files generated by Java source.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    val javaClasses: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Java source files. Stubs generated by the kotlin-kapt plugin.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    val kaptJavaStubs: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Android layout XML files.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    val layoutFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:OutputFile
    override val output: RegularFileProperty = objects.fileProperty()

    internal fun layouts(files: List<File>) {
        for (file in files) {
            layoutFiles.from(
                // TODO Gradle 6 can do objects.fileTree().from(file)
                objects.fileCollection().from(file).asFileTree
                    .matching {
                        include { it.path.contains("layout") }
                    }.files
            )
        }
    }

    @TaskAction
    fun action() {
        val reportFile = output.get().asFile

        // Cleanup prior execution
        reportFile.delete()

        val inputClassFiles = javaClasses.asFileTree.plus(kotlinClasses)
            .filter { it.isFile && it.name.endsWith(".class") }
            .files

        workerExecutor.noIsolation().submit(ClassListAnalysisWorkAction::class.java) {
            classes = inputClassFiles
            kaptJavaSource = kaptJavaStubs.files
            layouts = layoutFiles.files
            report = reportFile
        }
        workerExecutor.await()

        logger.debug("Report:\n${reportFile.readText()}")
    }
}

interface ClassListAnalysisParameters : WorkParameters {
    var classes: Set<File>
    var kaptJavaSource: Set<File>
    var layouts: Set<File>
    var report: File
}

abstract class ClassListAnalysisWorkAction : WorkAction<ClassListAnalysisParameters> {

    private val logger = LoggerFactory.getLogger(JarAnalysisWorkAction::class.java)

    // TODO some jars only have metadata. What to do about them?
    // 1. e.g. kotlin-stdlib-common-1.3.50.jar
    // 2. e.g. legacy-support-v4-1.0.0/jars/classes.jar
    override fun execute() {
        // Analyze class usage in collection of class files
        val classNames = parameters.classes
            .map { classFile -> classFile.inputStream().use { ClassReader(it) } }
            .collectClassNames(logger)

        // Analyze class usage in layout files
        parameters.layouts.map { layoutFile ->
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(layoutFile)
            document.documentElement.normalize()
            document.getElementsByTagName("*")
        }.flatMap { nodeList ->
            nodeList.map { it.nodeName }.filter { it.contains(".") }
        }.fold(classNames) { set, item -> set.apply { add(item) } }

        parameters.kaptJavaSource
            .flatMap { it.readLines() }
            .flatMap { JAVA_FQCN_REGEX.findAll(it).toList() }
            .map { it.value }
            .map { it.removeSuffix(".class") }
            .fold(classNames) { set, item -> set.apply { add(item) } }

        parameters.report.writeText(classNames.joinToString(separator = "\n"))
    }
}

private fun Iterable<ClassReader>.collectClassNames(logger: Logger): MutableSet<String> {
    return map {
        val classNameCollector = ClassAnalyzer(logger)
        it.accept(classNameCollector, 0)
        classNameCollector
    }
        .flatMap { it.classes() }
        .filterNot {
            // Filter out `java` packages, but not `javax`
            it.startsWith("java/")
        }
        .map { it.replace("/", ".") }
        .toSortedSet()
}

private inline fun <R> NodeList.map(transform: (Node) -> R): List<R> {
    val destination = ArrayList<R>(length)
    for (i in 0 until length) {
        destination.add(transform(item(i)))
    }
    return destination
}
