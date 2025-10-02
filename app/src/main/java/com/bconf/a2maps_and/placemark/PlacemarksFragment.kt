package com.bconf.a2maps_and.placemark

import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.databinding.FragmentPlacemarksBinding
import com.bconf.a2maps_and.ui.viewmodel.NavigationViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

class PlacemarksFragment : Fragment(), OnPlacemarkActionClickListener {

    private var _binding: FragmentPlacemarksBinding? = null
    private val binding get() = _binding!!

    private lateinit var placemarkViewModel: PlacemarksViewModel
    private lateinit var navigationViewModel: NavigationViewModel
    private lateinit var placemarkAdapter: PlacemarkAdapter

    private var currentlyRevealedPosition: Int = RecyclerView.NO_POSITION
    private var isManuallySwiping: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        placemarkViewModel = ViewModelProvider(this).get(PlacemarksViewModel::class.java)
        navigationViewModel =
            ViewModelProvider(requireActivity()).get(NavigationViewModel::class.java) // Activity-scoped

        _binding = FragmentPlacemarksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        placemarkAdapter = PlacemarkAdapter(
            actionClickListener = this, // Fragment implements OnPlacemarkActionClickListener
            onItemClicked = { item, position ->
                val holder =
                    binding.placemarksRecyclerView.findViewHolderForAdapterPosition(position) as? PlacemarkAdapter.PlacemarkViewHolder
                holder?.let {
                    if (currentlyRevealedPosition != RecyclerView.NO_POSITION && currentlyRevealedPosition != position) {
                        val previousHolder =
                            binding.placemarksRecyclerView.findViewHolderForAdapterPosition(
                                currentlyRevealedPosition
                            ) as? PlacemarkAdapter.PlacemarkViewHolder
                        previousHolder?.closeActions(true)
                    }

                    if (currentlyRevealedPosition == position) {
                        it.closeActions(true)
                        currentlyRevealedPosition = RecyclerView.NO_POSITION
                    } else {
                        it.openActions(true)
                        currentlyRevealedPosition = position
                    }
                }
            }
        )

        binding.placemarksRecyclerView.apply {
            adapter = placemarkAdapter
            layoutManager = LinearLayoutManager(context)
        }

