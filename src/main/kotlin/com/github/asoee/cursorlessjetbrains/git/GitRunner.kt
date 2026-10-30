package com.github.asoee.cursorlessjetbrains.git

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs git (from the PATH) in a file's repository, for the git actions. A plain process rather than the IDE's
 * process helpers, which refuse to run synchronously on the EDT, where IDE actions run.
 */
class GitRunner private constructor(val root: File) {

    class GitException(message: String) : RuntimeException(message)

    data class Result(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
        val text: String get() = String(stdout, Charsets.UTF_8)
    }

    companion object {
        /** The repository containing [file], or a GitException if there's none */
        fun forFile(file: File): GitRunner {
            val result = run(file.parentFile, listOf("rev-parse", "--show-toplevel"))
            if (result.exitCode != 0) {
                throw GitException("${file.name} isn't in a git repository")
            }
            return GitRunner(File(result.text.trim()))
        }

        private fun run(directory: File, args: List<String>, stdin: ByteArray? = null): Result {
            val process = try {
                ProcessBuilder(listOf("git") + args).directory(directory).start()
            } catch (e: java.io.IOException) {
                throw GitException("Couldn't run git: ${e.message}")
            }
            process.outputStream.use { input -> stdin?.let { input.write(it) } }
            val stdout = process.inputStream.readBytes()
            val stderr = process.errorStream.bufferedReader().readText()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw GitException("git ${args.firstOrNull()} timed out")
            }
            return Result(process.exitValue(), stdout, stderr.trim())
        }
    }

    /** [file]'s path relative to the repository, with forward slashes, as git expects */
    fun pathOf(file: File): String =
        root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')

    fun run(vararg args: String, stdin: ByteArray? = null): Result = run(root, args.toList(), stdin)

    /** Runs git, throwing its error message if it fails */
    fun check(vararg args: String, stdin: ByteArray? = null): Result {
        val result = run(*args, stdin = stdin)
        if (result.exitCode != 0) {
            throw GitException(result.stderr.ifEmpty { "git ${args.first()} failed" })
        }
        return result
    }

    /** The blob at [spec] (e.g. ":path" for the index, "HEAD:path"), or null if there is none */
    fun blob(spec: String): ByteArray? {
        val result = run("cat-file", "blob", spec)
        return if (result.exitCode == 0) result.stdout else null
    }

    /** Puts [content] in the index for [path], keeping its file mode */
    fun writeIndex(path: String, content: ByteArray) {
        val sha = check("hash-object", "-w", "--no-filters", "--stdin", stdin = content).text.trim()
        val mode = check("ls-files", "-s", "--", path).text.trim().substringBefore(' ').ifEmpty { "100644" }
        check("update-index", "--add", "--cacheinfo", "$mode,$sha,$path")
    }
}
