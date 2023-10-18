package classes

import lib.UIElementImpl
import lib.points
import lib.sortedClockwise
import lib.uv
import org.openrndr.MouseButton
import org.openrndr.MouseEventType
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.draw.shadeStyle
import org.openrndr.extra.color.presets.ORANGE
import org.openrndr.extra.color.presets.PURPLE
import org.openrndr.extra.noise.uniform
import org.openrndr.math.Polar
import org.openrndr.math.Vector2
import org.openrndr.math.transforms.transform
import org.openrndr.shape.Segment
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contour
import org.openrndr.shape.offset
import kotlin.math.atan2
import kotlin.random.Random


class MapperElement(initialContour: ShapeContour): UIElementImpl() {

    var contour = initialContour
        set(value) {
            field = if (!value.closed) value.close() else value

            cSegments = value.segments.toMutableList()
            cPoints = value.points()
            cControls = value.segments.map { it.control.toList() }.flatten()
            actionablePoints = cPoints + cControls

            actionBounds = ShapeContour.fromPoints(actionablePoints.sortedClockwise(), true).offset(proximityThreshold * 2)
        }

    var image: ColorBuffer? = null
    var mapperMode: MapperMode = MapperMode.ADJUST

    private var cSegments = contour.segments.toMutableList()
    private var cPoints = contour.points()
    private var cControls = contour.segments.map { it.control.toList() }.flatten()
    private var actionablePoints = (cPoints + cControls)

    private val proximityThreshold = 9.0

    private fun movePoint(mouseP: Vector2) {
        val activePoint = cPoints.getOrNull(activePointIdx)


        if (activePoint != null) {
            val segment = contour.nearest(activePoint).segment
            val segmentIdx = cSegments.indexOf(segment)

            val saIdx = (segmentIdx + 1).mod(cSegments.size)
            val sbIdx = (segmentIdx - 1).mod(cSegments.size)

            val d = mouseP - activePoint

            if (activePoint == segment.start) {
                val cl0 = segment.control
                segment.control.getOrNull(0)?.let { cl0[0] = it + d }
                cSegments[segmentIdx] = Segment(mouseP, cl0, segment.end)

                val cl1 = cSegments[sbIdx].control
                cSegments[sbIdx].control.getOrNull(1)?.let { cl1[1] = it + d }
                cSegments[sbIdx] = Segment(cSegments[sbIdx].start, cl1, mouseP)
            } else {
                val cl0 = segment.control
                segment.control.getOrNull(1)?.let { cl0[1] = it + d }
                cSegments[segmentIdx] = Segment(segment.start, cl0, mouseP)

                val cl1 = cSegments[saIdx].control
                cSegments[saIdx].control.getOrNull(0)?.let { cl1[0] = it + d }
                cSegments[saIdx] = Segment(mouseP, cl1, cSegments[saIdx].end)
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
            cSegments[sbIdx] = Segment(cSegments[sbIdx].start, cSegments[sbIdx].control, cSegments[activeSegmentIdx].start)
            cSegments[saIdx] = Segment(cSegments[activeSegmentIdx].end, cSegments[saIdx].control, cSegments[saIdx].end)

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

            val cl0 = Array(2) { Vector2.ZERO }
            cl0[0] = split[0].control.getOrElse(0) { split[0].start }
            cl0[1] = split[0].control.getOrElse(1)  {
                val p = split[0].end
                val t = atan2(p.y - contour.bounds.center.y, p.x - contour.bounds.center.x) / 4
                Polar(Math.toDegrees(t) - 90.0, 10.0).cartesian + p
            }

            val cl1 = Array(2) { Vector2.ZERO }
            cl1[0] = split[1].control.getOrElse(0) {
                val p = split[1].start
                val t = atan2(p.y - contour.bounds.center.y, p.x - contour.bounds.center.x) / 4
                Polar(Math.toDegrees(t) + 90.0, 10.0).cartesian + p
            }
            cl1[1] =  split[1].control.getOrElse(1) { split[1].end }

            cSegments[idx] = Segment(split[0].start, cl0, split[0].end)
            cSegments.add(idx + 1, Segment(split[1].start, cl1, split[1].end))

            contour = contour {
                for (s in cSegments) {
                    segment(s)
                }
            }.close()
        }
    }

    private fun removePoint(mouseP: Vector2) {
        val pointInRange = cPoints.firstOrNull { it.distanceTo(mouseP) < proximityThreshold }

        pointInRange?.let {
            val s0 = cSegments.first { s -> s.end == it }
            val s1 = cSegments.first { s -> s.start == it }

            val c = arrayOf(s0.start, s1.end)
            s0.control.getOrNull(0)?.let { c[0] = it }
            s1.control.getOrNull(1)?.let { c[1] = it }

            val newSeg = Segment(s0.start, c, s1.end)

            val segmentsCopy = cSegments
            segmentsCopy[cSegments.indexOf(s0)] = newSeg
            segmentsCopy.remove(s1)

            contour = contour {
                for (s in segmentsCopy) {
                    segment(s)
                }
            }.close()

        }
    }

    private var activePointIdx = -1
    private var activeControlPointIdx = -1
    private var activeSegmentIdx = -1
    private var activeSegmentT = 0.5
    private var activeAnchor = Vector2.ZERO
    private var lastMouseEvent = MouseEventType.BUTTON_UP

    init {
        visible = mapperMode != MapperMode.PRODUCTION
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
                if (it.button == MouseButton.LEFT) {
                    addPoint(it.position)
                } else if (it.button == MouseButton.RIGHT) {
                    if (cSegments.size > 2) {
                        removePoint(it.position)
                    }
                }
            }

            activePointIdx = -1
            activeControlPointIdx = -1
            activeSegmentIdx = -1
            activeSegmentT = 0.5
            lastMouseEvent = it.type
        }

