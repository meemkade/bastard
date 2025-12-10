package org.bread_experts_group.rfb

import org.bread_experts_group.Flaggable.Companion.raw
import org.bread_experts_group.MappedEnumeration
import org.bread_experts_group.api.coding.CodingFormats
import org.bread_experts_group.api.coding.CodingFormatsProvider
import org.bread_experts_group.api.coding.png.PNGChunk
import org.bread_experts_group.api.coding.png.StandardPNGReadingFeatures
import org.bread_experts_group.api.system.SystemFeatures
import org.bread_experts_group.api.system.SystemProvider
import org.bread_experts_group.api.system.device.SystemDeviceFeatures
import org.bread_experts_group.api.system.io.IODevice
import org.bread_experts_group.api.system.io.IODeviceFeatures
import org.bread_experts_group.api.system.io.open.FileIOReOpenFeatures
import org.bread_experts_group.io.reader.BSLReader
import org.bread_experts_group.io.reader.BSLReader.Companion.fileReadCheck
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration

fun decodePNG(
	relativePath: String
): List<ImageFrame> {
	val path = SystemProvider.get(SystemFeatures.GET_CURRENT_WORKING_PATH_DEVICE).device
		.get(SystemDeviceFeatures.PATH_APPEND).append(relativePath)
	val pathStr = path.get(SystemDeviceFeatures.SYSTEM_IDENTIFIER).identity
	val imgOpenData = path.get(SystemDeviceFeatures.IO_DEVICE).open(
		FileIOReOpenFeatures.READ,
		FileIOReOpenFeatures.SHARE_READ
	)
	val imgDevice = imgOpenData.firstNotNullOf { d -> d as? IODevice }
	val imgRead = BSLReader(imgDevice.get(IODeviceFeatures.READ), fileReadCheck)
	imgRead.order = ByteOrder.BIG_ENDIAN
	val png = CodingFormatsProvider.get(CodingFormats.PORTABLE_NETWORK_GRAPHICS)
	val magicData = png.read(imgRead, StandardPNGReadingFeatures.CHECK_MAGIC)
	if (!magicData.contains(StandardPNGReadingFeatures.CHECK_MAGIC)) throw IllegalArgumentException(
		"\"$pathStr\": Invalid PNG signature."
	)
	val header = png.read(imgRead, StandardPNGReadingFeatures.CHUNK_HEADER)
		.firstNotNullOfOrNull { c -> c as? PNGChunk.Header }
	if (header == null) throw IllegalArgumentException(
		"\"$pathStr\": PNG header missing."
	)
	var palette: PNGChunk.Palette? = null
	var transparency: PNGChunk.Transparency? = null
	val frameControls = mutableMapOf<Int, PNGChunk.FrameControl>()
	val frameData = mutableMapOf<Int, PNGChunk>()
	var lastData: PNGChunk? = null
	var lastDataIndex = 0
	while (true) {
		val pngData = png.read(
			imgRead,
			StandardPNGReadingFeatures.CHUNK_GENERIC,
			StandardPNGReadingFeatures.CHUNK_PALETTE,
			StandardPNGReadingFeatures.CHUNK_IMAGE_DATA,
			StandardPNGReadingFeatures.CHUNK_END,
			StandardPNGReadingFeatures.CHUNK_TRANSPARENCY,
			StandardPNGReadingFeatures.CHUNK_ANIMATION_CONTROL,
			StandardPNGReadingFeatures.CHUNK_FRAME_CONTROL,
			StandardPNGReadingFeatures.CHUNK_FRAME_DATA,
			header
		).first { c -> c != header && c is PNGChunk } as PNGChunk
		if (lastData != null && (pngData.identifier == lastData.identifier)) when (lastData) {
			is PNGChunk.ImageData -> {
				val newData = PNGChunk.ImageData(
					lastData.data + (pngData as PNGChunk.ImageData).data
				)
				lastData = newData
				frameData[0] = newData
			}

			is PNGChunk.FrameData -> {
				val newData = PNGChunk.FrameData(
					lastDataIndex,
					lastData.data + (pngData as PNGChunk.FrameData).data
				)
				lastData = newData
				frameData[lastDataIndex] = newData
			}
		} else when (pngData) {
			is PNGChunk.ImageData -> {
				frameData[0] = pngData
				lastData = pngData
			}

			is PNGChunk.FrameData -> {
				frameData[pngData.sequence] = pngData
				lastDataIndex = pngData.sequence
				lastData = pngData
			}

			else -> {
				lastData = null
				when (pngData) {
					is PNGChunk.Palette -> palette = pngData
					is PNGChunk.Transparency -> transparency = pngData
					is PNGChunk.FrameControl -> frameControls[pngData.sequence] = pngData
					is PNGChunk.End -> break
					is PNGChunk.Generic -> println("BSL developer help: ${pngData.identifier.toHexString()} ?")
				}
			}
		}
	}
	frameData.entries.removeIf { (sequence, chunk) ->
		when (chunk) {
			is PNGChunk.ImageData if ((sequence !in frameControls) && (frameControls.isNotEmpty())) -> {
				println("Dropping first frame: no control")
				true
			}

			is PNGChunk.FrameData if (sequence - 1 !in frameControls) -> {
				println("Dropping frame sequence # $sequence: no control")
				true
			}

			else -> false
		}
	}
	val images = mutableListOf<ImageFrame>()
	var argb = IntArray(header.w * header.h)
	frameData.forEach { (sequence, chunk) ->
		val control: PNGChunk.FrameControl
		val data: ByteArray
		if (chunk is PNGChunk.ImageData) {
			control = frameControls[sequence] ?: PNGChunk.FrameControl(
				0,
				header.w, header.h, 0, 0,
				Duration.INFINITE,
				MappedEnumeration(PNGChunk.FrameControl.DisposalOperation.APNG_DISPOSE_OP_NONE),
				MappedEnumeration(PNGChunk.FrameControl.BlendOperation.APNG_BLEND_OP_SOURCE)
			)
			data = chunk.data
		} else {
			chunk as PNGChunk.FrameData
			control = frameControls[sequence - 1]!!
			data = chunk.data
		}
		// Decoding
		val previousReturn = if (
			control.disposeOperation.enum == PNGChunk.FrameControl.DisposalOperation.APNG_DISPOSE_OP_PREVIOUS
		) argb.clone() else argb
		// TODO StartDEFLATE
		val inflater = InflaterInputStream(data.inputStream())
		// TODO EndDEFLATE

		fun paethPredictor(a: Int, b: Int, c: Int): Int {
			val p = a + b - c
			val pa = abs(p - a)
			val pb = abs(p - b)
			val pc = abs(p - c)
			return if ((pa <= pb) && (pa <= pc)) a
			else if (pb <= pc) b
			else c
		}

		val colorTypeRaw = header.colorType.raw()
		val channels = when (colorTypeRaw) {
			2L -> 3
			3L -> 1
			6L -> 4
			else -> TODO("No decoder: ${header.colorType}")
		}

		when (header.bitDepth) {
			8 -> {
				var lastScanLine = ByteArray(control.w * channels)
				var thisScanLine = ByteArray(control.w * channels)
				var y = 0
				while (y < control.h) {
					var x = 0
					val localFilter = inflater.read()
					var i = 0
					while (i < lastScanLine.size) {
						val thisByte = inflater.read()
						val a = if (i >= channels) thisScanLine[i - channels].toInt() and 0xFF else 0
						val b = lastScanLine[i].toInt() and 0xFF
						val c = if (i >= channels) lastScanLine[i - channels].toInt() and 0xFF else 0
						val decoded = when (localFilter) {
							0 -> thisByte
							1 -> thisByte + a
							2 -> thisByte + b
							3 -> thisByte + floor((a + b) / 2.0).roundToInt()
							4 -> thisByte + paethPredictor(a, b, c)
							else -> TODO("Unknown filter $localFilter")
						}
						thisScanLine[i] = (decoded and 0xFF).toByte()
						i++
					}
					fun absolute() = (header.w * (control.y + y)) + (control.x + x)
					when (colorTypeRaw) {
						2L -> for (i in 2 until thisScanLine.size step 3) {
							val r = (thisScanLine[i - 2].toInt() and 0xFF)
							val g = (thisScanLine[i - 1].toInt() and 0xFF)
							val b = (thisScanLine[i].toInt() and 0xFF)
							argb[absolute()] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
							x++
						}

						3L -> {
							palette ?: throw IllegalArgumentException(
								"\"$pathStr\": Missing palette for indexed-color image!"
							)
							val transparency = transparency as? PNGChunk.Transparency.Palette
							for (i in 0 until thisScanLine.size) {
								val thisColor = thisScanLine[i].toInt() and 0xFF
								var value = palette.palette[thisColor]
								if (transparency != null) {
									val localTransparency = transparency.palette.getOrNull(thisColor)
									value = value or (if (localTransparency != null)
										(localTransparency.toInt() and 0xFF) shl 24
									else (0xFF shl 24))
								}
								argb[absolute()] = when (control.blendOperation.enum) {
									PNGChunk.FrameControl.BlendOperation.APNG_BLEND_OP_SOURCE -> value
									PNGChunk.FrameControl.BlendOperation.APNG_BLEND_OP_OVER -> {
										val sourceAlpha = value ushr 24
										if (sourceAlpha == 0xFF) value
										else {
											val dest = argb[absolute()]
											// TODO ALPHA BLEND
//													val destAlpha = dest ushr 24
											dest
										}
									}

									else -> {
										println("Warning! blend ? ${control.blendOperation}")
										value
									}
								}
								x++
							}
						}

						6L -> for (i in 3 until thisScanLine.size step 4) {
							val r = (thisScanLine[i - 3].toInt() and 0xFF)
							val g = (thisScanLine[i - 2].toInt() and 0xFF)
							val b = (thisScanLine[i - 1].toInt() and 0xFF)
							val a = (thisScanLine[i].toInt() and 0xFF)
							argb[absolute()] = (a shl 24) or (r shl 16) or (g shl 8) or b
							x++
						}

						else -> TODO("No decoder: ${header.colorType}")
					}
					val temp = lastScanLine
					lastScanLine = thisScanLine
					thisScanLine = temp
					y++
				}
			}

			16 -> {
				val bytesPerPixel = channels * 2
				var lastScanLine = ByteArray(control.w * bytesPerPixel)
				var thisScanLine = ByteArray(control.w * bytesPerPixel)
				var y = 0
				var pixel = 0
				while (y < control.h) {
					val localFilter = inflater.read()
					var i = 0
					while (i < lastScanLine.size) {
						val thisByte = inflater.read()
						val a = if (i >= bytesPerPixel) thisScanLine[i - bytesPerPixel].toInt() and 0xFF else 0
						val b = lastScanLine[i].toInt() and 0xFF
						val c = if (i >= bytesPerPixel) lastScanLine[i - bytesPerPixel].toInt() and 0xFF else 0
						val decoded = when (localFilter) {
							0 -> thisByte
							1 -> thisByte + a
							2 -> thisByte + b
							4 -> thisByte + paethPredictor(a, b, c)
							else -> TODO("Unknown filter $localFilter")
						}
						thisScanLine[i] = (decoded and 0xFF).toByte()
						i++
					}
					when (colorTypeRaw) {
						2L -> for (i in 5 until thisScanLine.size step 6) {
							val r = ((thisScanLine[i - 5].toInt() and 0xFF) shl 8) or
									(thisScanLine[i - 4].toInt() and 0xFF)
							val g = ((thisScanLine[i - 3].toInt() and 0xFF) shl 8) or
									(thisScanLine[i - 2].toInt() and 0xFF)
							val b = ((thisScanLine[i - 1].toInt() and 0xFF) shl 8) or
									(thisScanLine[i].toInt() and 0xFF)
							argb[pixel++] = (0xFF shl 24) or
									(((r / 65535.0) * 255).roundToInt() shl 16) or
									(((g / 65535.0) * 255).roundToInt() shl 8) or
									((b / 65535.0) * 255).roundToInt()
						}

						6L -> for (i in 7 until thisScanLine.size step 8) {
							val r = ((thisScanLine[i - 7].toInt() and 0xFF) shl 8) or
									(thisScanLine[i - 6].toInt() and 0xFF)
							val g = ((thisScanLine[i - 5].toInt() and 0xFF) shl 8) or
									(thisScanLine[i - 4].toInt() and 0xFF)
							val b = ((thisScanLine[i - 3].toInt() and 0xFF) shl 8) or
									(thisScanLine[i - 2].toInt() and 0xFF)
							val a = ((thisScanLine[i - 1].toInt() and 0xFF) shl 8) or
									(thisScanLine[i].toInt() and 0xFF)
							argb[pixel++] = (((a / 65535.0) * 255).roundToInt() shl 24) or
									(((r / 65535.0) * 255).roundToInt() shl 16) or
									(((g / 65535.0) * 255).roundToInt() shl 8) or
									((b / 65535.0) * 255).roundToInt()
						}

						else -> TODO("No decoder: ${header.colorType}")
					}
					val temp = lastScanLine
					lastScanLine = thisScanLine
					thisScanLine = temp
					y++
				}
			}

			else -> TODO("${header.bitDepth}-bit")
		}
		// End
		inflater.close()
		images.add(
			ImageFrame(
				0, 0,
				header.w, header.h,
				argb.clone(), control.delay
			)
		)
		when (control.disposeOperation.enum) {
			PNGChunk.FrameControl.DisposalOperation.APNG_DISPOSE_OP_NONE -> {}
			PNGChunk.FrameControl.DisposalOperation.APNG_DISPOSE_OP_BACKGROUND -> {
				for (i in argb.indices) argb[i] = 0
			}

			PNGChunk.FrameControl.DisposalOperation.APNG_DISPOSE_OP_PREVIOUS -> argb = previousReturn
			else -> println("Warning! disposal ? ${control.disposeOperation}")
		}
	}
	imgDevice.get(IODeviceFeatures.RELEASE)
	return images
}