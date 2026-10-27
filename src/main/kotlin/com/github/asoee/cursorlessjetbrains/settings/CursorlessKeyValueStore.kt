package com.github.asoee.cursorlessjetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * The Cursorless engine's key-value store (tutorial progress, dismissed warnings), persisted across restarts.
 * Values are JSON, as the engine writes them.
 */
@State(
    name = "com.github.asoee.cursorlessjetbrains.settings.CursorlessKeyValueStore",
    storages = [Storage("CursorlessKeyValueStore.xml")]
)
class CursorlessKeyValueStore : PersistentStateComponent<CursorlessKeyValueStore.State> {

    class State {
        var values: MutableMap<String, String> = mutableMapOf()
    }

    private var myState = State()

    override fun getState(): State {
        return myState
    }

    override fun loadState(state: State) {
        myState = state
    }

    fun get(key: String): String? {
        return myState.values[key]
    }

    fun set(key: String, valueJson: String) {
        myState.values[key] = valueJson
    }

    companion object {
        val instance: CursorlessKeyValueStore
            get() = ApplicationManager.getApplication().getService(CursorlessKeyValueStore::class.java)
    }
}
