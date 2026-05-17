import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Properties
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

data class RideScopeAppBuildMetadata(
    val build: String,
    val timestamp: String,
    val protocol: String,
    val versionCode: Int,
)

data class RideScopeAppFtpConfig(
    val host: String,
    val port: Int,
    val directory: String,
    val username: String,
    val password: String,
    val tlsMode: RideScopeFtpTlsMode,
)

enum class RideScopeFtpTlsMode {
    Disabled,
    Explicit,
    Implicit,
}

val rideScopeAppProtocol = "4.11"
val rideScopeAppBaseBuild = "1.0.0"
val rideScopeBuildStateFile: File = rootProject.file("ridescope_app_build_state.properties")
val rideScopeManifestAssetFile: Provider<RegularFile> = layout.buildDirectory.file("generated/ridescopeAssets/main/manifest.json")
val rideScopeManifestOutputFile: Provider<RegularFile> = layout.buildDirectory.file("outputs/ridescope/manifest.json")
val rideScopeGeneratedAssetsDir: Provider<Directory> = layout.buildDirectory.dir("generated/ridescopeAssets/main")
val rideScopeTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")
val rideScopeAppFtpHost = providers.environmentVariable("RIDESCOPE_APP_FTP_HOST").orNull ?: "ftp://ftp.sparvieri.org"
val rideScopeAppFtpPort = providers.environmentVariable("RIDESCOPE_APP_FTP_PORT").orNull?.toIntOrNull() ?: 21
val rideScopeAppFtpDirectory = providers.environmentVariable("RIDESCOPE_APP_FTP_DIRECTORY").orNull ?: "www.sparvieri.org/ridescope/app"
val rideScopeAppFtpUsername = providers.environmentVariable("RIDESCOPE_APP_FTP_USER").orNull.orEmpty()
val rideScopeAppFtpPassword = providers.environmentVariable("RIDESCOPE_APP_FTP_PASSWORD").orNull.orEmpty()
val rideScopeAppFtpTlsModeRaw = providers.environmentVariable("RIDESCOPE_APP_FTP_TLS_MODE").orNull ?: "explicit"
val rideScopeLegacyAppFtpPublishEnabled = providers.environmentVariable("RIDESCOPE_ENABLE_LEGACY_APP_FTP_PUBLISH").orNull == "1"
val rideScopeSkipFtpPublish = providers.environmentVariable("RIDESCOPE_SKIP_APP_FTP_PUBLISH").orNull == "1"
val rideScopeReleaseStoreFile = providers.environmentVariable("RIDESCOPE_RELEASE_STORE_FILE").orNull
val rideScopeReleaseStorePassword = providers.environmentVariable("RIDESCOPE_RELEASE_STORE_PASSWORD").orNull
val rideScopeReleaseKeyAlias = providers.environmentVariable("RIDESCOPE_RELEASE_KEY_ALIAS").orNull
val rideScopeReleaseKeyPassword = providers.environmentVariable("RIDESCOPE_RELEASE_KEY_PASSWORD").orNull
val rideScopeReleaseSigningEnabled = listOf(
    rideScopeReleaseStoreFile,
    rideScopeReleaseStorePassword,
    rideScopeReleaseKeyAlias,
    rideScopeReleaseKeyPassword,
).all { !it.isNullOrBlank() }
val rideScopeApkOutputRoot: Provider<Directory> = layout.buildDirectory.dir("outputs/apk")
val rideScopeCanonicalApkName = "ridescope.apk"
val rideScopeAppFtpTlsMode = when (rideScopeAppFtpTlsModeRaw.trim().lowercase()) {
    "off", "plain", "disabled", "false", "0" -> RideScopeFtpTlsMode.Disabled
    "implicit", "ftps-implicit" -> RideScopeFtpTlsMode.Implicit
    else -> RideScopeFtpTlsMode.Explicit
}
val rideScopeAppFtpConfig = RideScopeAppFtpConfig(
    host = rideScopeAppFtpHost,
    port = rideScopeAppFtpPort,
    directory = rideScopeAppFtpDirectory,
    username = rideScopeAppFtpUsername,
    password = rideScopeAppFtpPassword,
    tlsMode = rideScopeAppFtpTlsMode,
)

