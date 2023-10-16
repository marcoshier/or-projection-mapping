import lib.*
import org.openrndr.*
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.isolated
import org.openrndr.extra.color.presets.ORANGE
import org.openrndr.extra.color.presets.PURPLE
import org.openrndr.extra.noise.uniform
import org.openrndr.extra.olive.oliveProgram
import org.openrndr.math.Vector2
import org.openrndr.math.transforms.transform
import org.openrndr.shape.*
import kotlin.random.Random

fun main() = application {

    configure {
        width = 800
        height = 800
    }

    oliveProgram {

        var shiftPressed = false

        launch {
            keyboard.keyDown.listen {
                if(it.key == KEY_LEFT_SHIFT || it.key == KEY_RIGHT_SHIFT) {
                    shiftPressed = true
                }
            }

            keyboard.keyUp.listen {
                if(it.key == KEY_LEFT_SHIFT || it.key == KEY_RIGHT_SHIFT) {
                    shiftPressed = false
                }
            }
        }


        val uiManager = UIManager(program)
        val c = object: UIElementImpl() {
            var debug = true

            var contour = Circle(drawer.bounds.center, 200.0).contour.close()
                set(value) {
                    field = value

                    cSegments = value.segments.toMutableList()
                    cPoints = value.points()
                    cControls = value.segments.map { it.control.toList() }.flatten()
                    actionablePoints = cPoints + cControls

                    actionBounds = ShapeContour.fromPoints(actionablePoints.sortedClockwise(), true).offset(proximityThreshold * 2)
                }

            var cSegments = contour.segments.toMutableList()
            var cPoints = contour.points()
            var cControls = contour.segments.map { it.control.toList() }.flatten()
            var actionablePoints = (cPoints + cControls)


            val proximityThreshold = 9.0

            private fun movePoint(mouseP: Vector2) {
                val activePoint = cPoints.getOrNull(activePointIdx)

                if (activePoint != null) {
                    val segment = contour.nearest(activePoint).segment
                    val segmentIdx = cSegments.indexOf(segment)

                    val saIdx = (segmentIdx + 1).mod(cSegments.size)
                    val sbIdx = (segmentIdx - 1).mod(cSegments.size)

                    val d = mouseP - activePoint

                    if (activePoint == segment.start) {
                        cSegments[segmentIdx] = Segment(mouseP, segment.control[0] + d, segment.control[1], segment.end)
                        cSegments[sbIdx] = Segment(cSegments[sbIdx].start, cSegments[sbIdx].control[0], cSegments[sbIdx].control[1] + d, mouseP)
                    } else {
                        cSegments[segmentIdx] = Segment(segment.start, segment.control[0], segment.control[1] + d, mouseP)
                        cSegments[saIdx] = Segment(mouseP, cSegments[saIdx].control[0] + d, cSegments[saIdx].control[1], cSegments[saIdx].end)
                    }

                    contour = contour {
                        for (s in cSegments) {
                            segment(s)
                        }
                    }.close()
                }
            }

            private fun moveControlPoint(mouseP: Vector2) {
                val activePoint = cControls.getOrNull(activeControlPointIdx)
                val segment = cSegments.firstOrNull { it.control.any { c -> c == activePoint } }

                if (activePoint != null && segment != null) {
                    val segmentIdx = cSegments.indexOf(segment)

                    val sbIdx = (segmentIdx - 1).mod(cSegments.size)
                    val saIdx = (segmentIdx + 1).mod(cSegments.size)

                    if (activePoint == segment.control[0]) {
                        cSegments[segmentIdx] = Segment(segment.start, mouseP, segment.control[1], segment.end)
                        if (shiftPressed) cSegments[sbIdx] = Segment(cSegments[sbIdx].start, cSegments[sbIdx].control[0], segment.control[0].rotate(180.0, segment.start), cSegments[sbIdx].end)
                    } else {
                        cSegments[segmentIdx] = Segment(segment.start, segment.control[0], mouseP,  segment.end)
                        if (shiftPressed) cSegments[saIdx] = Segment(cSegments[saIdx].start, segment.control[1].rotate(180.0, segment.end), cSegments[saIdx].control[1], cSegments[saIdx].end)
                    }

                    contour = contour {
                        for (s in cSegments) {
                            segment(s)
                        }
                    }.close()

                }
            }

            private fun moveSegment(mouseP: Vector2) {
                val activeSegment = cSegments.getOrNull(activeSegmentIdx)

                if (activeSegment != null) {
                    val sbIdx = (activeSegmentIdx - 1).mod(cSegments.size)
                    val saIdx = (activeSegmentIdx + 1).mod(cSegments.size)

                    val d = mouseP - activeSegment.position(activeSegmentT)

                    cSegments[activeSegmentIdx] = cSegments[activeSegmentIdx].transform(transform { translate(d) })
                    cSegments[sbIdx] = Segment(cSegments[sbIdx].start, cSegments[sbIdx].control[0], cSegments[sbIdx].control[1], cSegments[activeSegmentIdx].start)
                    cSegments[saIdx] = Segment(cSegments[activeSegmentIdx].end, cSegments[saIdx].control[0], cSegments[saIdx].control[1], cSegments[saIdx].end)

                    contour = contour {
                        for (s in cSegments) {
                            segment(s)
                        }
                    }.close()
                }
            }

            private fun moveShape(mouseP: Vector2) {
                contour = contour.transform(transform {
                    translate(mouseP - actionBounds.bounds.position(activeAnchor))
                })
            }

            private fun addPoint(mouseP: Vector2) {
                val nearest = contour.nearest(mouseP)

                if (mouseP.distanceTo(nearest.position) < proximityThreshold) {

                    val segmentRef = nearest.segment
                    val idx = cSegments.indexOf(segmentRef)

                    val split = segmentRef.split(nearest.segmentT)

                    cSegments[idx] = split[0]
                    cSegments.add(idx + 1, split[1])

                    contour = contour {
                        for (s in cSegments) {
                            segment(s)
                        }
                    }.close()
                }
            }

            var activePointIdx = -1
            var activeControlPointIdx = -1
            var activeSegmentIdx = -1
            var activeSegmentT = 0.5
            var activeAnchor = Vector2.ZERO
            var lastMouseEvent = MouseEventType.BUTTON_UP

            init {

                actionBounds = ShapeContour.fromPoints(actionablePoints.sortedClockwise(), true).offset(proximityThreshold * 2)

                buttonDown.listen {
                    it.cancelPropagation()
                    lastMouseEvent = it.type

                    val activePoint = actionablePoints.firstOrNull { ap -> isInRange(ap, it.position) }

                    if (activePoint != null) {
                        if (activePoint in cPoints) {
                            val idx = cPoints.indexOf(cPoints.minBy { p -> p.distanceTo(it.position) })
                            activePointIdx = idx
                        } else if (activePoint in cControls) {
                            val idx = cControls.indexOf(cControls.minBy { p -> p.distanceTo(it.position) })
                            activeControlPointIdx = idx
                        }
                    } else {
                        val nearest = contour.nearest(it.position)
                        if (it.position.distanceTo(nearest.position) < proximityThreshold) {
                            activeSegmentIdx = cSegments.indexOf(nearest.segment)
                            activeSegmentT = nearest.segmentT
                        } else {
                            activeAnchor = actionBounds.bounds.uv(it.position)
                        }
                    }

                }

                buttonUp.listen {
                    it.cancelPropagation()

                    if (lastMouseEvent == MouseEventType.BUTTON_DOWN) {
                        addPoint(it.position)
                    }

                    activePointIdx = -1
                    activeControlPointIdx = -1
                    activeSegmentIdx = -1
                    activeSegmentT = 0.5
                    lastMouseEvent = it.type

                }

                dragged.listen {
                    it.cancelPropagation()
                    lastMouseEvent = it.type

                    if (activePointIdx != -1) {
                        movePoint(it.position)
                    } else if (activeControlPointIdx != -1) {
                        moveControlPoint(it.position)
                    } else if (activeSegmentIdx != -1){
                        moveSegment(it.position)
                    } else {
                        moveShape(it.position)
                    }
                }
            }

            fun draw() {
                drawer.fill = null
                drawer.stroke = ColorRGBa.WHITE
                drawer.contour(contour)

                drawer.fill = ColorRGBa.PURPLE.mix(ColorRGBa.PINK, 0.5)
                drawer.circles(contour.points(), 6.0)

                if (activePointIdx != -1) {
                    drawer.fill = ColorRGBa.ORANGE
                    drawer.circle(contour.points()[activePointIdx], 9.0)
                }

                for ((i, segment) in contour.segments.withIndex()) {
                    drawer.strokeWeight = 1.0
                    drawer.stroke = ColorRGBa.GREEN.toHSLa().shiftHue(Double.uniform(0.0, 360.0, Random(i))).toRGBa()
                    drawer.segment(segment)

                    drawer.fill = ColorRGBa.WHITE.opacify(0.4)
                    drawer.stroke = null
                    drawer.circles(segment.control.toList(), 4.0)

                    drawer.stroke = ColorRGBa.WHITE.opacify(0.4)
                    drawer.strokeWeight = 0.5
                    segment.control.getOrNull(0)?.let { drawer.lineSegment(segment.start, it) }
                    segment.control.getOrNull(1)?.let { drawer.lineSegment(it, segment.end) }

                }


                if (debug) drawDebug()
            }

            private fun drawDebug() {
                drawer.isolated {
                    drawer.fill = null
                    drawer.stroke = ColorRGBa.PINK.shade(0.4)
                    drawer.contour(actionBounds)

                    for (actionablePoint in actionablePoints) {
                        drawer.fill = null
                        drawer.stroke = ColorRGBa.WHITE
                        drawer.circle(actionablePoint, 9.0 + proximityThreshold)
                    }
                }
            }

            private fun isInRange(pos: Vector2, mousePos: Vector2): Boolean {
                return pos.distanceTo(mousePos) < proximityThreshold
            }

        }

        uiManager.elements.add(c)

        extend {
            c.draw()
        }
    }

}