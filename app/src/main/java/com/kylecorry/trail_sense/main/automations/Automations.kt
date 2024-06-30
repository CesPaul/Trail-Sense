package com.kylecorry.trail_sense.main.automations

import android.content.Context
import android.content.IntentFilter
import androidx.core.os.bundleOf
import com.kylecorry.andromeda.core.system.BroadcastReceiverTopic
import com.kylecorry.trail_sense.shared.automations.Automation
import com.kylecorry.trail_sense.tools.tools.infrastructure.Tools

object Automations {
    fun setup(context: Context) {
        val tools = Tools.getTools(context, false)
        val actions = tools.flatMap { it.broadcasts.map { it.action } }

        actions.forEach { action ->
            val topic = BroadcastReceiverTopic(context, IntentFilter(action))
            // TODO: Should there be a way to unregister? Maybe only register when something is listening?
            topic.subscribe { intent ->
                // TODO: This should be run in the background
                val automationsToRun = getAutomations(context, action)
                val receiversToRun = automationsToRun.flatMap { it.receivers.map { it.receiverId } }

                val availableReceivers = tools
                    .flatMap { it.actions }
                    .filter { receiversToRun.contains(it.id) && it.isEnabled(context) }

                automationsToRun.forEach {
                    for (receiver in it.receivers) {
                        if (!receiver.enabled) {
                            continue
                        }
                        val toolReceiver = availableReceivers
                            .firstOrNull { r -> r.id == receiver.receiverId } ?: continue

                        val parameters = bundleOf()
                        receiver.parameterTransformers.forEach { transformer ->
                            transformer.transform(intent.extras ?: bundleOf(), parameters)
                        }

                        toolReceiver.action.onReceive(context, parameters)
                    }
                }
                true
            }
        }
    }

    private fun getAutomations(context: Context, action: String): List<Automation> {
        // TODO: Load these from the DB
        val automations = listOf(
            PowerSavingModeAutomation.onEnabled(context),
            PowerSavingModeAutomation.onDisabled(context)
        )
        return automations.filter { it.broadcast == action }
    }
}