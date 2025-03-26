package org.tera201.vcsmanager.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

class SimpleCommandExecutor {
    private var envVars: MutableList<EnvironmentVar> = mutableListOf()
    private var inheritEnv = false

    /**
     * Should child inherit parent's environment vars?
     */
    fun inheritEnv(inherit: Boolean): SimpleCommandExecutor = apply {
        inheritEnv = inherit
    }

    /**
     * Add an environment variable to the child's environment.
     */
    fun setEnvironmentVar(name: String, value: String): SimpleCommandExecutor = apply {
        envVars.add(EnvironmentVar(name, value))
    }

    /**
     * Clear the child's environment variables.
     */
    fun clearEnvironmentVars(): SimpleCommandExecutor = apply {
        envVars.clear()
    }

    /**
     * Execute a command from a specified working directory.
     */
    fun execute(command: String, workDir: String?): String {
        log.debug("Executing command <$command> in workDir $workDir")
        val wd = workDir?.let { File(it) }
        val proc = try {
            Runtime.getRuntime().exec(command, envTokens, wd)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        return proc.inputStream.bufferedReader().use { it.readText() }
    }

    private val envTokens: Array<String?>?
        get() = if (inheritEnv) null else envVars.map { "${it.name}=${it.value}" }.toTypedArray()

    companion object {
        private val log: Logger = LoggerFactory.getLogger(SimpleCommandExecutor::class.java)
    }
}

internal data class EnvironmentVar(val name: String, val value: String)