fun String.toSemverParts(): Triple<Int, Int, Int>? {
    val parts = split(".")
    if (parts.size != 3) {
        return null
    }
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull() ?: return null
    val patch = parts[2].toIntOrNull() ?: return null
    return Triple(major, minor, patch)
}

fun compareSemver(left: String, right: String): Int {
    val leftParts = left.toSemverParts() ?: return left.compareTo(right)
    val rightParts = right.toSemverParts() ?: return left.compareTo(right)
    return when {
        leftParts.first != rightParts.first -> leftParts.first.compareTo(rightParts.first)
        leftParts.second != rightParts.second -> leftParts.second.compareTo(rightParts.second)
        else -> leftParts.third.compareTo(rightParts.third)
    }
}

fun incrementSemverPatch(value: String): String {
    val parts = value.toSemverParts() ?: Triple(1, 0, 0)
    return "${parts.first}.${parts.second}.${parts.third + 1}"
}

fun semverToVersionCode(value: String): Int {
    val parts = value.toSemverParts() ?: Triple(1, 0, 0)
    return (parts.first * 10_000) + (parts.second * 100) + parts.third
}

fun shouldBumpRideScopeBuild(taskNames: List<String>): Boolean {
    if (taskNames.isEmpty()) {
        return false
    }
    val bumpTokens = listOf("assemble", "bundle", "install", "package")
    return taskNames.any { taskName ->
        bumpTokens.any { token -> taskName.contains(token, ignoreCase = true) }
    }
}

fun resolveRideScopeAppBuildMetadata(): RideScopeAppBuildMetadata {
    val properties = Properties()
    if (rideScopeBuildStateFile.exists()) {
        rideScopeBuildStateFile.inputStream().use(properties::load)
    }

    val storedBuild = properties.getProperty("build")
    val storedTimestamp = properties.getProperty("timestamp")
    val storedVersionCode = properties.getProperty("versionCode")?.toIntOrNull()
    val referenceBuild = when {
        storedBuild.isNullOrBlank() -> rideScopeAppBaseBuild
        compareSemver(storedBuild, rideScopeAppBaseBuild) >= 0 -> storedBuild
        else -> rideScopeAppBaseBuild
    }

    val shouldBump = shouldBumpRideScopeBuild(gradle.startParameter.taskNames)
    val resolvedBuild = if (shouldBump) incrementSemverPatch(referenceBuild) else referenceBuild
    val resolvedTimestamp = if (shouldBump) {
        LocalDateTime.now().format(rideScopeTimestampFormatter)
    } else {
        storedTimestamp ?: LocalDateTime.now().format(rideScopeTimestampFormatter)
    }
    val resolvedVersionCode = if (shouldBump) {
        maxOf((storedVersionCode ?: semverToVersionCode(referenceBuild)) + 1, semverToVersionCode(resolvedBuild))
    } else {
        storedVersionCode ?: semverToVersionCode(resolvedBuild)
    }

    if (shouldBump) {
        properties["build"] = resolvedBuild
        properties["timestamp"] = resolvedTimestamp
        properties["protocol"] = rideScopeAppProtocol
        properties["versionCode"] = resolvedVersionCode.toString()
        rideScopeBuildStateFile.parentFile?.mkdirs()
        rideScopeBuildStateFile.outputStream().use { output ->
            properties.store(output, "RideScope app build metadata")
        }
    }

    return RideScopeAppBuildMetadata(
        build = resolvedBuild,
        timestamp = resolvedTimestamp,
        protocol = rideScopeAppProtocol,
        versionCode = resolvedVersionCode,
    )
}

val rideScopeAppBuildMetadata = resolveRideScopeAppBuildMetadata()

fun RideScopeAppFtpConfig.remotePath(fileName: String): String {
    val normalizedDirectory = directory.trim().trimEnd('/').ifBlank { "" }
    return if (normalizedDirectory.isBlank()) {
        "/$fileName"
    } else if (normalizedDirectory.startsWith("/")) {
        "$normalizedDirectory/$fileName"
    } else {
        "/$normalizedDirectory/$fileName"
    }
}

fun RideScopeAppFtpConfig.normalizedHost(): String {
    val trimmedHost = host.trim().trimEnd('/')
    val uriHost = runCatching { URI(trimmedHost).host }.getOrNull()
    if (!uriHost.isNullOrBlank()) {
        return uriHost
    }
    return trimmedHost
        .removePrefix("ftp://")
        .removePrefix("ftps://")
        .substringBefore('/')
        .substringBefore(':')
}

