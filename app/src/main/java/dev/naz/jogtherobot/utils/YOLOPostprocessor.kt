package dev.naz.jogtherobot.utils


import android.graphics.RectF
import dev.naz.jogtherobot.domain.model.camera.DetectedObject
import dev.naz.jogtherobot.domain.model.camera.DetectedPose
import dev.naz.jogtherobot.domain.model.camera.Keypoint
import dev.naz.jogtherobot.domain.model.camera.LetterboxResult
import dev.naz.jogtherobot.domain.model.camera.LetterboxResultV2
import dev.naz.jogtherobot.domain.model.camera.ReverseMapping
import dev.naz.jogtherobot.domain.model.camera.deNormalize
import dev.naz.jogtherobot.domain.model.camera.mapFromModel
import dev.naz.jogtherobot.domain.model.camera.mapToPreview
import dev.naz.jogtherobot.utils.extentions.mapFromModel
import dev.naz.jogtherobot.utils.extentions.mapToPreview
import kotlin.collections.get


object YOLOPostprocessor {

    fun parseOutputShape300x57(
        batchOutput: Array<FloatArray>,
        letterBox: LetterboxResult,
        revMapping: ReverseMapping
    ): List<DetectedPose> {
        val detections = mutableListOf<DetectedPose>()

        // Output shape: [1, 300, 57]
        val numDetections = batchOutput.size // Should be 300
        //Log.d(ModelOutpuDebugTag, "YoloPostProcess:parseOutputShape300x6: batchOutput: ${batchOutput.size}")

        for (i in 0 until numDetections) {
            val detection = batchOutput[i]

            // Format: [x1, y1, x2, y2, confidence, class_id]
            val x1 = detection[0]
            val y1 = detection[1]
            val x2 = detection[2]
            val y2 = detection[3]
            val confidence = detection[4]
            val classId = detection[5].toInt()

            // Skip invalid or low confidence detections
            if (confidence < Constants.CONFIDENCE_THRESHOLD || x1 == 0f && y1 == 0f && x2 == 0f && y2 == 0f) {
                continue
            }
            /*
            * bounding box values between 0 and 1
            * [left=0.0237, top=0.6415, right=0.1758, bottom=0.9997]
            * These are normalized coordinates, NOT pixels.
            * Convert to pixels:
            * pixelX = normalizedX * inputWidth
            * pixelY = normalizedY * inputHeight
            * */
            val boundingBox = RectF(x1, y1, x2, y2)
                .mapFromModel(letterBox)
                .mapToPreview(revMapping)
            val label = if (classId < Constants.COCO_CLASSES.size) {
                Constants.COCO_CLASSES[classId]
            } else {
                "unknown"
            }


            // --- KEYPOINTS ---
            val kptStart = 6

            val keypoints = mutableListOf<Keypoint>()

            //https://github.com/ultralytics/ultralytics/blob/main/ultralytics/cfg/datasets/coco-pose.yaml
            //details about data points
            for (k in 0..16) {
                val kx: Float = detection[kptStart + k * 3]
                val ky: Float = detection[kptStart + k * 3 + 1]
                val kc: Float = detection[kptStart + k * 3 + 2]

                /*if (kc > 0.5f) {
                    keypoints.add(Keypoint(kx, ky, kc))
                }*/
                keypoints.add(
                    Keypoint(kx, ky, kc)
                        //.deNormalize()
                        .mapFromModel(letterBox)
                        .mapToPreview(revMapping)
                )
            }
            detections.add(
                DetectedPose(
                    boundingBox = boundingBox,
                    label = label,
                    confidence = confidence,
                    classId = classId,
                    keyPoints = keypoints
                )
            )
        }

        // Apply Non-Maximum Suppression
        //return nonMaximumSuppression(detections, Constants.NMS_THRESHOLD)
        return detections
    }



