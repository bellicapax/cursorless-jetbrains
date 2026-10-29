package com.github.asoee.cursorlessjetbrains.commands

import com.github.asoee.cursorlessjetbrains.services.TalonProjectService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Talon's "cursorless stats" (cursorless.analyzeCommandHistory): opens a report of the recorded commands */
class AnalyzeCommandHistoryCommand(project: Project) : VcCommand(project) {

    companion object {
        fun fromArgs(project: Project, @Suppress("UNUSED_PARAMETER") args: List<String>): AnalyzeCommandHistoryCommand {
            return AnalyzeCommandHistoryCommand(project)
        }
    }

    override fun executionMode(): ExecutionMode {
        return ExecutionMode.BACKGROUND
    }

    override fun execute(context: CommandContext): String {
        project.service<TalonProjectService>().cursorlessEngine.analyzeCommandHistory()
        return "OK"
    }
}
