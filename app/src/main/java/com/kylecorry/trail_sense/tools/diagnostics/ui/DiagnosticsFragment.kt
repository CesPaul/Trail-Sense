package com.kylecorry.trail_sense.tools.diagnostics.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.kylecorry.andromeda.core.ui.Colors
import com.kylecorry.andromeda.fragments.BoundFragment
import com.kylecorry.andromeda.list.ListView
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.databinding.FragmentDiagnosticsBinding
import com.kylecorry.trail_sense.databinding.ListItemPlainIconBinding
import com.kylecorry.trail_sense.shared.colors.AppColor
import com.kylecorry.trail_sense.shared.navigation.NavControllerAppNavigation
import com.kylecorry.trail_sense.tools.diagnostics.domain.IDiagnostic
import com.kylecorry.trail_sense.tools.diagnostics.domain.Severity
import com.kylecorry.trail_sense.tools.diagnostics.infrastructure.DiagnosticAlertService
import com.kylecorry.trail_sense.tools.tools.infrastructure.Tool
import com.kylecorry.trail_sense.tools.tools.infrastructure.Tools
import com.kylecorry.trail_sense.tools.tools.ui.items.DiagnosticItem

class DiagnosticsFragment : BoundFragment<FragmentDiagnosticsBinding>() {

    private lateinit var diagnostics: List<Pair<String, IDiagnostic>>
    private lateinit var diagnosticListView: ListView<DiagnosticItem>

    private val titleLookup by lazy { DiagnosticCodeTitleLookup(requireContext()) }
    private val descriptionLookup by lazy { DiagnosticCodeDescriptionLookup(requireContext()) }

    private var diagnosticIdToTool = mapOf<String, List<Tool>>()

    override fun generateBinding(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentDiagnosticsBinding {
        return FragmentDiagnosticsBinding.inflate(layoutInflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.diagnosticsTitle.rightButton.setOnClickListener {
            findNavController().navigate(R.id.action_diagnostics_to_sensor_details)
        }
        diagnosticListView =
            ListView(binding.diagnosticsList, R.layout.list_item_plain_icon) { itemView, item ->
                val itemBinding = ListItemPlainIconBinding.bind(itemView)
                itemBinding.title.text = titleLookup.getTitle(item.code)
                itemBinding.description.text = descriptionLookup.getDescription(item.code)
                itemBinding.icon.setImageResource(R.drawable.ic_alert)
                Colors.setImageColor(itemBinding.icon, getStatusTint(item.code.severity))
                itemBinding.root.setOnClickListener {
                    val alerter = DiagnosticAlertService(
                        requireContext(),
                        NavControllerAppNavigation(findNavController())
                    )
                    alerter.alert(item)
                }
            }
        diagnosticListView.addLineSeparator()

        val tools = Tools.getTools(requireContext())
        // TODO: Keep the mapping of tools to diagnostics and the mapping of ID to diagnostic
        val toolMap = mutableMapOf<String, List<Tool>>()

        tools.forEach {
            it.diagnostics.forEach { diagnostic ->
                toolMap[diagnostic.id] = toolMap.getOrDefault(diagnostic.id, listOf()) + it
            }
        }

        diagnosticIdToTool = toolMap

        diagnostics = tools.flatMap { it.diagnostics }
            .distinctBy { it.id }
            .map { it.id to it.create(this) }

        scheduleUpdates(INTERVAL_1_FPS)
    }

    override fun onUpdate() {
        super.onUpdate()
        // TODO: Keep track of which diagnostic found an error, and show all the tools it is associated with
        val results = diagnostics.flatMap {
            it.second.scan().map { code ->
                DiagnosticItem(code, diagnosticIdToTool[it.first] ?: listOf())
            }
        }.toSet().sortedBy { it.code.severity.ordinal }
        binding.emptyText.isVisible = results.isEmpty()
        diagnosticListView.setData(results)
    }

    @ColorInt
    private fun getStatusTint(status: Severity): Int {
        return when (status) {
            Severity.Error -> AppColor.Red.color
            Severity.Warning -> AppColor.Yellow.color
        }
    }

}