        val itemTouchHelper = ItemTouchHelper(SwipeActionsCallback())
        itemTouchHelper.attachToRecyclerView(binding.placemarksRecyclerView)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Assuming lastKnownGpsLocation is the correct flow for general location updates not strictly tied to active navigation
            navigationViewModel.lastKnownGpsLocation.collectLatest { location ->
                placemarkViewModel.updateCurrentLocation(location) // Pass location to PlacemarksViewModel
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            placemarkViewModel.displayItems.collectLatest { displayItems ->
                placemarkAdapter.submitList(displayItems)
                binding.emptyView.isVisible = displayItems.isEmpty()
            }
        }
    }

    // Implementation of OnPlacemarkActionClickListener
    override fun onCenterClick(placemark: Placemark, position: Int) {
        Log.d("PlacemarksFragment", "Center click on ${placemark.name}")
        closeCurrentlyRevealedItem()
        val action = PlacemarksFragmentDirections.actionPlacemarksFragmentToMapFragment(
            placemarkLat = placemark.latitude.toFloat(),
            placemarkLng = placemark.longitude.toFloat(),
            placemarkId = placemark.id
        )
        findNavController().navigate(action)

    }

    override fun onNavigateClick(placemark: Placemark, position: Int) {
        Log.d("PlacemarksFragment", "Navigate click on ${placemark.name}")
        closeCurrentlyRevealedItem()
        navigationViewModel.requestNavigationTo(LatLng(placemark.latitude, placemark.longitude))
        findNavController().popBackStack() // Or navigate to map
    }

    override fun onEditClick(placemark: Placemark, position: Int) {
        Log.d("PlacemarksFragment", "Edit click on ${placemark.name}")
//        val action = PlacemarksFragmentDirections.actionPlacemarksFragmentToEditPlacemarkFragment(placemark.id)
//        findNavController().navigate(action)
        Toast.makeText(requireContext(), "Not implemented yet!", Toast.LENGTH_LONG).show()
        closeCurrentlyRevealedItem() // Close after initiating action
    }

    override fun onDeleteClick(placemark: Placemark, position: Int) {
        Log.d("PlacemarksFragment", "Delete click on ${placemark.name}")
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Placemark")
            .setMessage("Are you sure you want to delete '${placemark.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                placemarkViewModel.deletePlacemark(placemark.id)
                // List will update via observer. Item will be closed by notifyItemChanged implicitly or by next step.
            }
            .setNegativeButton("Cancel", null)
            .show()
        closeCurrentlyRevealedItem() // Close after showing dialog
    }

    private fun closeCurrentlyRevealedItem(animate: Boolean = true) {
        if (currentlyRevealedPosition != RecyclerView.NO_POSITION) {
            val holder = binding.placemarksRecyclerView.findViewHolderForAdapterPosition(
                currentlyRevealedPosition
            ) as? PlacemarkAdapter.PlacemarkViewHolder
            holder?.closeActions(animate)
            currentlyRevealedPosition = RecyclerView.NO_POSITION
        }
    }

    inner class SwipeActionsCallback : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            // Forbid swipe if an item is programmatically opened and not the one being interacted with
            if (currentlyRevealedPosition != RecyclerView.NO_POSITION && currentlyRevealedPosition != viewHolder.adapterPosition && !isManuallySwiping) {
                return 0
            }
            return super.getMovementFlags(recyclerView, viewHolder)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // Not used, actions are via buttons
        }

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                val holder = viewHolder as PlacemarkAdapter.PlacemarkViewHolder
                val maxSwipe = -holder.actionsBackground.width.toFloat()
                val newDx = dX.coerceIn(maxSwipe, 0f)
                holder.foregroundView.translationX = newDx

                // If this swipe starts to reveal actions, it becomes the currently revealed item
                if (newDx < 0 && isCurrentlyActive) {
                    if (currentlyRevealedPosition != RecyclerView.NO_POSITION && currentlyRevealedPosition != holder.adapterPosition) {
                        val previousHolder =
                            recyclerView.findViewHolderForAdapterPosition(currentlyRevealedPosition) as? PlacemarkAdapter.PlacemarkViewHolder
                        previousHolder?.closeActions(false) // Close without animation
                    }
                    currentlyRevealedPosition = holder.adapterPosition
                } else if (newDx == 0f && !isCurrentlyActive && currentlyRevealedPosition == holder.adapterPosition && isManuallySwiping) {
                    // Swiped fully closed manually
                    currentlyRevealedPosition = RecyclerView.NO_POSITION
                }
            } else {
                super.onChildDraw(
                    c,
                    recyclerView,
                    viewHolder,
                    dX,
                    dY,
                    actionState,
                    isCurrentlyActive
                )
            }
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                isManuallySwiping = true
                val swipingPosition = viewHolder?.adapterPosition ?: RecyclerView.NO_POSITION
                if (currentlyRevealedPosition != RecyclerView.NO_POSITION && currentlyRevealedPosition != swipingPosition) {
                    closeCurrentlyRevealedItem(false) // Close immediately
                }
            } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                isManuallySwiping = false
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val holder = viewHolder as PlacemarkAdapter.PlacemarkViewHolder
            // If item is closed and it was the one revealed by swipe (not click)
            if (holder.foregroundView.translationX == 0f && currentlyRevealedPosition == viewHolder.adapterPosition && isManuallySwiping) {
                currentlyRevealedPosition = RecyclerView.NO_POSITION
            }
            // isManuallySwiping is reset in onSelectedChanged when actionState becomes IDLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.placemarksRecyclerView.adapter = null // Important to clear adapter
        _binding = null
    }
}
