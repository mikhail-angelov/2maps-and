package com.bconf.a2maps_and // Your package name

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText

class NavigationBottomSheetFragment : BottomSheetDialogFragment() {

    private lateinit var editTextFrom: TextInputEditText
    private lateinit var editTextTo: TextInputEditText
    private lateinit var buttonNavigate: Button
    private lateinit var buttonClose: ImageButton

    // Optional: Listener to communicate back to the Activity/Fragment
    interface NavigationBottomSheetListener {
        fun onNavigateClicked(from: String, to: String)
        fun onBottomSheetClosed() // Or specific reset action
    }

    var listener: NavigationBottomSheetListener? = null

    // Companion object to allow passing initial values
    companion object {
        private const val ARG_FROM_POINT = "from_point"
        private const val ARG_TO_POINT = "to_point"

        fun newInstance(fromPoint: String? = null, toPoint: String? = null): NavigationBottomSheetFragment {
            val fragment = NavigationBottomSheetFragment()
            val args = Bundle()
            fromPoint?.let { args.putString(ARG_FROM_POINT, it) }
            toPoint?.let { args.putString(ARG_TO_POINT, it) }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.layout_bottom_sheet_navigation, container, false)

        editTextFrom = view.findViewById(R.id.editText_from)
        editTextTo = view.findViewById(R.id.editText_to)
        buttonNavigate = view.findViewById(R.id.button_navigate)
        buttonClose = view.findViewById(R.id.button_close_bottom_sheet)

        // Set initial values if passed
        arguments?.getString(ARG_FROM_POINT)?.let { editTextFrom.setText(it) }
        arguments?.getString(ARG_TO_POINT)?.let { editTextTo.setText(it) }

        buttonNavigate.setOnClickListener {
            val fromText = editTextFrom.text.toString()
            val toText = editTextTo.text.toString()
            if (fromText.isNotEmpty() && toText.isNotEmpty()) {
                listener?.onNavigateClicked(fromText, toText)
                // Optionally dismiss the bottom sheet after clicking navigate
                // dismiss()
            } else {
                Toast.makeText(context, "Please set 'From' and 'To' points", Toast.LENGTH_SHORT).show()
            }
        }

        buttonClose.setOnClickListener {
            resetFields()
            listener?.onBottomSheetClosed() // Notify listener
            dismiss() // Close the bottom sheet
        }

        // Make EditTexts non-focusable to prevent keyboard from popping up
        // if they are just for display. If users should type, remove these.
        editTextFrom.isFocusable = false
        editTextFrom.isClickable = true // To allow click listeners if needed elsewhere
        editTextTo.isFocusable = false
        editTextTo.isClickable = true

        return view
    }

    fun setFromText(from: String) {
        if (::editTextFrom.isInitialized) { // Check if view is created
            editTextFrom.setText(from)
        }
    }

    fun setToText(to: String) {
        if (::editTextTo.isInitialized) {
            editTextTo.setText(to)
        }
    }

    private fun resetFields() {
        editTextFrom.setText("")
        editTextTo.setText("")
    }

    // Call this method when the bottom sheet is about to be dismissed
    // if you want to ensure fields are reset.
    // However, the close button already does this.
    override fun onDestroyView() {
        // You might want to reset fields or notify listener here as well
        // if the sheet is dismissed by dragging, for example.
        super.onDestroyView()
    }
}
