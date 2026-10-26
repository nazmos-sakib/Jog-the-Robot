package dev.naz.jogtherobot.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.naz.jogtherobot.bluetooth.Axis
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Visual-only joint model of a 5-axis igus robolink-style arm:
 * A1 base rotation (around the vertical axis only), A2 shoulder, A3 elbow, A4 wrist pitch, A5 tool roll.
 * The angles are what the app has sent, not feedback from the real robot.
 */
object RobotModel {
    /** Pose shown when the screen opens and after "Reset view", in degrees. */
    val HOME_DEG = listOf(0f, 30f, 70f, 50f, 0f)
    val MIN_DEG = listOf(-180f, -90f, -120f, -110f, -180f)
    val MAX_DEG = listOf(180f, 90f, 120f, 110f, 180f)

    /** How far the drawing turns a joint per Left/Right tap. */
    const val STEP_DEG = 10f

    fun jog(angles: List<Float>, axis: Axis, delta: Float): List<Float> =
        angles.toMutableList().also {
            val i = axis.ordinal
            it[i] = (it[i] + delta).coerceIn(MIN_DEG[i], MAX_DEG[i])
        }
}

private val IgusOrange = Color(0xFFF18700)
private val IgusOrangeLight = Color(0xFFFFB04D)
private val JointDark = Color(0xFF2E2E2E)
private val Outline = Color(0xFF5A3200)
private val Highlight = Color(0xFF00B4D8)

// Link lengths in model units
private const val BASE_H = 0.35f
private const val SHOULDER_H = 1.0f
private const val L1 = 1.6f
private const val L2 = 1.35f
private const val L3 = 0.55f
private const val FINGER = 0.4f
private const val FINGER_GAP = 0.16f
private const val FLOOR_R = 3.0f
private const val TURNTABLE_R = 0.45f

// Camera: turned and tilted a little so base rotation is visible
private const val VIEW_YAW = -0.6f
private const val VIEW_PITCH = 0.55f
private val COS_YAW = cos(VIEW_YAW)
private val SIN_YAW = sin(VIEW_YAW)
private val COS_PITCH = cos(VIEW_PITCH)
private val SIN_PITCH = sin(VIEW_PITCH)

private data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
}

/** Joint positions A1..A5 plus the gripper fingers, in model space. */
private class Pose(
    val joints: List<Vec3>,
    val fingers: List<Pair<Vec3, Vec3>>,
    val heading: Vec3,
)

private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

private fun forwardKinematics(deg: List<Float>): Pose {
    val t1 = rad(deg[0])
    val c1 = cos(t1)
    val s1 = sin(t1)
    // Direction in the arm's vertical plane; phi is measured from straight up.
    fun dir(phi: Float) = Vec3(sin(phi) * c1, sin(phi) * s1, cos(phi))

    val a1 = Vec3(0f, 0f, BASE_H)
    val a2 = Vec3(0f, 0f, SHOULDER_H)
    var phi = rad(deg[1])
    val a3 = a2 + dir(phi) * L1
    phi += rad(deg[2])
    val a4 = a3 + dir(phi) * L2
    phi += rad(deg[3])
    val a5 = a4 + dir(phi) * L3

    // Gripper fingers, rolled by A5 around the tool axis
    val toolDir = dir(phi)
    val inPlane = Vec3(cos(phi) * c1, cos(phi) * s1, -sin(phi))
    val sideways = Vec3(-s1, c1, 0f)
    val t5 = rad(deg[4])
    val side = inPlane * cos(t5) + sideways * sin(t5)
    val fingerA = a5 + side * FINGER_GAP
    val fingerB = a5 + side * -FINGER_GAP

    return Pose(
        joints = listOf(a1, a2, a3, a4, a5),
        fingers = listOf(
            fingerA to fingerA + toolDir * FINGER,
            fingerB to fingerB + toolDir * FINGER,
        ),
        heading = Vec3(c1, s1, 0f),
    )
}

/** Fits the model into the canvas and projects model points to screen points. */
private class Projector(size: Size) {
    private val scale = min(size.width / 7.5f, size.height / 5.6f)
    private val origin = Offset(size.width * 0.45f, size.height * 0.72f)

    val unit: Float get() = scale

    fun project(p: Vec3): Offset {
        val x1 = p.x * COS_YAW - p.y * SIN_YAW
        val depth = p.x * SIN_YAW + p.y * COS_YAW
        val up = p.z * COS_PITCH + depth * SIN_PITCH
        return Offset(origin.x + x1 * scale, origin.y - up * scale)
    }
}

/**
 * Draws the robot in its current pose. The selected joint is highlighted, and
 * tapping a joint selects that axis.
 */
