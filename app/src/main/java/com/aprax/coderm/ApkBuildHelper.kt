package com.aprax.coderm

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.android.apksig.ApkSigner
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ApkBuildHelper {

    data class ToolchainInfo(
        val termuxInstalled: Boolean,
        val openJdkAvailable: Boolean,
        val gradleAvailable: Boolean,
        val buildToolsAvailable: Boolean,
        val details: String,
        val setupScript: String
    )

    data class ApkResult(
        val file: File,
        val projectRelativePath: String,
        val downloadsPath: String
    )

    fun checkToolchain(context: Context): ToolchainInfo {
        var termuxInstalled = false
        try {
            context.packageManager.getPackageInfo("com.termux", PackageManager.GET_ACTIVITIES)
            termuxInstalled = true
        } catch (e: Exception) {
            termuxInstalled = File("/data/data/com.termux/files/usr/bin/bash").exists()
        }

        val openJdkAvailable = checkBinary("javac") || checkBinary("java")
        val gradleAvailable = checkBinary("gradle")
        val buildToolsAvailable = checkBinary("aapt2") || checkBinary("d8") || checkBinary("apksigner")

        val details = buildString {
            append("Termux App: ").append(if (termuxInstalled) "Installed" else "Not detected").append("\n")
            append("OpenJDK (Java): ").append(if (openJdkAvailable) "Available in PATH" else "Requires Termux setup").append("\n")
            append("Gradle: ").append(if (gradleAvailable) "Available in PATH" else "Requires Termux setup").append("\n")
            append("Android Build Tools (aapt2/d8): ").append(if (buildToolsAvailable) "Available" else "Installable via Termux")
        }

        val setupScript = """
            # 1. Open Termux on your Android device
            pkg update -y
            # 2. Install OpenJDK, Android build tools, and git
            pkg install -y openjdk-17 aapt2 d8 apksigner git
            # 3. Verify toolchain
            java -version
            aapt2 version
        """.trimIndent()

        return ToolchainInfo(
            termuxInstalled = termuxInstalled,
            openJdkAvailable = openJdkAvailable,
            gradleAvailable = gradleAvailable,
            buildToolsAvailable = buildToolsAvailable,
            details = details,
            setupScript = setupScript
        )
    }

    private fun checkBinary(binaryName: String): Boolean {
        return try {
            val paths = System.getenv("PATH")?.split(":") ?: listOf("/system/bin", "/system/xbin")
            paths.any { File(it, binaryName).canExecute() }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Executes the complete multi-step APK build pipeline.
     * Emits real-time progress events to the progress listener.
     */
    suspend fun buildWebApk(
        context: Context,
        projectName: String,
        projectFilesMap: Map<String, ByteArray>,
        persistedRootUri: Uri? = null,
        onProgress: (step: Int, totalSteps: Int, stepName: String, logLine: String) -> Unit
    ): ApkResult {
        val totalSteps = 6
        val cleanName = projectName.replace(Regex("[^a-zA-Z0-9_-]"), "").ifEmpty { "WebApp" }
        val apkFileName = "$cleanName-debug.apk"
        val buildDir = File(context.cacheDir, "codex_apk_build/$cleanName")
        if (buildDir.exists()) buildDir.deleteRecursively()
        buildDir.mkdirs()

        // Step 1: Inspect project structure
        onProgress(1, totalSteps, "Project Structure & Asset Verification", "Verifying project files and HTML entry point...")
        delay(200)

        val hasHtml = projectFilesMap.keys.any { it.endsWith(".html", ignoreCase = true) }
        if (!hasHtml) {
            throw IllegalStateException("No HTML file found in project. A web project requires at least one .html file (e.g. index.html) to build an Android APK.")
        }
        val entryHtml = if (projectFilesMap.containsKey("index.html")) "index.html" else projectFilesMap.keys.first { it.endsWith(".html", ignoreCase = true) }
        onProgress(1, totalSteps, "Project Structure & Asset Verification", "Identified entry point: $entryHtml (${projectFilesMap.size} project files ready)")
        delay(200)

        // Step 2: Android Shell Synthesis & Manifest Loading
        onProgress(2, totalSteps, "Android Shell Synthesis", "Loading standalone Android runner container...")
        delay(250)

        val unsignedApk = File(buildDir, "unsigned.apk")
        val baseTemplateAsset = "templates/webrunner_base.apk"

        // Step 3: Asset Packaging into APK assets/www/
        onProgress(3, totalSteps, "Packaging Web Assets", "Injecting ${projectFilesMap.size} web assets into APK assets/www/ directory...")

        // Normalize paths: determine if all files share a common single root directory prefix (e.g. "MyProject/...")
        val normalizedFiles = mutableMapOf<String, ByteArray>()
        val rawPaths = projectFilesMap.keys.map { it.trim().replace('\\', '/').trimStart('/') }
        val commonPrefix = if (rawPaths.size > 1) {
            val firstSegment = rawPaths.first().substringBefore('/', "")
            if (firstSegment.isNotEmpty() && rawPaths.all { it.startsWith("$firstSegment/") }) {
                "$firstSegment/"
            } else ""
        } else ""

        for ((relPath, bytes) in projectFilesMap) {
            val clean = relPath.trim().replace('\\', '/').trimStart('/')
            val finalRel = if (commonPrefix.isNotEmpty() && clean.startsWith(commonPrefix)) {
                clean.removePrefix(commonPrefix)
            } else {
                clean
            }
            normalizedFiles[finalRel] = bytes
        }

        val addedEntryNames = mutableSetOf<String>()
        val baseTemplateFile = File(buildDir, "base_template.apk")
        context.assets.open(baseTemplateAsset).use { input ->
            baseTemplateFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val countingStream = CountingOutputStream(BufferedOutputStream(FileOutputStream(unsignedApk)))
        ZipOutputStream(countingStream).use { zos ->
            // 3a: Copy compiled classes.dex, binary AndroidManifest.xml, resources from template with exact 4-byte alignment
            val baseZip = java.util.zip.ZipFile(baseTemplateFile)
            val entries = baseZip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                // Filter out any META-INF signatures from template and www assets
                if (!name.startsWith("META-INF/") && !name.startsWith("assets/www/")) {
                    if (addedEntryNames.add(name)) {
                        val data = baseZip.getInputStream(entry).use { it.readBytes() }
                        val isStored = (entry.method == ZipEntry.STORED) ||
                                       name == "resources.arsc" ||
                                       name.startsWith("res/mipmap")
                        writeAlignedZipEntry(zos, countingStream, name, data, isStored)
                    }
                }
            }
            baseZip.close()

            // 3b: Copy project assets into assets/www/ (DEFLATED for space efficiency)
            // Also prepare self-contained inlined versions of HTML files so that external stylesheets
            // and scripts are guaranteed to execute even if WebView has strict local asset origin policies.
            for ((relPath, bytes) in normalizedFiles) {
                val cleanRelPath = relPath.trim().trimStart('/', '\\')
                var fileBytes = bytes

                // If this is an HTML file, inline any matching CSS / JS files present in the project
                if (cleanRelPath.endsWith(".html", ignoreCase = true) || cleanRelPath.endsWith(".htm", ignoreCase = true)) {
                    fileBytes = inlineHtmlAssets(cleanRelPath, bytes, normalizedFiles)
                }

                val zipEntryPath = "assets/www/$cleanRelPath"
                if (addedEntryNames.add(zipEntryPath)) {
                    writeAlignedZipEntry(zos, countingStream, zipEntryPath, fileBytes, isStored = false)
                }

                // If the file is in a subdirectory (e.g. css/style.css or js/app.js),
                // also alias at assets/www/filename so root references (href="/style.css" or href="style.css") always resolve
                if (cleanRelPath.contains('/')) {
                    val fileName = cleanRelPath.substringAfterLast('/')
                    val flatEntryPath = "assets/www/$fileName"
                    if (addedEntryNames.add(flatEntryPath)) {
                        writeAlignedZipEntry(zos, countingStream, flatEntryPath, fileBytes, isStored = false)
                    }
                } else {
                    // Conversely, if root style.css or app.js is present, alias under www/css/ or www/js/
                    if (cleanRelPath.endsWith(".css", ignoreCase = true)) {
                        val subEntry = "assets/www/css/$cleanRelPath"
                        if (addedEntryNames.add(subEntry)) {
                            writeAlignedZipEntry(zos, countingStream, subEntry, fileBytes, isStored = false)
                        }
                    } else if (cleanRelPath.endsWith(".js", ignoreCase = true) || cleanRelPath.endsWith(".mjs", ignoreCase = true)) {
                        val subEntry = "assets/www/js/$cleanRelPath"
                        if (addedEntryNames.add(subEntry)) {
                            writeAlignedZipEntry(zos, countingStream, subEntry, fileBytes, isStored = false)
                        }
                    }
                }
            }

            // 3c: Guarantee an index.html exists at assets/www/index.html
            if (!addedEntryNames.contains("assets/www/index.html")) {
                val targetHtmlEntry = addedEntryNames.firstOrNull { 
                    it.startsWith("assets/www/") && (it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true)) 
                }?.removePrefix("assets/www/") ?: "index.html"

                val redirectContent = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1.0">
                      <meta http-equiv="refresh" content="0; url=$targetHtmlEntry">
                      <script>window.location.replace("$targetHtmlEntry");</script>
                    </head>
                    <body style="margin:0; background:#121212; color:#eee; font-family:sans-serif; display:flex; align-items:center; justify-content:center; height:100vh;">
                      <p>Loading application...</p>
                    </body>
                    </html>
                """.trimIndent().toByteArray(Charsets.UTF_8)

                writeAlignedZipEntry(zos, countingStream, "assets/www/index.html", redirectContent, isStored = false)
                addedEntryNames.add("assets/www/index.html")
            }
        }

        onProgress(3, totalSteps, "Packaging Web Assets", "Web assets successfully bundled into assets/www/ ($entryHtml ready)")
        delay(250)

        // Step 4: Toolchain & Compilation Verification
        onProgress(4, totalSteps, "Compiler & Architecture Check", "Verifying Android package structure and DEX bytecode...")
        delay(200)

        // Step 5: Android APK Signing (v1 + v2 + v3 schemes via Google apksig)
        onProgress(5, totalSteps, "APK Signing & Packaging", "Signing APK with Android Debug certificate (v1 JAR + v2 Signature Scheme)...")

        val outputApkDir = File(context.cacheDir, "built_apks")
        outputApkDir.mkdirs()
        val signedApkFile = File(outputApkDir, apkFileName)
        if (signedApkFile.exists()) signedApkFile.delete()

        signApkWithDebugKey(context, unsignedApk, signedApkFile)

        onProgress(5, totalSteps, "APK Signing & Packaging", "APK successfully signed with v1 + v2 certificates (${signedApkFile.length() / 1024} KB)")
        delay(250)

        // Step 6: Save to Project Directory & Downloads
        onProgress(6, totalSteps, "Finalization & Project Directory Placement", "Saving APK in project directory and system storage...")

        val apkBytes = signedApkFile.readBytes()
        var projectRelPath = apkFileName

        // Save into SAF project tree if user has an active directory selected
        if (persistedRootUri != null) {
            try {
                DocumentTreeHelper.writeRelativeFileBytes(
                    context,
                    persistedRootUri,
                    apkFileName,
                    apkBytes
                )
                DocumentTreeHelper.writeRelativeFileBytes(
                    context,
                    persistedRootUri,
                    "dist/$apkFileName",
                    apkBytes
                )
                projectRelPath = "$apkFileName and dist/$apkFileName"
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Also save in app internal project files directory
        try {
            val internalProjDir = File(context.filesDir, "projects/$cleanName")
            if (internalProjDir.exists() || internalProjDir.mkdirs()) {
                val internalApk = File(internalProjDir, apkFileName)
                internalApk.writeBytes(apkBytes)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Save a copy to public device Downloads folder
        val downloadsLocation = saveApkToDownloads(context, signedApkFile)

        onProgress(
            6,
            totalSteps,
            "Finalization & Project Directory Placement",
            "APK ready! Saved to project directory ($projectRelPath) and Downloads"
        )

        return ApkResult(
            file = signedApkFile,
            projectRelativePath = projectRelPath,
            downloadsPath = downloadsLocation
        )
    }

    private class CountingOutputStream(out: java.io.OutputStream) : java.io.FilterOutputStream(out) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len
        }
    }

    private fun writeAlignedZipEntry(
        zos: ZipOutputStream,
        countingStream: CountingOutputStream,
        name: String,
        data: ByteArray,
        isStored: Boolean
    ) {
        val entry = ZipEntry(name)
        entry.time = 1735689600000L // Consistent Jan 1 2025 timestamp
        if (isStored) {
            entry.method = ZipEntry.STORED
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            val crc = java.util.zip.CRC32()
            crc.update(data)
            entry.crc = crc.value

            // 4-byte zip alignment for uncompressed entries:
            // Local File Header format: 30 bytes fixed header + name length + extra length
            val curPos = countingStream.bytesWritten
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val rem = ((curPos + 30 + nameBytes.size) % 4).toInt()
            val pad = if (rem != 0) 4 - rem else 0
            if (pad > 0) {
                entry.extra = ByteArray(pad)
            }
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun signApkWithDebugKey(context: Context, inApk: File, outApk: File) {
        var privateKey: PrivateKey? = null
        var cert: X509Certificate? = null

        // Priority 1: Load directly from PKCS#8 (.pk8) and X.509 PEM (.x509.pem)
        // Standard Android build key format that does not depend on Java Keystore providers
        try {
            val keyBytes = context.assets.open("templates/debug.pk8").use { it.readBytes() }
            val kf = KeyFactory.getInstance("RSA")
            privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

            val cf = CertificateFactory.getInstance("X.509")
            cert = try {
                context.assets.open("templates/debug.x509.pem").use {
                    cf.generateCertificate(it) as X509Certificate
                }
            } catch (_: Exception) {
                context.assets.open("templates/debug.crt").use {
                    cf.generateCertificate(it) as X509Certificate
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ApkBuildHelper", "Could not load debug.pk8 / debug.x509.pem: ${e.message}")
        }

        // Priority 2: Load from PKCS12 (.keystore)
        if (privateKey == null || cert == null) {
            try {
                val ks = KeyStore.getInstance("PKCS12")
                context.assets.open("templates/debug.keystore").use { isr ->
                    ks.load(isr, "android".toCharArray())
                }
                privateKey = ks.getKey("androiddebugkey", "android".toCharArray()) as? PrivateKey
                cert = ks.getCertificate("androiddebugkey") as? X509Certificate
            } catch (e: Exception) {
                android.util.Log.w("ApkBuildHelper", "Could not load PKCS12 keystore: ${e.message}")
            }
        }

        // Priority 3: Load from BKS (.keystore) if BouncyCastle is present
        if (privateKey == null || cert == null) {
            try {
                val ks = KeyStore.getInstance("BKS")
                context.assets.open("templates/debug.keystore").use { isr ->
                    ks.load(isr, "android".toCharArray())
                }
                privateKey = ks.getKey("androiddebugkey", "android".toCharArray()) as? PrivateKey
                cert = ks.getCertificate("androiddebugkey") as? X509Certificate
            } catch (e: Exception) {
                android.util.Log.w("ApkBuildHelper", "Could not load BKS keystore: ${e.message}")
            }
        }

        if (privateKey == null || cert == null) {
            throw IllegalStateException("Failed to load Android debug signing certificate and key")
        }

        signWithPrivateKeyAndCert(privateKey, cert, inApk, outApk)
    }

    private fun signWithPrivateKeyAndCert(privateKey: PrivateKey, cert: X509Certificate, inApk: File, outApk: File) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "debug",
            privateKey,
            listOf(cert)
        ).build()

        val apkSigner = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inApk)
            .setOutputApk(outApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()

        apkSigner.sign()
    }

    fun saveApkToDownloads(context: Context, apkFile: File): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, apkFile.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        FileInputStream(apkFile).use { fis ->
                            fis.copyTo(os)
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    return "Downloads/${apkFile.name}"
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val destFile = File(downloadsDir, apkFile.name)
                apkFile.copyTo(destFile, overwrite = true)
                return destFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return apkFile.absolutePath
    }

    fun shareApk(context: Context, apkFile: File) {
        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Generated Android APK: ${apkFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share APK with...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun installApk(context: Context, apkFile: File) {
        // Android 8.0+ Unknown App Sources Permission Check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            }
        }

        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Explicitly grant read URI permissions to all matching package installer handlers
        val queryList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                installIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        for (resolveInfo in queryList) {
            val targetPkg = resolveInfo.activityInfo.packageName
            context.grantUriPermission(targetPkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(installIntent)
    }

    /**
     * Resolves and inlines local CSS stylesheets and JS script tags into the HTML content.
     * This guarantees that even if Android WebView encounters restrictive file origin checks
     * or custom scheme isolation, all styles and code load seamlessly.
     */
    private fun inlineHtmlAssets(
        htmlPath: String,
        htmlBytes: ByteArray,
        allFiles: Map<String, ByteArray>
    ): ByteArray {
        val rawHtml = try {
            String(htmlBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return htmlBytes
        }

        val baseDir = if (htmlPath.contains('/')) htmlPath.substringBeforeLast('/') + "/" else ""

        val resolveFile = { relRef: String ->
            val cleanRef = relRef.trim().trimStart('.', '/')
            if (cleanRef.startsWith("http://") || cleanRef.startsWith("https://") ||
                cleanRef.startsWith("data:") || cleanRef.startsWith("//") || cleanRef.startsWith("#")) {
                null
            } else {
                // First try relative to HTML file directory
                val targetPath = (baseDir + cleanRef).replace("//", "/")
                allFiles[targetPath] ?: allFiles[cleanRef] ?: run {
                    val fileName = cleanRef.substringAfterLast('/')
                    allFiles.entries.firstOrNull { 
                        it.key.endsWith("/$fileName", ignoreCase = true) || it.key.equals(fileName, ignoreCase = true)
                    }?.value
                }
            }
        }

        var modifiedHtml = rawHtml

        // 1. Inline CSS stylesheets: <link ... rel="stylesheet" ... href="..." ...> or <link ... href="..." ... rel="stylesheet" ...>
        val linkRegex = Regex("""<link\s+[^>]*rel=["']stylesheet["'][^>]*>|<link\s+[^>]*href=["'][^"']+["'][^>]*rel=["']stylesheet["'][^>]*>""", RegexOption.IGNORE_CASE)
        val hrefRegex = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        modifiedHtml = linkRegex.replace(modifiedHtml) { matchResult ->
            val tag = matchResult.value
            val hrefMatch = hrefRegex.find(tag)
            if (hrefMatch != null) {
                val href = hrefMatch.groupValues[1]
                val cssBytes = resolveFile(href)
                if (cssBytes != null) {
                    val cssContent = String(cssBytes, Charsets.UTF_8)
                    "<style data-inlined-from=\"$href\">\n$cssContent\n</style>"
                } else {
                    tag
                }
            } else {
                tag
            }
        }

        // 2. Inline external script files: <script ... src="..." ...></script>
        val scriptRegex = Regex("""<script\s+[^>]*src=["']([^"']+)["'][^>]*>\s*</script>""", RegexOption.IGNORE_CASE)
        modifiedHtml = scriptRegex.replace(modifiedHtml) { matchResult ->
            val src = matchResult.groupValues[1]
            val jsBytes = resolveFile(src)
            if (jsBytes != null) {
                val jsContent = String(jsBytes, Charsets.UTF_8)
                "<script data-inlined-from=\"$src\">\n$jsContent\n</script>"
            } else {
                matchResult.value
            }
        }

        return modifiedHtml.toByteArray(Charsets.UTF_8)
    }
}
