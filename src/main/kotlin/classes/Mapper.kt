package classes

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import lib.UIManager
import mu.KotlinLogging
import org.openrndr.Extension
import org.openrndr.Program
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexFormat
import org.openrndr.math.Vector2
import org.openrndr.shape.Segment
import org.openrndr.shape.ShapeContour
import java.io.File

private val logger = KotlinLogging.logger { }

class Mapper(val uiManager: UIManager, val mode: MapperMode = MapperMode.ADJUST, val builder: Mapper.() -> Unit): Extension {
    override var enabled: Boolean = true

    var images = mutableListOf<ColorBuffer>()
    var elements = mutableListOf<MapperElement>()

    val defaultPath = "mapper-parameters"

    // Segment class has lazy length property, which is not supported by Gson decoding
    data class SegmentRef(val start: Vector2, val control: Array<Vector2>, val end:Vector2)

    private fun refFromSegment(from: Segment): SegmentRef {
        return SegmentRef(from.start, from.control, from.end)
    }

    private fun refToSegment(from: SegmentRef): Segment {
        return Segment(from.start, from.control, from.end)
    }

    fun fromObject(segmentLists: Map<Int, List<SegmentRef>>) {
        for ((i, l) in segmentLists) {
            val me = elements.getOrNull(i)

            if (me == null) {
                mapperElement(ShapeContour.fromSegments(l.map { v -> refToSegment(v) }, true)) {
                    images[i]
                }
            }

            elements[i].contour = ShapeContour.fromSegments(l.map { v -> refToSegment(v) }, true)
            elements[i].image = images[i]
        }
    }


    fun fromFile(file:File) {
        println("from file")
        val json = file.readText()
        val typeToken = object : TypeToken<Map<Int, List<SegmentRef>>>() {}
        val labeledValues: Map<Int, List<SegmentRef>> = try {
            Gson().fromJson(json, typeToken.type)
        } catch (e: JsonSyntaxException) {
            println("could not parse json: $json")
            throw e
        }

        fromObject(labeledValues)
    }

    fun toObject(): Map<Int, List<SegmentRef>> {
        return elements.withIndex().associate { (i, it) -> i to it.contour.segments.map { s -> refFromSegment(s) } }
    }

    fun toFile(file: File) {
        println("to file")
        file.writeText(Gson().toJson(toObject()))
    }

    var listenToProduceAssetsEvent = true

    fun mapperElement(contour: ShapeContour, f: MapperElement.() -> ColorBuffer): MapperElement {
        val m = MapperElement(contour)
        m.image = f.invoke(m)

        elements.add(m)
        images.add(m.image!!)
        uiManager.elements.add(m)

        return m
    }

    override fun setup(program: Program) {
        elements.clear()
        uiManager.elements.clear()

        builder()
        println("setup ${elements.size} ${uiManager.elements.size}")

        val mapperState = File(defaultPath, "${program.name}-latest.json")
        if (mapperState.exists()) {
           fromFile(mapperState)
        }

        program.produceAssets.listen {
            if (listenToProduceAssetsEvent) {
                val folderFile = File(defaultPath)
                val targetFile = File(defaultPath, "${it.assetMetadata.assetBaseName}.json")
                if (folderFile.exists() && folderFile.isDirectory) {
                    logger.info("Saving parameters to '${targetFile.absolutePath}")
                    toFile(targetFile)
                } else {
                    if (folderFile.mkdirs()) {
                        logger.info("Saving parameters to '${targetFile.absolutePath}")
                        toFile(targetFile)
                    } else {
                        logger.error { "Could not save parameters because could not create directory ${folderFile.absolutePath}" }
                    }
                }
            }
        }
    }

    override fun beforeDraw(drawer: Drawer, program: Program) {
        elements.forEach { it.draw(drawer) }
    }


    override fun shutdown(program: Program) {
        val folder = File(defaultPath)
        if (folder.exists() && folder.isDirectory) {
            toFile(File(defaultPath, "${program.name}-latest.json"))
        } else {
            if (folder.mkdirs()) {
                toFile(File(defaultPath, "${program.name}-latest.json"))
            } else {
                error("Could not persist Mapper state because could not create directory ${folder.absolutePath}")
            }
        }

    }
}