        dragged.listen {
            it.cancelPropagation()

            if (lastMouseEvent != MouseEventType.BUTTON_UP) {
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
    }

    fun draw(drawer: Drawer) {

        drawer.fill = ColorRGBa.WHITE
        drawer.stroke = ColorRGBa.PINK
        drawer.contour(contour)

        image?.let {
            drawer.isolated {

                drawer.fill = ColorRGBa.WHITE
                drawer.shadeStyle = shadeStyle {
                    fragmentTransform = """
                        vec2 texCoord = c_boundsPosition.xy;
                        vec2 size = textureSize(p_img, 0);
                        
                        float boundsAR = c_boundsSize.x / c_boundsSize.y;
                        float texAR = size.x / size.y;
                        
                        texCoord.y = 1.0 - texCoord.y;
                        
                        if (texAR > boundsAR) {
                            float cropFactor = boundsAR / texAR;
                            texCoord.x = (texCoord.x - 0.5) * cropFactor + 0.5;
                        } else {
                            float cropFactor = texAR / boundsAR;
                            texCoord.y = (texCoord.y - 0.5) * cropFactor + 0.5;
                        }
                        
                        x_fill = texture(p_img, texCoord);
                        
                    """.trimIndent()
                    parameter("img", it)
                }

                drawer.shape(contour.shape)

            }
        }

        if (mapperMode == MapperMode.ADJUST || mapperMode == MapperMode.DEBUG) {
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

        }

        if (mapperMode == MapperMode.DEBUG) {

            drawer.fill = null
            drawer.stroke = ColorRGBa.PINK.shade(0.4)
            drawer.contour(actionBounds)

            for (actionablePoint in actionablePoints) {
                drawer.fill = null
                drawer.stroke = ColorRGBa.WHITE
                drawer.circle(actionablePoint, proximityThreshold)
            }
        }
    }


    private fun isInRange(pos: Vector2, mousePos: Vector2): Boolean {
        return pos.distanceTo(mousePos) < proximityThreshold
    }

}


fun mapperElement(contour: ShapeContour, f: (MapperElement.() -> ColorBuffer)? = null): MapperElement {
    val el = MapperElement(contour)
    if (f != null) {
        el.image = f.invoke(el)
    }

    return el
}
