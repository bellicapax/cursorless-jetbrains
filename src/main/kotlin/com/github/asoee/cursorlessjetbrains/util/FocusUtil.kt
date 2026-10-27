package com.github.asoee.cursorlessjetbrains.util

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import javax.swing.SwingUtilities

/** Whether [component] is inside a Terminal tool window. */
fun isInTerminal(component: Component): Boolean {
    return ProjectManager.getInstance().openProjects.any { project ->
        ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.component
            ?.let { SwingUtilities.isDescendingFrom(component, it) } == true
    }
}