fun readRideScopeFtpReply(reader: BufferedReader): Pair<Int, String> {
    val firstLine = reader.readLine() ?: error("Connessione FTP chiusa")
    require(firstLine.length >= 3) { "Risposta FTP non valida: $firstLine" }
    val code = firstLine.substring(0, 3).toIntOrNull()
        ?: error("Codice FTP non valido: $firstLine")
    if (firstLine.length > 3 && firstLine[3] == '-') {
        while (true) {
            val nextLine = reader.readLine() ?: error("Risposta FTP multilinea interrotta")
            if (nextLine.startsWith("$code ")) {
                return code to nextLine
            }
        }
    }
    return code to firstLine
}

fun sendRideScopeFtpCommand(
    writer: BufferedWriter,
    reader: BufferedReader,
    command: String,
): Pair<Int, String> {
    writer.write(command)
    writer.write("\r\n")
    writer.flush()
    return readRideScopeFtpReply(reader)
}

fun parseRideScopePassiveEndpoint(
    message: String,
    fallbackHost: String,
): InetSocketAddress {
    val start = message.indexOf('(')
    val end = message.indexOf(')', start + 1)
    require(start >= 0 && end >= 0) { "Risposta PASV non valida: $message" }
    val parts = message.substring(start + 1, end).split(',')
    require(parts.size == 6) { "Endpoint PASV non valido: $message" }
    val host = parts.take(4).joinToString(".").ifBlank { fallbackHost }
    val portHigh = parts[4].toIntOrNull() ?: error("Porta PASV non valida: $message")
    val portLow = parts[5].toIntOrNull() ?: error("Porta PASV non valida: $message")
    return InetSocketAddress(host, (portHigh shl 8) + portLow)
}

fun createRideScopeTlsSocket(
    baseSocket: Socket,
    host: String,
    port: Int,
): SSLSocket {
    val tlsSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
        .createSocket(baseSocket, host, port, true) as SSLSocket
    tlsSocket.useClientMode = true
    tlsSocket.soTimeout = 15_000
    tlsSocket.startHandshake()
    return tlsSocket
}

fun createRideScopeImplicitTlsSocket(
    host: String,
    port: Int,
): SSLSocket {
    val plainSocket = Socket()
    plainSocket.connect(InetSocketAddress(host, port), 15_000)
    plainSocket.soTimeout = 15_000
    return createRideScopeTlsSocket(
        baseSocket = plainSocket,
        host = host,
        port = port,
    )
}

fun createRideScopePassiveDataSocket(
    config: RideScopeAppFtpConfig,
    passiveEndpoint: InetSocketAddress,
): Socket {
    val dataSocket = Socket()
    dataSocket.connect(passiveEndpoint, 15_000)
    dataSocket.soTimeout = 15_000
    return when (config.tlsMode) {
        RideScopeFtpTlsMode.Disabled -> dataSocket
        RideScopeFtpTlsMode.Explicit,
        RideScopeFtpTlsMode.Implicit,
        -> createRideScopeTlsSocket(
            baseSocket = dataSocket,
            host = config.normalizedHost(),
            port = passiveEndpoint.port,
        )
    }
}

