package com.github.asoee.cursorlessjetbrains.commands

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.github.asoee.cursorlessjetbrains.util.isInTerminal
import java.awt.Component
import java.awt.Robot
import java.awt.event.KeyEvent


class IDEActionCommand @JvmOverloads constructor(
    project: Project,
    private val actionId: String,
    private val editor: Editor? = null,
) : VcCommand(project) {

    companion object {
        fun fromArgs(project: Project, args: List<String>): IDEActionCommand? {
            val actionId = args[0]
            val action = ActionManager.getInstance().getAction(actionId)
            if (action == null) {
                thisLogger().warn("Action not found: $actionId")
                return null
            }
            return IDEActionCommand(project, actionId)
        }

        val readonlyActions = listOf(IdeActions.ACTION_SHOW_INTENTION_ACTIONS)

        // The terminal's output is an editor, but the generic clipboard actions can't reach the shell
        private val terminalEquivalents = mapOf(
            "EditorPaste" to "Terminal.Paste",
            "EditorCopy" to "Terminal.CopySelectedText",
        )

        // Editing actions the terminal has no action for, so the shell gets the keys instead. In PowerShell, Ctrl+A
        // selects just the current input, so "select all" / "select line" then Backspace clears what was typed.
        private val terminalKeys = mapOf(
            "\$SelectAll" to listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_A),
            "EditorSelectAll" to listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_A),
            "EditorSelectLine" to listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_A),
            "EditorBackSpace" to listOf(KeyEvent.VK_BACK_SPACE),
            "EditorDelete" to listOf(KeyEvent.VK_DELETE),
        )
        val invokeOnlyActions = listOf(IdeActions.ACTION_EDITOR_PASTE_FROM_HISTORY)
        val writeActions = listOf<String>()
    }

    val action: AnAction

    init {
        action = ActionManager.getInstance().getAction(actionId)
    }

    override fun invokeOnly(): Boolean {
        return invokeOnlyActions.contains(actionId)
    }

    override fun executionMode(): ExecutionMode {
        if (readonlyActions.contains(actionId)) {
            return ExecutionMode.READ
        } else if (writeActions.contains(actionId)) {
            return ExecutionMode.WRITE
        } else {
            return ExecutionMode.EDT
        }
    }

    private fun findComponent(context: CommandContext): Component? {
        // Act where focus is, as the action's shortcut would: e.g. "paste" in the find bar or terminal pastes there,
        // not into the file editor
        val focusOwner = IdeFocusManager.getInstance(project).focusOwner
        if (focusOwner != null) {
            return focusOwner
        }
        val e: ToolWindow? = context.toolWindow
        var component: Component? = null
        if (e != null) {
            component = e.component
        }
        if (component == null) {
            component = context.editor?.contentComponent
        }
        return component
    }

    override fun execute(context: CommandContext): String {
        if (editor != null) {
            context.editor = editor
        }
        val component = if (editor != null) editor.contentComponent else findComponent(context)
        val inTerminal = editor == null && component != null && isInTerminal(component)
        val keys = terminalKeys[actionId]
        if (inTerminal && keys != null) {
            pressKeys(keys)
            return "OK"
        }
        val actionToRun = terminalEquivalents[actionId]
            ?.takeIf { inTerminal }
            ?.let { ActionManager.getInstance().getAction(it) }
            ?: action

        ActionManager.getInstance()
            .tryToExecute(actionToRun, null, component, ActionPlaces.UNKNOWN, true)
        return "OK"
    }

    /** Presses [keys] together, as the user would at the keyboard; the focused terminal passes them to the shell. */
    private fun pressKeys(keys: List<Int>) {
        val robot = Robot()
        keys.forEach { robot.keyPress(it) }
        keys.reversed().forEach { robot.keyRelease(it) }
    }
}
