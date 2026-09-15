package com.androidharness.app.data.env

import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

internal const val CODEGRAPH_VERSION_MARKER = ".harness-codegraph-version"

internal object CodeGraphCommands {
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun install(prefix: File): String =
        "npm install --global --prefix ${quote(prefix.absolutePath)} --no-audit --no-fund @colbymchenry/codegraph@latest"

    fun uninstall(prefix: File): String =
        "npm uninstall --global --prefix ${quote(prefix.absolutePath)} --ignore-scripts @colbymchenry/codegraph"

    const val version = "codegraph --no-color version"
    const val init = "codegraph --no-color init --yes ."
    const val sync = "codegraph --no-color sync ."
    const val reindex = "codegraph --no-color index ."
    const val uninit = "codegraph --no-color uninit --force ."

    fun explore(query: String): String = "codegraph --no-color explore ${quote(query)}"

    fun node(name: String?, file: String?, offset: Int?, limit: Int?): String = buildString {
        append("codegraph --no-color node")
        if (!file.isNullOrBlank()) append(" --file ").append(quote(file.trim()))
        if (offset != null) append(" --offset ").append(offset.coerceAtLeast(1))
        if (limit != null) append(" --limit ").append(limit.coerceIn(1, 2_000))
        if (!name.isNullOrBlank()) append(' ').append(quote(name.trim()))
    }

    fun impact(symbol: String, depth: Int): String =
        "codegraph --no-color impact --depth ${depth.coerceIn(1, 10)} ${quote(symbol.trim())}"

    fun affected(files: List<String>, depth: Int): String = buildString {
        append("codegraph --no-color affected --depth ").append(depth.coerceIn(1, 10))
        files.map { it.trim() }.filter { it.isNotEmpty() }.forEach { append(' ').append(quote(it)) }
    }
}

data class CodeGraphState(
    val version: String? = null,
    val busy: Boolean = false,
    val action: String? = null,
    val message: String? = null,
    val failed: Boolean = false,
)

data class CodeGraphCommandResult(val ok: Boolean, val output: String)

