package dev.naz.jogtherobot.domain.usecase

import dev.naz.jogtherobot.domain.model.camera.Keypoint
import dev.naz.jogtherobot.domain.model.camera.Pose
import dev.naz.jogtherobot.domain.model.camera.RobotCommand
import kotlin.math.abs
import kotlin.math.sqrt


class GestureDetector {

    fun detect(p: Pose): RobotCommand {

        val ls = p.leftShoulder
        val rs = p.rightShoulder
        val le = p.leftElbow
        val re = p.rightElbow
        val lw = p.leftWrist
        val rw = p.rightWrist

        val shoulderY = (ls.ky + rs.ky) / 2f //an average of both shoulder value
        val headY = p.nose.ky

        val margin = 0.08f
        // helper zones
        //y value lower means it in top position
        fun isAboveHead(y: Float) = y < headY  //
        fun isAtShoulder(y: Float) = abs(y - shoulderY) < 90f
        fun isBelowShoulder(y: Float) = y > shoulderY + 30f

        // -------------------------
        // Neutral
        // both hand strait down
        // -------------------------
        if (rs.ky < re.ky && re.ky+distance(re,rw)*.9f < rw.ky &&
            ls.ky < le.ky && le.ky+distance(le,lw)*.9f < lw.ky
        ) {
            //return "NEUTRAL"
            return RobotCommand.NEUTRAL
        }

        /* -------------------------
        🚨 EMERGENCY STOP
        - Arms crossed above head (X shape)
        if (
            leftWrist and rightWrist above nose
                    &&
            leftWrist and rightShoulder cross individual shoulder
        )
        * -------------------------
        */
        if (
            isAboveHead(lw.ky) && isAboveHead(rw.ky) &&
            le.kx < lw.kx && le.kx < ls.kx &&
            re.kx > rw.kx && re.kx > rs.kx
        ) {
            //return "EMERGENCY_STOP"
            //return "STOP"
            return RobotCommand.STOP
        }

        /* -------------------------
        🛑 STOP
        - Both hands clearly raised above head (surrender pose)
        if (
            leftWrist and rightWrist above nose
                    &&
            leftWrist and rightShoulder does not cross individual shoulder
        )
        * -------------------------
        */
        /*if (
            isAboveHead(lw.ky) && isAboveHead(rw.ky) &&
            lw.kx < le.kx && le.kx < ls.kx &&
            rs.kx < re.kx && re.kx < rw.kx
        ) {
            return "STOP"
        }*/

        // -------------------------
        // ▶ START (T pose)
        // - Both arms stretched horizontally (T pose)
        // -------------------------
        if (
            isAtShoulder(lw.ky) && isAtShoulder(rw.ky) &&
            lw.kx < le.kx && le.kx < ls.kx   &&
            rw.kx > re.kx && re.kx > rs.kx
        ) {
            //return "START"
            return RobotCommand.START
        }

        // -------------------------
        // ⬆ FORWARD
        // both hand up like w/v position
        // -------------------------
        if (
            isAboveHead(lw.ky) && isAboveHead(rw.ky) &&
            lw.kx < le.kx && le.kx < ls.kx &&
            rs.kx < re.kx && re.kx < rw.kx
        ) {
            //return "FORWARD"
            return RobotCommand.FORWARD
        }

        // -------------------------
        // ⬆⬆ FORWARD SLOW
        // Both hands slightly above head (less strict than STOP)
        // -------------------------
        /*if (
            rw.ky < headY && lw.ky < headY &&
            !isAboveHead(rw.ky) && !isAboveHead(lw.ky)
        ) {
            return "FORWARD_SLOW"
        }*/

        // -------------------------
        // ⬇ BACKWARD
        // both hand down, 45 degree angled
        // -------------------------
        if (
            isBelowShoulder(re.ky) && re.ky+distance(re,rw)*.7f < rw.ky &&
            isBelowShoulder(le.ky) && le.ky+distance(le,lw)*.7f < lw.ky &&
            distance(le,re)*1.5f < distance(lw,rw)
        ) {
            //return "BACKWARD"
            return RobotCommand.BACKWARD
        }

        // -------------------------
        // ⬇⬇ BACKWARD SLOW
        // Both hands clearly down
        // -------------------------
/*        if (rs.ky < re.ky && re.ky+distance(re,rw)*.6f < rw.ky &&
            ls.ky < le.ky && le.ky+distance(le,lw)*.6f < lw.ky &&
            rw.kx < re.kx && re.kx < rs.kx  &&
            lw.kx > le.kx && le.kx > ls.kx
        -----------------------------------------
            isBelowShoulder(rw.ky) &&
            isBelowShoulder(lw.ky)
        ) {
            return "BACKWARD_SLOW"
        }*/

        // -------------------------
        // ⬅ TURN LEFT
        // Left arm straight horizontal, right hand down
        // if the right hand is fully straited down then the difference of y value of the
        // right wrist and right elbow is the actual length from elbow to wrist.
        // this value can be used as margin
        // -------------------------
        if (
            isAtShoulder(lw.ky)  &&
            lw.kx < le.kx && le.kx < ls.kx  &&
            rs.ky < re.ky && re.ky+distance(re,rw)*.8f < rw.ky
        ) {
            //return "TURN_LEFT"
            return RobotCommand.TURN_LEFT
        }

        // -------------------------
        // ➡ TURN RIGHT
        // Right arm straight horizontal, left hand down
        // -------------------------
        if (
            isAtShoulder(rw.ky)   &&
            rw.kx > re.kx && re.kx > rs.kx  &&
            ls.ky < le.ky && le.ky+distance(le,lw)*.8f < lw.ky
        ) {
            //return "TURN_RIGHT"
            return RobotCommand.TURN_RIGHT
        }
        //return "NONE"
        return RobotCommand.NONE
    }

    fun distance(p: Keypoint, q: Keypoint): Float {
        val dx = q.kx - p.kx
        val dy = q.ky - p.ky
        return sqrt(dx * dx + dy * dy)
    }


    /*
    * when writing papers, explain how ambiguous other method of detecting pattern
    * create visual pattern for the logic.
    * how distance is measured to calculate the position of hand*/
}