@Composable
fun RobotArmView(
    angles: List<Float>,
    selectedAxis: Axis?,
    onAxisTap: (Axis) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Smoothly turn each joint to its new angle.
    val animated = angles.mapIndexed { i, target ->
        animateFloatAsState(target, tween(300), label = "joint$i").value
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val textMeasurer = rememberTextMeasurer()
    val currentAngles by rememberUpdatedState(animated)
    val currentOnTap by rememberUpdatedState(onAxisTap)

    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = "Robot arm. " +
                    (selectedAxis?.let { "${it.label} (${it.joint}) selected" } ?: "No axis selected")
            }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val projector = Projector(Size(size.width.toFloat(), size.height.toFloat()))
                    val joints = forwardKinematics(currentAngles).joints.map(projector::project)
                    val nearest = joints.indices.minBy { (joints[it] - tap).getDistance() }
                    if ((joints[nearest] - tap).getDistance() < 40.dp.toPx()) {
                        currentOnTap(Axis.entries[nearest])
                    }
                }
            }
    ) {
        val projector = Projector(size)
        val pose = forwardKinematics(animated)
        val u = projector.unit
        val selected = selectedAxis?.ordinal

        drawFloor(projector, pose, highlight = selected == 0, pulse = pulse)
        drawShadow(projector, pose, u)

        // Pedestal, A1 turntable and links, from the base outwards
        val base = projector.project(Vec3(0f, 0f, 0f))
        drawLink(base, projector.project(pose.joints[0]), 0.7f * u, JointDark)
        drawTurntable(projector, pose, highlight = selected == 0)
        val chain = pose.joints.map(projector::project)
        drawLink(chain[0], chain[1], 0.42f * u, IgusOrange)
        drawLink(chain[1], chain[2], 0.34f * u, IgusOrange)
        drawLink(chain[2], chain[3], 0.28f * u, IgusOrange)
        drawLink(chain[3], chain[4], 0.22f * u, IgusOrange)

        // Gripper: palm bar plus two fingers
        val fingers = pose.fingers.map { (a, b) -> projector.project(a) to projector.project(b) }
        drawLine(JointDark, fingers[0].first, fingers[1].first, 0.1f * u, StrokeCap.Round)
        fingers.forEach { (a, b) ->
            drawLine(JointDark, a, b, 0.08f * u, StrokeCap.Round)
        }

        // Joints with labels
        chain.forEachIndexed { i, p ->
            val isSelected = i == selected
            val r = 0.2f * u * if (isSelected) 1.25f else 1f
            if (isSelected) {
                drawCircle(Highlight.copy(alpha = 0.25f + 0.25f * pulse), r * (1.7f + 0.3f * pulse), p)
                drawArc(
                    color = Highlight,
                    startAngle = -60f + 40f * pulse,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = p - Offset(r * 1.5f, r * 1.5f),
                    size = Size(r * 3f, r * 3f),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            drawCircle(JointDark, r, p)
            drawCircle(if (isSelected) Highlight else IgusOrangeLight, r * 0.5f, p)

            val label = textMeasurer.measure(
                Axis.entries[i].code,
                TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) Highlight else Color.Gray,
                )
            )
            drawText(label, topLeft = p + Offset(r + 4.dp.toPx(), -r - label.size.height))
        }
    }
}

private fun DrawScope.drawLink(from: Offset, to: Offset, width: Float, color: Color) {
    val outline = if (color == IgusOrange) Outline else Color.Black
    drawLine(outline, from, to, width + 3.dp.toPx(), StrokeCap.Round)
    drawLine(color, from, to, width, StrokeCap.Round)
    if (color == IgusOrange) {
        // Thin light stripe so the links look rounded
        val shift = Offset(0f, -width * 0.2f)
        drawLine(IgusOrangeLight, from + shift, to + shift, width * 0.25f, StrokeCap.Round)
    }
}

/** Floor disc plus a dashed pointer that shows where A1 is facing. */
private fun DrawScope.drawFloor(projector: Projector, pose: Pose, highlight: Boolean, pulse: Float) {
    val floor = Path().apply {
        for (k in 0..48) {
            val a = (2 * Math.PI * k / 48).toFloat()
            val p = projector.project(Vec3(FLOOR_R * cos(a), FLOOR_R * sin(a), 0f))
            if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(floor, Color.Gray.copy(alpha = 0.12f))
    drawPath(
        floor,
        if (highlight) Highlight.copy(alpha = 0.5f + 0.5f * pulse) else Color.Gray.copy(alpha = 0.4f),
        style = Stroke(width = (if (highlight) 3 else 1).dp.toPx()),
    )
    drawLine(
        color = if (highlight) Highlight else IgusOrange.copy(alpha = 0.6f),
        start = projector.project(Vec3(0f, 0f, 0f)),
        end = projector.project(pose.heading * (FLOOR_R * 0.95f)),
        strokeWidth = 2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
    )
}

/** The arm's shadow on the floor: makes A1 turning around the vertical axis easy to see. */
private fun DrawScope.drawShadow(projector: Projector, pose: Pose, u: Float) {
    val points = listOf(Vec3(0f, 0f, 0f)) + pose.joints.drop(1) +
        pose.fingers.map { it.second }
    val path = Path()
    points.forEachIndexed { i, p ->
        val s = projector.project(Vec3(p.x, p.y, 0f))
        if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
    }
    drawPath(
        path,
        Color.Black.copy(alpha = 0.18f),
        style = Stroke(width = 0.25f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Disc on top of the pedestal with a marker that turns with A1. */
private fun DrawScope.drawTurntable(projector: Projector, pose: Pose, highlight: Boolean) {
    val disc = Path().apply {
        for (k in 0..32) {
            val a = (2 * Math.PI * k / 32).toFloat()
            val p = projector.project(Vec3(TURNTABLE_R * cos(a), TURNTABLE_R * sin(a), BASE_H))
            if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(disc, Color(0xFF4A4A4A))
    drawPath(disc, if (highlight) Highlight else Color.Black, style = Stroke(width = 2.dp.toPx()))
    val marker = pose.heading * TURNTABLE_R
    drawLine(
        color = if (highlight) Highlight else IgusOrange,
        start = projector.project(Vec3(0f, 0f, BASE_H)),
        end = projector.project(Vec3(marker.x, marker.y, BASE_H)),
        strokeWidth = 4.dp.toPx(),
        cap = StrokeCap.Round,
    )
}