fun uploadRideScopeFileOverFtp(
    config: RideScopeAppFtpConfig,
    localFile: File,
    remoteFileName: String,
) {
    require(localFile.isFile) { "File locale assente: ${localFile.absolutePath}" }

    val normalizedHost = config.normalizedHost()
    val controlSocket: Socket = when (config.tlsMode) {
        RideScopeFtpTlsMode.Implicit -> createRideScopeImplicitTlsSocket(
            host = normalizedHost,
            port = config.port,
        )
        RideScopeFtpTlsMode.Disabled,
        RideScopeFtpTlsMode.Explicit,
        -> Socket().apply {
            connect(InetSocketAddress(normalizedHost, config.port), 15_000)
            soTimeout = 15_000
        }
    }

    controlSocket.use { socket ->
        var activeSocket = socket
        var reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), StandardCharsets.UTF_8))
        var writer = BufferedWriter(OutputStreamWriter(activeSocket.getOutputStream(), StandardCharsets.UTF_8))

        val welcome = readRideScopeFtpReply(reader)
        require(welcome.first == 220) { "FTP 220 atteso, ricevuto ${welcome.first}: ${welcome.second}" }

        if (config.tlsMode == RideScopeFtpTlsMode.Explicit) {
            val authReply = sendRideScopeFtpCommand(writer, reader, "AUTH TLS")
            require(authReply.first == 234 || authReply.first == 220) {
                "AUTH TLS fallito: ${authReply.second}"
            }
            activeSocket = createRideScopeTlsSocket(
                baseSocket = activeSocket,
                host = normalizedHost,
                port = config.port,
            )
            reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), StandardCharsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(activeSocket.getOutputStream(), StandardCharsets.UTF_8))
        }

        val userReply = sendRideScopeFtpCommand(writer, reader, "USER ${config.username}")
        when (userReply.first) {
            230 -> Unit
            331 -> {
                val passwordReply = sendRideScopeFtpCommand(writer, reader, "PASS ${config.password}")
                require(passwordReply.first == 230) {
                    "Login FTP fallito: ${passwordReply.second}"
                }
            }
            else -> error("Login FTP fallito: ${userReply.second}")
        }

        if (config.tlsMode != RideScopeFtpTlsMode.Disabled) {
            val pbszReply = sendRideScopeFtpCommand(writer, reader, "PBSZ 0")
            require(pbszReply.first == 200) { "PBSZ fallito: ${pbszReply.second}" }

            val protReply = sendRideScopeFtpCommand(writer, reader, "PROT P")
            require(protReply.first == 200) { "PROT P fallito: ${protReply.second}" }
        }

        val typeReply = sendRideScopeFtpCommand(writer, reader, "TYPE I")
        require(typeReply.first == 200) { "TYPE I fallito: ${typeReply.second}" }

        val passiveReply = sendRideScopeFtpCommand(writer, reader, "PASV")
        require(passiveReply.first == 227) { "PASV fallito: ${passiveReply.second}" }
        val passiveEndpoint = parseRideScopePassiveEndpoint(passiveReply.second, normalizedHost)

        createRideScopePassiveDataSocket(config, passiveEndpoint).use { dataSocket ->

            val uploadReply = sendRideScopeFtpCommand(
                writer,
                reader,
                "STOR ${config.remotePath(remoteFileName)}",
            )
            require(uploadReply.first == 125 || uploadReply.first == 150) {
                "Upload FTP fallito: ${uploadReply.second}"
            }

            BufferedInputStream(localFile.inputStream()).use { input ->
                BufferedOutputStream(dataSocket.getOutputStream()).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            val completionReply = readRideScopeFtpReply(reader)
            require(completionReply.first == 226 || completionReply.first == 250) {
                "Completamento upload FTP fallito: ${completionReply.second}"
            }
        }

        runCatching { sendRideScopeFtpCommand(writer, reader, "QUIT") }
    }
}

fun latestRideScopeApkFile(apkRoot: File): File? {
    if (!apkRoot.exists()) {
        return null
    }
    return apkRoot.walkTopDown()
        .filter { file ->
            file.isFile &&
                file.extension.equals("apk", ignoreCase = true) &&
                file.name.equals(rideScopeCanonicalApkName, ignoreCase = true)
        }
        .maxByOrNull(File::lastModified)
}

val generateRideScopeManifest by tasks.registering {
    val manifestJson = """
        {
          "ridescope": {
            "build": "${rideScopeAppBuildMetadata.versionCode}",
            "version_code": ${rideScopeAppBuildMetadata.versionCode},
            "version_name": "${rideScopeAppBuildMetadata.build}",
            "timestamp": "${rideScopeAppBuildMetadata.timestamp}",
            "protocol": "${rideScopeAppBuildMetadata.protocol}"
          }
        }
    """.trimIndent()

    inputs.property("ridescopeBuild", rideScopeAppBuildMetadata.versionCode)
    inputs.property("ridescopeVersionName", rideScopeAppBuildMetadata.build)
    inputs.property("ridescopeTimestamp", rideScopeAppBuildMetadata.timestamp)
    inputs.property("ridescopeProtocol", rideScopeAppBuildMetadata.protocol)
    outputs.files(rideScopeManifestAssetFile, rideScopeManifestOutputFile)

    doLast {
        val assetFile = rideScopeManifestAssetFile.get().asFile
        assetFile.parentFile.mkdirs()
        assetFile.writeText(manifestJson)

        val outputFile = rideScopeManifestOutputFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(manifestJson)
    }
}