class CodeGraphManager(
    private val linuxEnv: LinuxEnvironmentManager,
    private val shellRouter: ShellTierRouter,
) {
    private val marker get() = File(linuxEnv.prefix, CODEGRAPH_VERSION_MARKER)
    private val binary get() = File(linuxEnv.prefix, "bin/codegraph")
    private val home get() = File(linuxEnv.prefix, "home").apply { mkdirs() }

    private val _state = MutableStateFlow(CodeGraphState(version = markerVersion()))
    val state: StateFlow<CodeGraphState> = _state

    fun isIndexed(workspace: WorkspaceFs?): Boolean =
        workspace?.shellRoot?.resolve(".codegraph")?.isDirectory == true

    fun isAvailable(workspace: WorkspaceFs): Boolean =
        linuxEnv.isReady && binary.exists() && isIndexed(workspace)

    suspend fun refresh(): CodeGraphState {
        if (!binary.exists() || !linuxEnv.isReady) {
            marker.delete()
            val next = CodeGraphState()
            _state.value = next
            return next
        }
        val result = run(CodeGraphCommands.version, home, 30_000, 4_000)
        val version = if (result.ok) parseVersion(result.output) else null
        if (version != null) marker.writeText(version) else marker.delete()
        val next = CodeGraphState(
            version = version,
            message = if (result.ok) null else result.output.ifBlank { "CodeGraph could not be started." },
            failed = !result.ok,
        )
        _state.value = next
        return next
    }

    suspend fun installOrUpdate(): CodeGraphState = busy("Installing CodeGraph") {
        linuxEnv.install(listOf("bash", "nodejs", "npm"))
        if (!linuxEnv.isReady) {
            return@busy CodeGraphCommandResult(false, "The Linux environment could not be prepared.")
        }
        val installed = run(CodeGraphCommands.install(linuxEnv.prefix), home, 300_000, 30_000)
        if (!installed.ok) return@busy installed
        linuxEnv.ensureShims(force = true)
        val versionResult = run(CodeGraphCommands.version, home, 30_000, 4_000)
        if (!versionResult.ok) return@busy versionResult
        val version = parseVersion(versionResult.output)
            ?: return@busy CodeGraphCommandResult(false, "CodeGraph installed, but its version could not be verified.")
        marker.writeText(version)
        linuxEnv.invalidateExternalToolDeploy()
        CodeGraphCommandResult(true, "CodeGraph $version installed.")
    }

    suspend fun uninstall(): CodeGraphState = busy("Uninstalling CodeGraph") {
        if (!binary.exists()) {
            marker.delete()
            linuxEnv.invalidateExternalToolDeploy()
            return@busy CodeGraphCommandResult(true, "CodeGraph is not installed.")
        }
        val result = run(CodeGraphCommands.uninstall(linuxEnv.prefix), home, 180_000, 20_000)
        if (result.ok) {
            marker.delete()
            linuxEnv.ensureShims(force = true)
            linuxEnv.invalidateExternalToolDeploy()
        }
        result.copy(output = if (result.ok) "CodeGraph uninstalled." else result.output)
    }

    suspend fun initWorkspace(workspace: WorkspaceFs): CodeGraphCommandResult =
        workspaceCommand(workspace, CodeGraphCommands.init, "Initialize CodeGraph")

    suspend fun syncWorkspace(workspace: WorkspaceFs): CodeGraphCommandResult =
        workspaceCommand(workspace, CodeGraphCommands.sync, "Sync CodeGraph")

    suspend fun reindexWorkspace(workspace: WorkspaceFs): CodeGraphCommandResult =
        workspaceCommand(workspace, CodeGraphCommands.reindex, "Re-index CodeGraph", timeoutMs = 300_000)

    suspend fun disableWorkspace(workspace: WorkspaceFs): CodeGraphCommandResult =
        workspaceCommand(workspace, CodeGraphCommands.uninit, "Disable CodeGraph")

    suspend fun explore(workspace: WorkspaceFs, query: String): CodeGraphCommandResult =
        queryWorkspace(workspace, CodeGraphCommands.explore(query))

    suspend fun node(
        workspace: WorkspaceFs,
        name: String?,
        file: String?,
        offset: Int?,
        limit: Int?,
    ): CodeGraphCommandResult = queryWorkspace(workspace, CodeGraphCommands.node(name, file, offset, limit))

    suspend fun impact(workspace: WorkspaceFs, symbol: String, depth: Int): CodeGraphCommandResult =
        queryWorkspace(workspace, CodeGraphCommands.impact(symbol, depth))

    suspend fun affected(workspace: WorkspaceFs, files: List<String>, depth: Int): CodeGraphCommandResult =
        queryWorkspace(workspace, CodeGraphCommands.affected(files, depth))

    private suspend fun queryWorkspace(workspace: WorkspaceFs, command: String): CodeGraphCommandResult {
        val ready = requireIndexed(workspace)
        if (ready != null) return ready
        val root = workspace.shellRoot!!
        val synced = run(CodeGraphCommands.sync, root, 180_000, 12_000)
        if (!synced.ok) return CodeGraphCommandResult(false, "CodeGraph sync failed before the query:\n${synced.output}")
        return run(command, root, 180_000, 80_000)
    }

    private suspend fun workspaceCommand(
        workspace: WorkspaceFs,
        command: String,
        label: String,
        timeoutMs: Int = 180_000,
    ): CodeGraphCommandResult {
        val root = workspace.shellRoot
            ?: return CodeGraphCommandResult(false, "CodeGraph needs a workspace with a real filesystem path.")
        if (_state.value.version == null) refresh()
        if (_state.value.version == null) {
            return CodeGraphCommandResult(false, "Install CodeGraph in Settings → Code intelligence first.")
        }
        _state.value = _state.value.copy(busy = true, action = label, message = null, failed = false)
        val result = run(command, root, timeoutMs, 40_000)
        _state.value = _state.value.copy(
            busy = false,
            action = null,
            message = result.output.ifBlank { if (result.ok) "$label complete." else "$label failed." },
            failed = !result.ok,
        )
        return result
    }

    private suspend fun requireIndexed(workspace: WorkspaceFs): CodeGraphCommandResult? {
        val root = workspace.shellRoot
            ?: return CodeGraphCommandResult(false, "CodeGraph needs a workspace with a real filesystem path.")
        if (_state.value.version == null) refresh()
        if (_state.value.version == null) {
            return CodeGraphCommandResult(false, "CodeGraph is not installed. Open Settings → Code intelligence to install it.")
        }
        if (!root.resolve(".codegraph").isDirectory) {
            return CodeGraphCommandResult(false, "CodeGraph is not enabled for this workspace. Open Settings → Code intelligence and enable it first.")
        }
        return null
    }

    private suspend fun busy(label: String, block: suspend () -> CodeGraphCommandResult): CodeGraphState {
        _state.value = _state.value.copy(busy = true, action = label, message = null, failed = false)
        val result = runCatching { block() }.getOrElse {
            CodeGraphCommandResult(false, it.message ?: "$label failed.")
        }
        val refreshedVersion = if (result.ok) {
            if (binary.exists()) {
                val versionResult = run(CodeGraphCommands.version, home, 30_000, 4_000)
                if (versionResult.ok) parseVersion(versionResult.output) else markerVersion()
            } else null
        } else markerVersion()
        if (refreshedVersion == null) marker.delete() else marker.writeText(refreshedVersion)
        return CodeGraphState(
            version = refreshedVersion,
            busy = false,
            message = result.output.ifBlank { if (result.ok) "$label complete." else "$label failed." },
            failed = !result.ok,
        ).also { _state.value = it }
    }

    private suspend fun run(command: String, cwd: File, timeoutMs: Int, maxOutput: Int): CodeGraphCommandResult {
        val result = shellRouter.run(command, cwd, timeoutMs, maxOutput)
        val output = buildString {
            if (result.rawOutput.isNotBlank()) append(result.rawOutput.trimEnd())
            if (result.rawStderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(result.rawStderr.trimEnd())
            }
            result.note?.let {
                if (isNotEmpty()) append('\n')
                append(it)
            }
        }
        return CodeGraphCommandResult(!result.timedOut && result.exitCode == 0, output)
    }

    private fun markerVersion(): String? =
        runCatching { marker.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()

    private fun parseVersion(output: String): String? =
        Regex("""\b\d+\.\d+\.\d+(?:[-+][A-Za-z0-9._-]+)?\b""").find(output)?.value
}
