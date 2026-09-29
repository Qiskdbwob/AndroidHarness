package com.androidharness.app.data.env

import android.content.Context
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.data.SettingsRepository
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

/**
 * Manages SSH connections to a local Termux sshd daemon (default localhost:8022).
 * Allows agent shell executions, git commit, and git push to run with Termux's
 * full toolchain and permissions, bypassing Android app-sandbox restrictions.
 */
class TermuxSshManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val keys: KeyStoreManager,
) {

    private class TermuxUserInfo(private val password: String?) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = password
        override fun promptPassword(message: String?): Boolean = !password.isNullOrBlank()
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            if (password == null) return null
            val size = prompt?.size ?: 1
            return Array(size) { password }
        }
    }

    private suspend fun createSession(
        host: String,
        port: Int,
        user: String,
        connectTimeoutMs: Int = 10_000,
    ): Pair<JSch, Session> = withContext(Dispatchers.IO) {
        val jsch = JSch()
        val privateKey = keys.termuxSshKey()
        if (!privateKey.isNullOrBlank()) {
            jsch.addIdentity("termux_key", privateKey.toByteArray(Charsets.UTF_8), null, null)
        }

        val session = jsch.getSession(user.ifBlank { AppSettings.DEFAULT_TERMUX_SSH_USER }, host.ifBlank { AppSettings.DEFAULT_TERMUX_SSH_HOST }, port)
        val password = keys.termuxSshPassword()
        if (!password.isNullOrBlank()) {
            session.setPassword(password)
        }
        session.userInfo = TermuxUserInfo(password)

        val config = Properties()
        config["StrictHostKeyChecking"] = "no"
        config["PreferredAuthentications"] = "publickey,password,keyboard-interactive"
        session.setConfig(config)
        session.timeout = connectTimeoutMs
        session.connect(connectTimeoutMs)
        Pair(jsch, session)
    }

    /**
     * Tests the SSH connection to Termux and inspects the environment (system uname + git version).
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val appSettings = settings.settings.first()
            val host = appSettings.termuxSshHost
            val port = appSettings.termuxSshPort
            val user = appSettings.termuxSshUser

            val (_, session) = createSession(host, port, user, connectTimeoutMs = 8_000)
            try {
                val channel = session.openChannel("exec") as ChannelExec
                val testCmd = "export PATH=\$PATH:/data/data/com.termux/files/usr/bin:\$HOME/bin && " +
                    "echo \"=== Termux SSH OK ===\" && uname -a && " +
                    "(git --version || echo \"git: not installed in Termux\")"
                channel.setCommand(testCmd)

                val outStream = ByteArrayOutputStream()
                val errStream = ByteArrayOutputStream()
                channel.setOutputStream(outStream)
                channel.setErrStream(errStream)

                channel.connect(5_000)
                val deadline = System.currentTimeMillis() + 8_000
                while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }

                val output = outStream.toString(Charsets.UTF_8.name()).trim()
                val error = errStream.toString(Charsets.UTF_8.name()).trim()
                channel.disconnect()

                if (output.isNotEmpty()) {
                    output
                } else if (error.isNotEmpty()) {
                    "Connected, but returned stderr: $error"
                } else {
                    "Connected successfully to Termux sshd at $host:$port!"
                }
            } finally {
                session.disconnect()
            }
        }
    }

    /**
     * Executes a command inside the Termux environment over SSH.
     */
    suspend fun run(
        command: String,
        cwd: File,
        timeoutMs: Int,
        maxOutput: Int,
    ): ShellRunResult = withContext(Dispatchers.IO) {
        val appSettings = settings.settings.first()
        val host = appSettings.termuxSshHost
        val port = appSettings.termuxSshPort
        val user = appSettings.termuxSshUser

        val targetDir = cwd.absolutePath.replace("'", "'\\''")
        // Ensure Termux usr/bin is on PATH even in non-interactive SSH sessions
        val wrappedCmd = "export PATH=\$PATH:/data/data/com.termux/files/usr/bin:\$HOME/bin && " +
            "if [ -d '$targetDir' ]; then cd '$targetDir'; fi && ($command)"

        val sessionPair = try {
            createSession(host, port, user, connectTimeoutMs = 10_000)
        } catch (e: Exception) {
            return@withContext ShellRunResult(
                exitCode = -1,
                timedOut = false,
                rawOutput = "",
                rawStderr = "Failed to connect to Termux SSH ($host:$port): ${e.message}\n" +
                    "Please ensure Termux is running with sshd started ('sshd' in Termux) " +
                    "and your host/port/credentials are configured in Settings → Terminal & device.",
                tier = ExecutionTier.TERMUX_SSH,
                note = "[Termux SSH unreachable: please run 'sshd' in Termux]",
            )
        }

        val (_, session) = sessionPair
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(wrappedCmd)

            val outStream = ByteArrayOutputStream()
            val errStream = ByteArrayOutputStream()
            channel.setOutputStream(outStream)
            channel.setErrStream(errStream)

            channel.connect(timeoutMs.coerceAtMost(10_000))

            val deadline = System.currentTimeMillis() + timeoutMs
            var timedOut = false
            while (!channel.isClosed) {
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true
                    break
                }
                Thread.sleep(50)
            }

            val exitCode = if (timedOut) -1 else channel.exitStatus
            channel.disconnect()

            val rawOut = outStream.toByteArray()
            val rawErr = errStream.toByteArray()

            val outStr = if (rawOut.size > maxOutput) {
                String(rawOut, 0, maxOutput, Charsets.UTF_8) + "\n...[truncated output]"
            } else {
                String(rawOut, Charsets.UTF_8)
            }

            val errStr = if (rawErr.size > maxOutput) {
                String(rawErr, 0, maxOutput, Charsets.UTF_8) + "\n...[truncated stderr]"
            } else {
                String(rawErr, Charsets.UTF_8)
            }

            ShellRunResult(
                exitCode = exitCode,
                timedOut = timedOut,
                rawOutput = outStr,
                rawStderr = errStr,
                tier = ExecutionTier.TERMUX_SSH,
                note = "[executed via Termux SSH bridge at $host:$port]",
            )
        } catch (e: Exception) {
            ShellRunResult(
                exitCode = -1,
                timedOut = false,
                rawOutput = "",
                rawStderr = "Error running command via Termux SSH: ${e.message}",
                tier = ExecutionTier.TERMUX_SSH,
                note = "[Termux SSH execution failed]",
            )
        } finally {
            session.disconnect()
        }
    }
}