android {
    namespace = "com.example.ridescope"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.netstrike.ridescope"
        minSdk = 24
        targetSdk = 36
        versionCode = rideScopeAppBuildMetadata.versionCode
        versionName = rideScopeAppBuildMetadata.build

        buildConfigField("String", "RIDESCOPE_BUILD", "\"${rideScopeAppBuildMetadata.build}\"")
        buildConfigField("String", "RIDESCOPE_BUILD_TIMESTAMP", "\"${rideScopeAppBuildMetadata.timestamp}\"")
        buildConfigField("String", "RIDESCOPE_PROTOCOL", "\"${rideScopeAppBuildMetadata.protocol}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            debugSymbolLevel = "SYMBOL_TABLE"
        }
    }

    signingConfigs {
        if (rideScopeReleaseSigningEnabled) {
            create("ridescopeRelease") {
                storeFile = file(rideScopeReleaseStoreFile!!)
                storePassword = rideScopeReleaseStorePassword
                keyAlias = rideScopeReleaseKeyAlias
                keyPassword = rideScopeReleaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (rideScopeReleaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("ridescopeRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.add(rideScopeGeneratedAssetsDir.get().asFile.absolutePath)
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateRideScopeManifest)
}

val syncRideScopeApkName by tasks.registering {
    doLast {
        val apkRoot = layout.buildDirectory.dir("outputs/apk").get().asFile
        if (!apkRoot.exists()) {
            return@doLast
        }

        apkRoot.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.extension.equals("apk", ignoreCase = true) &&
                    !file.name.equals(rideScopeCanonicalApkName, ignoreCase = true)
            }
            .forEach { apkFile ->
                apkFile.copyTo(
                    target = apkFile.parentFile.resolve(rideScopeCanonicalApkName),
                    overwrite = true,
                )
            }
    }
}

val publishRideScopeArtifacts by tasks.registering {
    dependsOn(generateRideScopeManifest, syncRideScopeApkName)

    doLast {
        if (!rideScopeLegacyAppFtpPublishEnabled) {
            logger.lifecycle("Publish FTP RideScope app disabilitato: l'app viene distribuita tramite Google Play")
            return@doLast
        }
        if (rideScopeSkipFtpPublish) {
            logger.lifecycle("Publish FTP RideScope saltato da RIDESCOPE_SKIP_APP_FTP_PUBLISH=1")
            return@doLast
        }
        if (rideScopeAppFtpUsername.isBlank() || rideScopeAppFtpPassword.isBlank()) {
            logger.lifecycle("Publish FTP RideScope saltato: RIDESCOPE_APP_FTP_USER/RIDESCOPE_APP_FTP_PASSWORD non configurate")
            return@doLast
        }

        val apkFile = latestRideScopeApkFile(rideScopeApkOutputRoot.get().asFile)
            ?: error("APK canonico ridescope.apk non trovato negli output")
        val manifestFile = rideScopeManifestOutputFile.get().asFile
        require(manifestFile.isFile) { "manifest.json non trovato: ${manifestFile.absolutePath}" }

        uploadRideScopeFileOverFtp(
            config = rideScopeAppFtpConfig,
            localFile = apkFile,
            remoteFileName = rideScopeCanonicalApkName,
        )
        uploadRideScopeFileOverFtp(
            config = rideScopeAppFtpConfig,
            localFile = manifestFile,
            remoteFileName = "manifest.json",
        )
    }
}

// Aggancia sync e publish a tutti i task che producono o installano un APK.
// "package*" è il task che scrive fisicamente l'APK — sempre eseguito da Android Studio,
// dal deploy script e dalla CI, anche quando assemble/install vengono saltati.
tasks.configureEach {
    if (
        name.startsWith("assemble", ignoreCase = true) ||
        name.startsWith("install", ignoreCase = true) ||
        Regex("^package[A-Z].*(Debug|Release)$").matches(name)
    ) {
        finalizedBy(syncRideScopeApkName)
        if (rideScopeLegacyAppFtpPublishEnabled) {
            finalizedBy(publishRideScopeArtifacts)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.google.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.garmin.fit)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
}
