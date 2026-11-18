package com.xmum.smartgrocery.ui.recipes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.ui.pantrypal.ChecklistHistoryScreen
import com.xmum.smartgrocery.ui.pantrypal.PantryPalTheme
import com.xmum.smartgrocery.ui.pantrypal.rememberHistoryItems
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.xmum.smartgrocery.databinding.BottomSheetChecklistHistoryBinding
import kotlinx.coroutines.launch

class ChecklistHistoryModal : BottomSheetDialogFragment() {

    private var _binding: BottomSheetChecklistHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RecipeViewModel by activityViewModels()
    private lateinit var adapter: ChecklistHistoryAdapter

    companion object {
        const val TAG = "ChecklistHistoryModal"

        fun newInstance(): ChecklistHistoryModal {
            return ChecklistHistoryModal()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetChecklistHistoryBinding.inflate(inflater, container, false)
        return ComposeView(requireContext()).apply {
            id = R.id.checklist_history_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeViewModel()
        (view as ComposeView).setContent {
            val checklists by rememberHistoryItems(adapter)
            val state by viewModel.uiState.collectAsState()
            PantryPalTheme {
                ChecklistHistoryScreen(
                    checklists = checklists,
                    onOpen = ::openChecklist,
                    onClose = { binding.buttonCloseHistory.performClick() },
                    onClearAll = viewModel::clearAllChecklistHistory,
                    isClearing = state.isClearingHistory,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            BottomSheetBehavior.from(sheet).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ChecklistHistoryAdapter(onOpenChecklist = ::openChecklist)
        binding.recyclerChecklistHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerChecklistHistory.adapter = adapter
    }

    private fun openChecklist(checklist: SavedChecklist) {
        viewModel.restoreChecklistById(checklist.id) {
            if (!isAdded || parentFragmentManager.isStateSaved) return@restoreChecklistById
            val host = activity as? MainActivity
            dismiss()
            host?.openPantryAction(PantryAction.AiChecklist)
        }
    }

    private fun setupListeners() {
        binding.buttonCloseHistory.setOnClickListener {
            dismiss()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        val checklists = state.savedChecklists
                        adapter.submitList(checklists)

                        val isEmpty = checklists.isEmpty()
                        binding.layoutEmptyHistory.visibility = if (isEmpty) View.VISIBLE else View.GONE
                        binding.recyclerChecklistHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.shoppingAddEvents.collect { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
