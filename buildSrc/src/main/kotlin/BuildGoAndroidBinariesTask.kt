import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

private data class GoAndroidAbiSpec(
    val androidAbi: String,
    val goArch: String,
    val goArm: String? = null,
    val requiresNdkToolchain: Boolean = false,
) {
    fun clangExecutableName(minSdk: Int): String =
        when (androidAbi) {
            "arm64-v8a" -> "aarch64-linux-android${minSdk}-clang"
            "armeabi-v7a" -> "armv7a-linux-androideabi${minSdk}-clang"
            "x86" -> "i686-linux-android${minSdk}-clang"
            "x86_64" -> "x86_64-linux-android${minSdk}-clang"
            else -> error("Unsupported Android ABI: $androidAbi")
        }
}

abstract class BuildGoAndroidBinariesTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val sdkDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val abiMatrix: ListProperty<String>

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val minSdkApi: Property<Int>

    @TaskAction
    fun build() {
        val binaries = abiMatrix.get().map { spec ->
            val (androidAbi, goArch, goArm, requiresNdkToolchain) = spec.split("|")
            GoAndroidAbiSpec(
                androidAbi = androidAbi,
                goArch = goArch,
                goArm = goArm.ifBlank { null },
                requiresNdkToolchain = requiresNdkToolchain.toBoolean(),
            )
        }
        val outputRoot = outputDir.get().asFile
        outputRoot.deleteRecursively()
        outputRoot.mkdirs()

        val ndkDir = sdkDir.get().asFile
            .resolve("ndk")
            .resolve(ndkVersion.get())
        val prebuiltDir = ndkDir
            .resolve("toolchains")
            .resolve("llvm")
            .resolve("prebuilt")
        val prebuiltHostDir = prebuiltDir
            .listFiles(File::isDirectory)
            ?.sortedBy { it.name }
            ?.firstOrNull()
        val toolchainBin = if (binaries.any { it.requiresNdkToolchain }) {
            val resolvedToolchainBin = prebuiltHostDir?.resolve("bin")
            if (resolvedToolchainBin == null || !resolvedToolchainBin.isDirectory) {
                throw GradleException(
                    "Android NDK ${ndkVersion.get()} not found at ${prebuiltDir.absolutePath}. " +
                        "Install it with sdkmanager \"ndk;${ndkVersion.get()}\".",
                )
            }
            resolvedToolchainBin
        } else {
            null
        }

        binaries.forEach { abi ->
            val abiDir = outputRoot.resolve(abi.androidAbi).apply { mkdirs() }
            val outputBinary = abiDir.resolve("libproxyt.so")
            val env = mutableMapOf(
                "GOOS" to "android",
                "GOARCH" to abi.goArch,
            )
            abi.goArm?.let { env["GOARM"] = it }

            if (abi.requiresNdkToolchain) {
                val compiler = requireNotNull(toolchainBin) {
                    "Android NDK toolchain is required for ${abi.androidAbi} but was not resolved."
                }.resolve(abi.clangExecutableName(minSdkApi.get()))
                if (!compiler.canExecute()) {
                    throw GradleException("Required Android NDK compiler not found: ${compiler.absolutePath}")
                }
                env["CGO_ENABLED"] = "1"
                env["CC"] = compiler.absolutePath
            } else {
                env["CGO_ENABLED"] = "0"
            }

            execOperations.exec {
                workingDir = sourceDir.get().asFile
                environment(env)
                commandLine(
                    "go",
                    "build",
                    "-trimpath",
                    "-ldflags=-s -w",
                    "-o",
                    outputBinary.absolutePath,
                    ".",
                )
            }
            outputBinary.setExecutable(true, true)
        }
    }
}
