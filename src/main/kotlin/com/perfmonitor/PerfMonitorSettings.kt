package com.perfmonitor

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*

@Service(Service.Level.APP)
@State(name = "PerfMonitorSettings", storages = [Storage("perfMonitorSettings.xml")])
class PerfMonitorSettings : PersistentStateComponent<PerfMonitorSettings.State> {

    data class State(
        var provider: String    = "COPILOT",
        var copilotMode: String = "CLIPBOARD"  // "CLIPBOARD" or "CLI"
    )

    private var state = State()

    override fun getState() = state
    override fun loadState(s: State) { state = s }

    var provider: String
        get() = state.provider
        set(v) { state.provider = v }

    var copilotMode: String
        get() = state.copilotMode
        set(v) { state.copilotMode = v }

    var claudeApiKey: String
        get() = getKey("PerfMonitor.Claude")
        set(v) = setKey("PerfMonitor.Claude", v)

    var geminiApiKey: String
        get() = getKey("PerfMonitor.Gemini")
        set(v) = setKey("PerfMonitor.Gemini", v)

    private fun getKey(service: String): String {
        val attrs = CredentialAttributes(service)
        return PasswordSafe.instance.getPassword(attrs) ?: ""
    }

    private fun setKey(service: String, value: String) {
        val attrs = CredentialAttributes(service)
        PasswordSafe.instance.set(attrs, Credentials(service, value))
    }

    companion object {
        fun instance(): PerfMonitorSettings = service()
    }
}