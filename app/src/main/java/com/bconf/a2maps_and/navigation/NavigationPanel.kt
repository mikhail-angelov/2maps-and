package com.bconf.a2maps_and.navigation

import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import android.util.TypedValue
import android.graphics.Paint
import com.bconf.a2maps_and.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Date
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Renders [NavigationState] into the maneuver card, speed-limit sign and bottom panel of the map screen. */
class NavigationPanel(
    root: View,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onSetup: () -> Unit,
    private val onSelect: (Int) -> Unit,
    onGo: () -> Unit,
) {
    private val context = root.context
    val maneuverCard: View = root.findViewById(R.id.navigationManeuverCard)
    val bottomCard: View = root.findViewById(R.id.navigationPanel)
    val speedLimit: TextView = root.findViewById(R.id.navigationSpeedLimit)
    private val maneuverIcon: ImageView = root.findViewById(R.id.navigationManeuverIcon)
    private val maneuverDistance: TextView = root.findViewById(R.id.navigationManeuverDistance)
    private val maneuverText: TextView = root.findViewById(R.id.navigationManeuver)
    private val summary: TextView = root.findViewById(R.id.navigationSummary)
    private val status: TextView = root.findViewById(R.id.navigationStatus)
    private val options: LinearLayout = root.findViewById(R.id.navigationOptions)
    private val actions: View = root.findViewById(R.id.navigationActions)
    private val retry: Button = root.findViewById(R.id.navigationRetry)
    private val setup: Button = root.findViewById(R.id.navigationSetup)
    private val go: Button = root.findViewById(R.id.navigationGo)
    private val progress: LinearProgressIndicator = root.findViewById(R.id.navigationProgress)
    private var renderedOptions: List<Route>? = null

    init {
        root.findViewById<View>(R.id.navigationStop).setOnClickListener { onStop() }
        retry.setOnClickListener { onRetry() }
        setup.setOnClickListener { onSetup() }
        go.setOnClickListener { onGo() }
    }

    fun render(state: NavigationState, precisePermission: Boolean) {
        val phase = state.phase
        bottomCard.isVisible = phase != NavigationPhase.IDLE
        renderManeuver(state)
        val limit = state.maxSpeedKmh.takeIf { phase == NavigationPhase.ACTIVE }
        speedLimit.isVisible = limit != null
        if (limit != null) {
            speedLimit.text = limit.toString()
            speedLimit.contentDescription = context.getString(R.string.navigation_speed_limit_description, limit)
        }

        val arrival = state.arrivalAtMillis?.takeIf { phase == NavigationPhase.ACTIVE }?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
        val summaryText = when {
            phase == NavigationPhase.ARRIVED -> context.getString(R.string.navigation_arrived)
            phase == NavigationPhase.SELECTING || (state.route != null && (phase == NavigationPhase.ACTIVE || phase == NavigationPhase.ERROR)) ->
                summary(state.remainingSeconds, state.remainingMeters)
            else -> null
        }
        val statusText = when (phase) {
            NavigationPhase.WAITING_POSITION -> context.getString(R.string.navigation_waiting)
            NavigationPhase.CALCULATING -> context.getString(R.string.navigation_calculating)
            NavigationPhase.SELECTING -> if (state.searchingAlternatives) context.getString(R.string.navigation_searching_alternatives) else null
            NavigationPhase.ACTIVE -> when {
                state.isRerouting -> context.getString(R.string.navigation_rerouting)
                state.positionStale -> context.getString(R.string.navigation_position_stale)
                else -> null
            }
            NavigationPhase.ERROR -> if (state.route == null) state.message else context.getString(R.string.navigation_stale) + "\n" + state.message
            else -> null
        }
        // One line in both orientations: route details normally, status when action is needed.
        status.text = statusText?.replace('\n', ' ')
        status.contentDescription = statusText
        status.isVisible = statusText != null
        summary.isVisible = summaryText != null && statusText == null
        summary.text = summaryText
        if (summary.isVisible && arrival != null && summaryText != null) {
            val withArrival = context.getString(R.string.navigation_summary, summaryText,
                context.getString(R.string.navigation_arrival_short, arrival))
            summary.doOnLayout {
                val paint = Paint(summary.paint).apply {
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, context.resources.displayMetrics)
                }
                if (summary.isVisible && summary.text.toString() == summaryText &&
                    paint.measureText(withArrival) <= summary.width - summary.paddingLeft - summary.paddingRight) {
                    summary.text = withArrival
                }
            }
        }

        renderOptions(state)
        retry.isVisible = phase == NavigationPhase.ERROR
        setup.isVisible = phase == NavigationPhase.ERROR || phase == NavigationPhase.WAITING_POSITION
        setup.setText(when {
            phase == NavigationPhase.ERROR -> R.string.navigation_road_data
            !precisePermission -> R.string.navigation_precise
            else -> R.string.navigation_gps_settings
        })
        go.isVisible = phase == NavigationPhase.SELECTING
        actions.isVisible = retry.isVisible || setup.isVisible || go.isVisible
        renderProgress(state)
    }

    fun durationText(seconds: Double): String {
        val minutes = ceil(seconds / 60).toInt()
        return if (minutes < 60) context.getString(R.string.navigation_minutes, minutes)
            else context.getString(R.string.navigation_hours_minutes, minutes / 60, minutes % 60)
    }

    fun distanceText(meters: Double): String {
        // Coarser far from the maneuver, finer when it matters.
        val step = when { meters >= 500 -> 50; meters >= 100 -> 10; else -> 5 }
        val rounded = (meters / step).roundToInt() * step
        return if (rounded >= 1000) context.getString(R.string.navigation_maneuver_km, meters / 1000)
            else context.getString(R.string.navigation_maneuver_meters, rounded)
    }

    private fun summary(seconds: Double?, meters: Double) =
        seconds?.let { context.getString(R.string.navigation_summary, durationText(it), distanceText(meters)) } ?: distanceText(meters)

    private fun renderManeuver(state: NavigationState) {
        val next = state.upcomingManeuver.takeIf { state.phase == NavigationPhase.ACTIVE }
        maneuverCard.isVisible = next != null
        if (next == null) return
        val type = next.maneuver.type
        maneuverIcon.setImageResource(when (type) {
            ManeuverType.LEFT, ManeuverType.RIGHT -> R.drawable.ic_maneuver_left
            ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT, ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT -> R.drawable.ic_maneuver_slight_left
            ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT -> R.drawable.ic_maneuver_sharp_left
            ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT -> R.drawable.ic_maneuver_keep_left
            ManeuverType.U_TURN -> R.drawable.ic_maneuver_u_turn
            ManeuverType.ROUNDABOUT -> R.drawable.ic_maneuver_roundabout
            ManeuverType.FINISH -> R.drawable.ic_maneuver_finish
            ManeuverType.STRAIGHT, ManeuverType.CONTINUE, ManeuverType.REJOIN -> R.drawable.ic_maneuver_straight
        })
        // Right-hand variants mirror the left icons so both sides share one drawing.
        maneuverIcon.scaleX = if (type in RIGHT_TURNS) -1f else 1f
        val distance = distanceText(next.distanceMeters)
        val title = maneuverTitle(next.maneuver)
        maneuverDistance.text = distance
        maneuverText.text = title
        maneuverCard.contentDescription = context.getString(R.string.navigation_maneuver_description, title, distance)
        // Without a fresh position the instruction is kept for orientation but not presented as current.
        maneuverCard.alpha = if (state.positionStale) 0.5f else 1f
    }

    private fun renderOptions(state: NavigationState) {
        val shown = state.options.takeIf { state.phase == NavigationPhase.SELECTING && it.size > 1 }.orEmpty()
        options.isVisible = shown.isNotEmpty()
        if (shown != renderedOptions) {
            options.removeAllViews()
            val main = shown.firstOrNull()
            shown.forEachIndexed { index, option ->
                val card = LayoutInflater.from(context).inflate(R.layout.item_route_option, options, false) as MaterialCardView
                val time = option.estimatedDurationSeconds?.let(::durationText) ?: distanceText(option.distanceMeters)
                val delta = if (index == 0) context.getString(R.string.navigation_option_main) else {
                    val a = option.estimatedDurationSeconds; val b = main?.estimatedDurationSeconds
                    if (a != null && b != null) context.getString(R.string.navigation_option_delta, ((a - b) / 60).roundToInt()) else null
                }
                val details = listOfNotNull(distanceText(option.distanceMeters).takeIf { option.estimatedDurationSeconds != null }, delta).joinToString(" · ")
                card.findViewById<TextView>(R.id.routeOptionTime).text = time
                card.findViewById<TextView>(R.id.routeOptionDetails).text = details
                card.contentDescription = context.getString(R.string.navigation_option_description, index + 1, "$time, $details")
                card.setOnClickListener { onSelect(index) }
                options.addView(card)
            }
            renderedOptions = shown
        }
        val density = context.resources.displayMetrics.density
        for (index in 0 until options.childCount) {
            val card = options.getChildAt(index) as MaterialCardView
            val selected = index == state.selectedOption
            card.isChecked = selected
            card.strokeWidth = ((if (selected) 2 else 1) * density).roundToInt()
            card.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(context, if (selected) R.color.nav_accent else R.color.nav_option_stroke)))
        }
    }

    private fun renderProgress(state: NavigationState) {
        val busy = state.phase == NavigationPhase.CALCULATING || (state.phase == NavigationPhase.SELECTING && state.searchingAlternatives) ||
            (state.phase == NavigationPhase.ACTIVE && state.isRerouting)
        val tracking = state.phase == NavigationPhase.ACTIVE || state.phase == NavigationPhase.ARRIVED
        progress.isVisible = busy || tracking
        if (progress.isIndeterminate != busy) progress.isIndeterminate = busy
        if (!busy && tracking) {
            // Fraction of the same geometry the matcher measures; BRouter's own length estimate is not mixed in.
            val along = state.progressMeters ?: 0.0
            val total = along + state.remainingMeters
            val fraction = if (state.phase == NavigationPhase.ARRIVED) 1.0 else if (total > 0) along / total else 0.0
            progress.setProgressCompat((fraction.coerceIn(0.0, 1.0) * progress.max).roundToInt(), false)
        }
    }

    private fun maneuverTitle(maneuver: RouteManeuver): String {
        if (maneuver.type == ManeuverType.ROUNDABOUT && maneuver.exitNumber > 0) {
            return context.getString(R.string.navigation_roundabout_exit, maneuver.exitNumber)
        }
        return context.getString(when (maneuver.type) {
            ManeuverType.STRAIGHT -> R.string.navigation_straight
            ManeuverType.LEFT -> R.string.navigation_left
            ManeuverType.SLIGHT_LEFT -> R.string.navigation_slight_left
            ManeuverType.SHARP_LEFT -> R.string.navigation_sharp_left
            ManeuverType.RIGHT -> R.string.navigation_right
            ManeuverType.SLIGHT_RIGHT -> R.string.navigation_slight_right
            ManeuverType.SHARP_RIGHT -> R.string.navigation_sharp_right
            ManeuverType.KEEP_LEFT -> R.string.navigation_keep_left
            ManeuverType.KEEP_RIGHT -> R.string.navigation_keep_right
            ManeuverType.U_TURN -> R.string.navigation_u_turn
            ManeuverType.ROUNDABOUT -> R.string.navigation_roundabout
            ManeuverType.REJOIN -> R.string.navigation_rejoin
            ManeuverType.CONTINUE -> R.string.navigation_continue
            ManeuverType.EXIT_LEFT -> R.string.navigation_exit_left
            ManeuverType.EXIT_RIGHT -> R.string.navigation_exit_right
            ManeuverType.FINISH -> R.string.navigation_finish
        })
    }

    private companion object {
        val RIGHT_TURNS = setOf(ManeuverType.RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT,
            ManeuverType.KEEP_RIGHT, ManeuverType.EXIT_RIGHT)
    }
}