    fun parseOutputShape300x57(
        outputArray: Array<Array<FloatArray>>,
        letterBox: LetterboxResultV2,
        revMapping: ReverseMapping
    ): List<DetectedPose> {
        val detections = mutableListOf<DetectedPose>()

        // Output shape: [1, 300, 57]
        val batchOutput = outputArray[0] // First batch
        val numDetections = batchOutput.size // Should be 300
        //Log.d(ModelOutpuDebugTag, "YoloPostProcess:parseOutputShape300x6: batchOutput: ${batchOutput.size}")

        for (i in 0 until numDetections) {
            val detection = batchOutput[i]

            // Format: [x1, y1, x2, y2, confidence, class_id]
            val x1 = detection[0]
            val y1 = detection[1]
            val x2 = detection[2]
            val y2 = detection[3]
            val confidence = detection[4]
            val classId = detection[5].toInt()

            // Skip invalid or low confidence detections
            if (confidence < Constants.CONFIDENCE_THRESHOLD || x1 == 0f && y1 == 0f && x2 == 0f && y2 == 0f) {
                continue
            }

            /*
            * bounding box values between 0 and 1
            * [left=0.0237, top=0.6415, right=0.1758, bottom=0.9997]
            * These are normalized coordinates, NOT pixels.
            * Convert to pixels:
            * pixelX = normalizedX * inputWidth
            * pixelY = normalizedY * inputHeight
            * */
            val boundingBox = RectF(x1, y1, x2, y2)
                .mapFromModel(letterBox).mapToPreview(revMapping)
            val label = if (classId < Constants.COCO_CLASSES.size) {
                Constants.COCO_CLASSES[classId]
            } else {
                "unknown"
            }


            // --- KEYPOINTS ---
            val kptStart = 6

            val keypoints = mutableListOf<Keypoint>()

            //https://github.com/ultralytics/ultralytics/blob/main/ultralytics/cfg/datasets/coco-pose.yaml
            //details about data points
            for (k in 0..16) {
                val kx: Float = detection[kptStart + k * 3]
                val ky: Float = detection[kptStart + k * 3 + 1]
                val kc: Float = detection[kptStart + k * 3 + 2]

                /*if (kc > 0.5f) {
                    keypoints.add(Keypoint(kx, ky, kc))
                }*/
                keypoints.add(
                    Keypoint(kx, ky, kc).deNormalize()
                        .mapFromModel(letterBox).mapToPreview(revMapping)
                )
            }
            detections.add(
                DetectedPose(
                    boundingBox = boundingBox,
                    label = label,
                    confidence = confidence,
                    classId = classId,
                    keyPoints = keypoints
                )
            )
        }

        // Apply Non-Maximum Suppression
        //return nonMaximumSuppression(detections, Constants.NMS_THRESHOLD)
        return detections
    }

    /**
     * Non-Maximum Suppression to remove overlapping detections
     */
    private fun nonMaximumSuppression(
        detections: List<DetectedObject>,
        iouThreshold: Float
    ): List<DetectedObject> {
        if (detections.isEmpty()) return emptyList()

        // Sort by confidence descending
        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<DetectedObject>()

        for (current in sorted) {
            var shouldAdd = true

            for (selectedObj in selected) {
                val iou = calculateIoU(current.boundingBox, selectedObj.boundingBox)
                if (iou > iouThreshold) {
                    shouldAdd = false
                    break
                }
            }

            if (shouldAdd) {
                selected.add(current)
            }
        }

        return selected
    }

    /**
     * Calculate Intersection over Union
     */
    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val x1 = maxOf(box1.left, box2.left)
        val y1 = maxOf(box1.top, box2.top)
        val x2 = minOf(box1.right, box2.right)
        val y2 = minOf(box1.bottom, box2.bottom)

        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val area1 = (box1.right - box1.left) * (box1.bottom - box1.top)
        val area2 = (box2.right - box2.left) * (box2.bottom - box2.top)
        val union = area1 + area2 - intersection

        return if (union > 0) intersection / union else 0f
    }
}