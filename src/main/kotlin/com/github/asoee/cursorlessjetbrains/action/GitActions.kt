package com.github.asoee.cursorlessjetbrains.action

import com.github.asoee.cursorlessjetbrains.git.ConflictMarkers
import com.github.asoee.cursorlessjetbrains.git.GitLines
import com.github.asoee.cursorlessjetbrains.git.GitRunner
import com.github.asoee.cursorlessjetbrains.util.requireWritable
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Cursorless's git actions ("git stage", "git unstage", "git accept"), as IDE actions on the editor's caret or
 * selection, so they work with or without the IDE's staging area. Errors show as a hint at the caret.
 */
abstract class CursorlessGitAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor != null &&
            FileDocumentManager.getInstance().getFile(editor.document)?.isInLocalFileSystem == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        try {
            perform(project, editor, file)
            VcsDirtyScopeManager.getInstance(project).fileDirty(file)
        } catch (error: Exception) {
            thisLogger().info("${javaClass.simpleName} failed: ${error.message}")
            HintManager.getInstance().showErrorHint(editor, error.message ?: error.toString())
        }
    }

    protected abstract fun perform(project: Project, editor: Editor, file: VirtualFile)

    /** The selected lines (0-based, inclusive), or the caret's line; a selection ending at a line start leaves it out */
    protected fun selectedLines(editor: Editor): Pair<Int, Int> {
        val document = editor.document
        val selection = editor.selectionModel
        if (!selection.hasSelection()) {
            val line = editor.caretModel.logicalPosition.line
            return line to line
        }
        val first = document.getLineNumber(selection.selectionStart)
        var last = document.getLineNumber(selection.selectionEnd)
        if (last > first && document.getLineStartOffset(last) == selection.selectionEnd) last--
        return first to last
    }
}

/** "git stage" on a whole file: git add */
class GitStageFileAction : CursorlessGitAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        FileDocumentManager.getInstance().saveDocument(editor.document)
        val git = GitRunner.forFile(File(file.path))
        git.check("add", "--", git.pathOf(File(file.path)))
    }
}

/** "git unstage" on a whole file: back to HEAD in the index, or out of it if the repository has no commits yet */
class GitUnstageFileAction : CursorlessGitAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val git = GitRunner.forFile(File(file.path))
        val path = git.pathOf(File(file.path))
        if (git.run("rev-parse", "--verify", "-q", "HEAD").exitCode == 0) {
            git.check("restore", "--staged", "--", path)
        } else {
            git.check("rm", "--cached", "-q", "--", path)
        }
    }
}

/** Base for staging or unstaging the selected lines, by writing the file's new index version */
abstract class GitLinesAction : CursorlessGitAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val git = GitRunner.forFile(File(file.path))
        val path = git.pathOf(File(file.path))
        val charset = file.charset
        val indexBytes = git.blob(":$path")
        val index = indexBytes?.toString(charset) ?: ""
        // Compare with "\n" line breaks like the document's, and keep the index's own line breaks
        val crlf = index.contains("\r\n")
        val (first, last) = selectedLines(editor)
        val newIndex = newIndex(git, path, charset, index.replace("\r\n", "\n"), editor.document.text, first, last)
        if (newIndex == index.replace("\r\n", "\n") && indexBytes != null) {
            throw IllegalStateException(nothingToDo)
        }
        git.writeIndex(path, (if (crlf) newIndex.replace("\n", "\r\n") else newIndex).toByteArray(charset))
    }

    protected abstract val nothingToDo: String

    protected abstract fun newIndex(
        git: GitRunner, path: String, charset: java.nio.charset.Charset, index: String, working: String, first: Int, last: Int,
    ): String
}

/** "git stage" on lines: stage only the changes on the selected lines */
class GitStageLinesAction : GitLinesAction() {
    override val nothingToDo = "No changes to stage on these lines"

    override fun newIndex(
        git: GitRunner, path: String, charset: java.nio.charset.Charset, index: String, working: String, first: Int, last: Int,
    ) = GitLines.stage(index, working, first, last)
}

/** "git unstage" on lines: unstage only the staged changes on the selected lines */
class GitUnstageLinesAction : GitLinesAction() {
    override val nothingToDo = "No staged changes on these lines"

    override fun newIndex(
        git: GitRunner, path: String, charset: java.nio.charset.Charset, index: String, working: String, first: Int, last: Int,
    ): String {
        val head = git.blob("HEAD:$path")?.toString(charset)?.replace("\r\n", "\n") ?: ""
        return GitLines.unstage(head, index, working, first, last)
    }
}

/**
 * "git revert" on lines: put the selected lines' unstaged changes back to the staged version, as VS Code does. Outside
 * a git repository (e.g. Perforce), the IDE's Rollback Lines, which reverts whole changed blocks.
 */
class GitRevertLinesAction : CursorlessGitAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val git = try {
            GitRunner.forFile(File(file.path))
        } catch (_: GitRunner.GitException) {
            val rollback = ActionManager.getInstance().getAction("Vcs.RollbackChangedLines")
            ActionManager.getInstance().tryToExecute(rollback, null, editor.contentComponent, ActionPlaces.UNKNOWN, true)
            return
        }
        val path = git.pathOf(File(file.path))
        val staged = git.blob(":$path")?.toString(file.charset)?.replace("\r\n", "\n")
            ?: throw IllegalStateException("${file.name} isn't tracked by git")
        val document = editor.document
        val (first, last) = selectedLines(editor)
        val reverted = GitLines.revert(staged, document.text, first, last)
        if (reverted == document.text) {
            throw IllegalStateException("No changes to revert on these lines")
        }
        requireWritable(editor)
        // Replace only the text that differs, so carets elsewhere stay where they are
        val old = document.charsSequence
        var start = 0
        while (start < old.length && start < reverted.length && old[start] == reverted[start]) start++
        var suffix = 0
        while (suffix < old.length - start && suffix < reverted.length - start &&
            old[old.length - 1 - suffix] == reverted[reverted.length - 1 - suffix]
        ) suffix++
        WriteCommandAction.runWriteCommandAction(project, "Revert Lines", null, {
            document.replaceString(start, old.length - suffix, reverted.substring(start, reverted.length - suffix))
        })
    }
}

/** "git accept": keep the side of the merge conflict the caret is in */
class AcceptConflictSideAction : CursorlessGitAction() {
    override fun perform(project: Project, editor: Editor, file: VirtualFile) {
        val document = editor.document
        val lines = document.text.split("\n")
        val resolution = ConflictMarkers.resolveAt(lines, editor.caretModel.logicalPosition.line)
        requireWritable(editor)
        var start = document.getLineStartOffset(resolution.firstLine)
        var end = document.getLineEndOffset(resolution.lastLine)
        if (resolution.keep.isEmpty()) {
            // An empty side removes the lines, line breaks included, rather than leaving a blank line
            if (resolution.lastLine + 1 < document.lineCount) {
                end = document.getLineStartOffset(resolution.lastLine + 1)
            } else if (start > 0) {
                start--
            }
        }
        WriteCommandAction.runWriteCommandAction(project, "Accept Conflict Side", null, {
            document.replaceString(start, end, resolution.keep.joinToString("\n"))
        })
    }
}
