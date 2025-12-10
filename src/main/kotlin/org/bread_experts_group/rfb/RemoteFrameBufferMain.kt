package org.bread_experts_group.project_incubator.rfb

import org.bread_experts_group.Flaggable.Companion.from
import org.bread_experts_group.Flaggable.Companion.raw
import org.bread_experts_group.MappedEnumeration
import org.bread_experts_group.api.coding.CodingFormats
import org.bread_experts_group.api.coding.CodingFormatsProvider
import org.bread_experts_group.api.coding.png.PNGChunk
import org.bread_experts_group.api.coding.png.StandardPNGReadingFeatures
import org.bread_experts_group.api.system.SystemFeatures
import org.bread_experts_group.api.system.SystemProvider
import org.bread_experts_group.api.system.device.SystemDeviceFeatures
import org.bread_experts_group.api.system.io.BSLIODataEnded
import org.bread_experts_group.api.system.io.IODevice
import org.bread_experts_group.api.system.io.IODeviceFeatures
import org.bread_experts_group.api.system.io.open.FileIOReOpenFeatures
import org.bread_experts_group.api.system.io.open.StandardIOOpenFeatures
import org.bread_experts_group.api.system.socket.BSLSocketConnectionEnded
import org.bread_experts_group.api.system.socket.SystemSocketProviderFeatures
import org.bread_experts_group.api.system.socket.close.StandardCloseFeatures
import org.bread_experts_group.api.system.socket.ipv4.InternetProtocolV4AddressData
import org.bread_experts_group.api.system.socket.ipv4.SystemInternetProtocolV4SocketProviderFeatures
import org.bread_experts_group.api.system.socket.ipv4.stream.SystemInternetProtocolV4StreamProtocolFeatures
import org.bread_experts_group.api.system.socket.ipv4.stream.tcp.IPv4TCPFeatures
import org.bread_experts_group.api.system.socket.ipv6.*
import org.bread_experts_group.api.system.socket.ipv6.config.WindowsIPv6SocketConfigurationFeatures
import org.bread_experts_group.api.system.socket.ipv6.stream.SystemInternetProtocolV6StreamProtocolFeatures
import org.bread_experts_group.api.system.socket.ipv6.stream.feature.SystemInternetProtocolV6TCPFeature
import org.bread_experts_group.api.system.socket.ipv6.stream.tcp.IPv6TCPFeatures
import org.bread_experts_group.api.system.socket.ipv6.stream.tcp.feature.IPv6TCPResolutionFeature
import org.bread_experts_group.api.system.socket.resolution.ResolutionDataPart
import org.bread_experts_group.api.system.socket.resolution.StandardResolutionFeatures
import org.bread_experts_group.api.system.socket.resolution.StandardResolutionStatus
import org.bread_experts_group.bslVersion
import org.bread_experts_group.command_line.Flag
import org.bread_experts_group.command_line.readArgs
import org.bread_experts_group.command_line.stringToBoolean
import org.bread_experts_group.command_line.stringToULong
import org.bread_experts_group.io.reader.BSLReader
import org.bread_experts_group.io.reader.BSLReader.Companion.fileReadCheck
import org.bread_experts_group.io.reader.BSLReader.Companion.socketReadCheck
import org.bread_experts_group.io.reader.BSLWriter
import org.bread_experts_group.io.reader.BSLWriter.Companion.socketWriteCheck
import org.bread_experts_group.project_incubator.rfb.PixelFormatDataStructure.Companion.nextPixelFormatStructure
import org.bread_experts_group.project_incubator.rfb.PixelFormatDataStructure.Companion.writePixelFormatStructure
import java.nio.ByteOrder
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.InflaterInputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

val frames = mutableListOf<ImageFrame>()
val logQueue = LinkedBlockingQueue<LogMessage>()

fun client(
	name: String, addressData: InternetProtocolV6AddressPortData,
	read: BSLReader<*, *>, write: BSLWriter<*, *>, serverPort: UShort
) {
	fun shutdown(reason: String) {
		logQueue.add(LogMessage.Disconnect(true, reason, addressData, serverPort))
	}

	// 7.1.1 ProtocolVersion Handshake
	write.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
	write.flush()
	val clientPV = read.readN(12).toString(Charsets.US_ASCII).let {
		if (it.last() != '\n') return shutdown("Protocol version did not end with line feed \"$it\"")
		it.take(it.length - 1)
	}
	val delimiter = clientPV.indexOf(' ')
	if (
		delimiter == -1 ||
		clientPV.take(delimiter) != "RFB"
	) return shutdown("Bad header presented in protocol version \"$clientPV\"")
	val clientVersion = clientPV.substring(delimiter + 1)
		.split('.', limit = 2)
		.mapNotNull { it.toIntOrNull() }
	if (clientVersion.size != 2) return shutdown("Bad protocol version format \"$clientPV\"")
	if (clientVersion[0] != 3) return shutdown("Protocol version too high \"$clientPV\"")
	val minorVersionInUse = when (val minor = clientVersion[1]) {
		3, 7, 8 -> minor
		else -> 3
	}
	logQueue.add(
		LogMessage.Connect(
			"Client RFB version ${clientVersion[0]}.${clientVersion[1]} (compatibility: $minorVersionInUse)",
			addressData, serverPort
		)
	)

	// 7.1.2 Security Handshake
	write.write8i(1)
	write.write8i(1)
	write.flush()
	if (read.readU8i() != 1) return shutdown("Client did not agree to the provided security methods")

	// 7.1.3 SecurityResult Handshake
	write.write32(0)
	write.flush()

	// 7.3.1 ClientInit
	if (read.readU8i() == 0) logQueue.add(
		LogMessage.Connect("Client wants exclusive desktop access", addressData, serverPort)
	) else logQueue.add(
		LogMessage.Connect("Client wants shared desktop access", addressData, serverPort)
	)

	// 7.3.2 ServerInit (7.4 Pixel Format Data Structure)
	val rootFrame = frames[0]
	write.write16i(rootFrame.w)
	write.write16i(rootFrame.h)
	var currentFormat = PixelFormatDataStructure(
		32, 24,
		bigEndian = true,
		trueColor = true,
		255, 255, 255,
		24, 16, 8
	)
	writePixelFormatStructure(write, currentFormat)
	val encodedName = name.toByteArray(Charsets.ISO_8859_1)
	write.write32(encodedName.size)
	write.write(encodedName)
	write.flush()

	var shownFrame = 0
	var shownAt = System.currentTimeMillis()
	val encodings = EnumSet.noneOf(RemoteFramebufferEncodings::class.java)
	// 7.5 Client-to-Server Messages
	while (true) {
		when (val type = read.readU8i()) {
			// 7.5.1 SetPixelFormat
			0 -> {
				read.skip(3)
				currentFormat = nextPixelFormatStructure(read)
			}

			// SetEn◙codings
			2 -> {
				read.skip(1)
				repeat(read.readU16i()) {
					when (val encoding = read.readS32()) {
						0 -> encodings.add(RemoteFramebufferEncodings.RAW)
						1 -> encodings.add(RemoteFramebufferEncodings.COPYRECT)
						2 -> encodings.add(RemoteFramebufferEncodings.RRE)
						5 -> encodings.add(RemoteFramebufferEncodings.HEXTILE)
						15 -> encodings.add(RemoteFramebufferEncodings.TRLE)
						16 -> encodings.add(RemoteFramebufferEncodings.ZRLE)
						-239 -> encodings.add(RemoteFramebufferEncodings.CURSOR_PSEUDO_ENCODING)
						-223 -> encodings.add(RemoteFramebufferEncodings.DESKTOP_SIZE_PSEUDO_ENCODING)
						else -> logQueue.add(
							LogMessage.Info(
								"Unsupported encoding $encoding", addressData, serverPort
							)
						)
					}
				}
			}

			// 7.5.3 / 7.6.1 FramebufferUpdateRequest / FramebufferUpdate
			3 -> {
				val incremental = read.readU8i() != 0
				read.readU16i() // x
				read.readU16i() // y
				read.readU16i() // w
				read.readU16i() // h
				var timing = (System.currentTimeMillis() - shownAt).toDuration(DurationUnit.MILLISECONDS)
				var showFrame = shownFrame
				while (true) {
					timing -= frames[showFrame].delay
					if (timing < Duration.ZERO) break
					showFrame = (showFrame + 1) % frames.size
				}
				if (incremental && showFrame == shownFrame) {
					write.write8i(0)
					write.fill(1)
					write.write16i(0)
				} else {
					shownFrame = showFrame
					shownAt = System.currentTimeMillis()
					write.write8i(0)
					write.fill(1)
					write.write16i(1)
					// Rect
					val thisFrame = frames[showFrame]
					write.write16i(thisFrame.x)
					write.write16i(thisFrame.y)
					write.write16i(thisFrame.w)
					write.write16i(thisFrame.h)
					write.write32(0) // RAW encoding
					for (y in 0 until thisFrame.h) {
						for (x in 0 until thisFrame.w) {
							val rgb = thisFrame.data[(y * thisFrame.w) + x]
							val r = (rgb ushr 16) and 0xFF
							val g = (rgb ushr 8) and 0xFF
							val b = rgb and 0xFF
							var p = (r shl currentFormat.redShift) or
									(g shl currentFormat.greenShift) or
									(b shl currentFormat.blueShift)
							if (!currentFormat.bigEndian) p = Integer.reverseBytes(p)
							when (currentFormat.bitsPerPixel) {
								8 -> write.write8i(p)
								16 -> write.write16i(p)
								32 -> write.write32l(p.toLong())
								else -> return shutdown("Client was corrupted $currentFormat")
							}
						}
					}
				}
				write.flush()
			}

			// KeyEvent
			4 -> {
				val down = read.readU8i()
				read.skip(2)
				val key = read.readS32()
				logQueue.add(LogMessage.Key(down != 0, key, addressData, serverPort))
			}

			// PointerEvent
			5 -> {
				val mask = read.readU8i()
				val x = read.readU16i()
				val y = read.readU16i()
				logQueue.add(LogMessage.Mouse(mask, x, y, addressData, serverPort))
			}

			6 -> {
				read.skip(3)
				val data = read.readN(read.readS32()).toString(Charsets.ISO_8859_1)
				logQueue.add(LogMessage.Clipboard(data, addressData, serverPort))
			}

			else -> return shutdown("Client sent unknown message type ... $type")
		}
	}
}

fun threadLoop(
	tcpV6Resolution: IPv6TCPResolutionFeature, tcpV6: SystemInternetProtocolV6TCPFeature,
	port: UShort, serverName: String,
	bans: Map<BanMode, List<InternetProtocolV6AddressData>>
) {
	val tcpV6RecvAny = tcpV6Resolution.resolve(
		"", port,
		StandardResolutionFeatures.PASSIVE
	).firstNotNullOf {
		it as? ResolutionDataPart
	}.data.firstNotNullOf { it as? InternetProtocolV6AddressData }
	val tcpV6Socket = tcpV6.get(IPv6TCPFeatures.SOCKET)
		.openSocket()
	tcpV6Socket.get(IPv6SocketFeatures.CONFIGURE)
		.configure(WindowsIPv6SocketConfigurationFeatures.ALLOW_IPV6_AND_IPV4)
	tcpV6Socket.get(IPv6SocketFeatures.BIND)
		.bind(InternetProtocolV6AddressPortData(tcpV6RecvAny.data, port))
	tcpV6Socket.get(IPv6SocketFeatures.LISTEN)
		.listen()
	// PROTOTYPE LOGIC
	while (true) {
		val acceptData = tcpV6Socket.get(IPv6SocketFeatures.ACCEPT)
			.accept()
			.block()
		val acceptedAddress = acceptData.firstNotNullOf { it as? InternetProtocolV6AddressPortData }
		val acceptedSocket = acceptData.firstNotNullOf { it as? IPv6Socket }
		logQueue.add(LogMessage.Connect("Initial connection", acceptedAddress, port))
		if (bans[BanMode.RESET]?.any { it == acceptedAddress } == true) {
			acceptedSocket.close(StandardCloseFeatures.RELEASE)
			logQueue.add(
				LogMessage.Disconnect(true, "In RESET ban-list", acceptedAddress, port)
			)
			continue
		}
		if (bans[BanMode.CLOSE]?.any { it == acceptedAddress } == true) {
			acceptedSocket.close(
				StandardCloseFeatures.STOP_RX, StandardCloseFeatures.STOP_TX,
				StandardCloseFeatures.RELEASE
			)
			logQueue.add(
				LogMessage.Disconnect(true, "In CLOSE ban-list", acceptedAddress, port)
			)
			continue
		}

		Thread.ofVirtual().name("[$port] VNC Session $acceptedAddress").start {
			val read = BSLReader(acceptedSocket.get(IPv6SocketFeatures.RECEIVE), socketReadCheck)
			val write = BSLWriter(acceptedSocket.get(IPv6SocketFeatures.SEND), socketWriteCheck)
			read.order = ByteOrder.BIG_ENDIAN
			write.order = ByteOrder.BIG_ENDIAN
			try {
				client(serverName, acceptedAddress, read, write, port)
			} catch (_: BSLSocketConnectionEnded) {
				logQueue.add(
					LogMessage.Disconnect(false, "Client disconnected", acceptedAddress, port)
				)
			}
			acceptedSocket.close(
				StandardCloseFeatures.STOP_RX, StandardCloseFeatures.STOP_TX,
				StandardCloseFeatures.RELEASE
			)
		}
	}
}

fun main(args: Array<String>) {
	val tcpV6 = SystemProvider.get(SystemFeatures.NETWORKING_SOCKETS)
		.get(SystemSocketProviderFeatures.INTERNET_PROTOCOL_V6)
		.get(SystemInternetProtocolV6SocketProviderFeatures.STREAM_PROTOCOLS)
		.get(SystemInternetProtocolV6StreamProtocolFeatures.TRANSMISSION_CONTROL_PROTOCOL)
	val tcpV6Resolution = tcpV6.get(IPv6TCPFeatures.NAME_RESOLUTION)
	val tcpV4 = SystemProvider.get(SystemFeatures.NETWORKING_SOCKETS)
		.get(SystemSocketProviderFeatures.INTERNET_PROTOCOL_V4)
		.get(SystemInternetProtocolV4SocketProviderFeatures.STREAM_PROTOCOLS)
		.get(SystemInternetProtocolV4StreamProtocolFeatures.TRANSMISSION_CONTROL_PROTOCOL)
	val tcpV4Resolution = tcpV4.get(IPv4TCPFeatures.NAME_RESOLUTION)

	fun portEffector(string: String) = stringToULong(
		UShort.MIN_VALUE.toULong()..UShort.MAX_VALUE.toULong()
	)(string).toUShort()

	val port = Flag(
		"port",
		"A port to use for listening on the RFB server.",
		true,
		default = 5900u,
		conv = ::portEffector
	)
	val portRange = Flag(
		"port_range",
		"A range of ports to use for listening on the RFB server.\nFormat: LowerPort-UpperPort",
		true,
		conv = {
			val split = it.split('-')
			if (split.size != 2) throw IllegalArgumentException(
				"\"$it\": Port range must consist of a lower and upper bound, like so: \"5900-5910\""
			)
			portEffector(split[0])..portEffector(split[1])
		}
	)
	val name = Flag(
		"server_name",
		"The server name transmitted with each connection.",
		default = "BSLSTD_${bslVersion()}",
		required = 1
	)
	val imagePath = Flag(
		"image",
		"The image to transmit over the RFB connection.",
		required = 1,
		conv = {
			val path = SystemProvider.get(SystemFeatures.GET_CURRENT_WORKING_PATH_DEVICE).device
				.get(SystemDeviceFeatures.PATH_APPEND).append(it)
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
				"\"$it\": Invalid PNG signature."
			)
			val header = png.read(imgRead, StandardPNGReadingFeatures.CHUNK_HEADER)
				.firstNotNullOfOrNull { c -> c as? PNGChunk.Header }
			if (header == null) throw IllegalArgumentException(
				"\"$it\": PNG header missing."
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
										"\"$it\": Missing palette for indexed-color image!"
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
				frames.add(
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
			true
		}
	)
	val logTemplate = Flag<String>(
		"log_template",
		"The name template to use for logging user actions."
	)
	val logConnectionEvents = Flag(
		"log_connections",
		"Log (dis)connection events in log_template.",
		default = true,
		conv = ::stringToBoolean
	)
	val logKeyEvents = Flag(
		"log_key",
		"Log key events in log_template.",
		default = true,
		conv = ::stringToBoolean
	)
	val logMouseButtonEvents = Flag(
		"log_mouse_button",
		"Log mouse button (including scroll wheel) events in log_template.",
		default = true,
		conv = ::stringToBoolean
	)
	val logMouseMovementEvents = Flag(
		"log_mouse_movement",
		"Log mouse movement events in log_template.",
		default = false,
		conv = ::stringToBoolean
	)
	val logClipboardEvents = Flag(
		"log_clipboard",
		"Log clipboard events in log_template.",
		default = true,
		conv = ::stringToBoolean
	)
	val logInfoEvents = Flag(
		"log_info",
		"Log informational events in log_template.",
		default = true,
		conv = ::stringToBoolean
	)

	fun parseAddress(str: String): InternetProtocolV6AddressData {
		val v6Resolution = tcpV6Resolution.resolve(
			str, "",
			StandardResolutionFeatures.NUMERIC_HOST
		)
		return if (v6Resolution.contains(StandardResolutionStatus.NAME_NOT_FOUND)) {
			val v4Resolution = tcpV4Resolution.resolve(
				str, "",
				StandardResolutionFeatures.NUMERIC_HOST
			)
			if (v4Resolution.contains(StandardResolutionStatus.NAME_NOT_FOUND)) throw IllegalArgumentException(
				"Failed to resolve \"${str}\" into a usable address."
			)
			InternetProtocolV6AddressData(
				v4Resolution.firstNotNullOf { r -> r as? ResolutionDataPart }.data
					.firstNotNullOf { d -> d as? InternetProtocolV4AddressData }
			)
		} else v6Resolution.firstNotNullOf { r -> r as? ResolutionDataPart }.data
			.firstNotNullOf { d -> d as? InternetProtocolV6AddressData }
	}

	val ban = Flag(
		"ban",
		"Bans a network address in ${BanMode.entries.size} mode${if (BanMode.entries.size != 1) 's' else ""}:" +
				BanMode.entries.joinToString {
					"\n\t${it.name}: ${it.description}"
				} +
				"\nFormat: MODE,ADDRESS",
		repeatable = true,
		conv = {
			val params = it.split(',')
			if (params.size != 2) throw IllegalArgumentException(
				"\"$it\": Ban parameter must be composed as \"MODE,ADDRESS\""
			)
			val mode = BanMode.valueOf(params[0])
			mode to parseAddress(params[1])
		}
	)
	val banFile = Flag<List<Pair<BanMode, InternetProtocolV6AddressData>>>(
		"ban_file",
		"Bans a list of network addresses using a plain-text file with the aforementioned modes." +
				"\nFormat: MODE,ADDRESS FILE" +
				"\n\tAddress File Format:ADDRESS[CR]<LF>..." +
				"\nFormat: ADDRESS FILE" +
				"\n\tAddress file Format:MODE<TAB>ADDRESS[CR]<LF>...",
		repeatable = true,
		conv = {
			val params = it.split(',')
			if (params.size > 2) throw IllegalArgumentException(
				"\"$it\": Ban file flag cannot have more than 2 parameters"
			)
			val addresses = mutableListOf<Pair<BanMode, InternetProtocolV6AddressData>>()
			if (params.size == 2) {
				val mode = BanMode.valueOf(params[0])
				val openData = SystemProvider.get(SystemFeatures.GET_CURRENT_WORKING_PATH_DEVICE).device
					.get(SystemDeviceFeatures.PATH_APPEND).append(params[1])
					.get(SystemDeviceFeatures.IO_DEVICE).open(FileIOReOpenFeatures.READ)
				val device = openData.firstNotNullOfOrNull { d -> d as? IODevice }
				if (device == null) throw IllegalArgumentException(
					"\"$it\": Device not found. Status: $openData"
				)
				try {
					val read = BSLReader(device.get(IODeviceFeatures.READ), fileReadCheck)
					var line = ""
					while (true) {
						val next = Char(read.readU8i())
						if (next == '\n') {
							addresses.add(mode to parseAddress(line.trim()))
							line = ""
						} else line += next
					}
				} catch (_: BSLIODataEnded) {
				}
				device.get(IODeviceFeatures.RELEASE).close()
			} else {
				val openData = SystemProvider.get(SystemFeatures.GET_CURRENT_WORKING_PATH_DEVICE).device
					.get(SystemDeviceFeatures.PATH_APPEND).append(params[0])
					.get(SystemDeviceFeatures.IO_DEVICE).open(FileIOReOpenFeatures.READ)
				val device = openData.firstNotNullOfOrNull { d -> d as? IODevice }
				if (device == null) throw IllegalArgumentException(
					"\"$it\": Device not found. Status: $openData"
				)
				try {
					val read = BSLReader(device.get(IODeviceFeatures.READ), fileReadCheck)
					var line = ""
					var mode: BanMode? = null
					while (true) {
						when (val next = Char(read.readU8i())) {
							'\n' -> {
								if (mode == null) throw IllegalArgumentException(
									"\"$it\": No mode, line contents: \"$line\""
								)
								addresses.add(mode to parseAddress(line.trim()))
								line = ""
							}

							'\t' -> {
								mode = BanMode.valueOf(line)
								line = ""
							}

							else -> line += next
						}
					}
				} catch (_: BSLIODataEnded) {
				}
				device.get(IODeviceFeatures.RELEASE).close()
			}
			addresses
		}
	)
	val maxSessionTime = Flag(
		"max_session_time",
		"Sets the maximum amount of time a RFB session may last.",
		default = 9000.toDuration(DurationUnit.SECONDS),
		conv = { Duration.parse(it) }
	)
	val maxInactivityTime = Flag(
		"max_inactivity_time",
		"Sets the maximum amount of time a RFB session may last without client intervention.",
		default = 9000.toDuration(DurationUnit.SECONDS),
		conv = { Duration.parse(it) }
	)
	val args = readArgs(
		args,
		"RFB Projection & Client Logging",
		"Projects an image onto a minimal RFB server, with the ability to log client messages.",
		port, portRange, name, imagePath,
		logTemplate, logConnectionEvents, logKeyEvents, logMouseButtonEvents,
		logMouseMovementEvents, logClipboardEvents, logInfoEvents,
		ban, banFile, maxSessionTime, maxInactivityTime
	)
	val serverName = args.getRequired(name)
	val ports = mutableSetOf<UShort>()
	ports.addAll(args.getsRequired(port))
	ports.addAll(args.gets(portRange)?.flatMap { it.map { ui -> ui.toUShort() } } ?: emptyList())
	val bans = mutableMapOf<BanMode, MutableList<InternetProtocolV6AddressData>>()
	val bansUnmerged = mutableSetOf<Pair<BanMode, InternetProtocolV6AddressData>>()
	bansUnmerged.addAll(args.gets(ban) ?: emptyList())
	bansUnmerged.addAll(args.gets(banFile)?.flatMap { it } ?: emptyList())
	bansUnmerged.forEach { (mode, address) ->
		bans.getOrPut(mode) { mutableListOf() }.add(address)
	}
	ports.forEach {
		Thread.ofPlatform().name("Thread Loop [$it]").start {
			threadLoop(
				tcpV6Resolution, tcpV6,
				it, serverName,
				bans
			)
		}
	}
	val connectOK = args.getRequired(logConnectionEvents)
	val mouseButtonOK = args.getRequired(logMouseButtonEvents)
	val mouseMouseOK = args.getRequired(logMouseMovementEvents)
	val keyOK = args.getRequired(logKeyEvents)
	val clipboardOK = args.getRequired(logClipboardEvents)
	val infoOK = args.getRequired(logInfoEvents)
	Thread.ofPlatform().name("Log Queue Manager").start {
		val openData = SystemProvider.get(SystemFeatures.GET_CURRENT_WORKING_PATH_DEVICE).device
			.get(SystemDeviceFeatures.PATH_APPEND).append("test.log")
			.get(SystemDeviceFeatures.IO_DEVICE).open(
				FileIOReOpenFeatures.WRITE,
				FileIOReOpenFeatures.SHARE_READ,
				StandardIOOpenFeatures.CREATE
			)
		val logDevice = openData.firstNotNullOfOrNull { it as? IODevice }
		if (logDevice == null) throw IllegalStateException(
			"Failed to open log for writing. Status: $openData"
		)
		val write = BSLWriter(logDevice.get(IODeviceFeatures.WRITE), { _, _ -> })
		val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSSxxxx")
		var lastMouseMask = 0
		while (true) {
			val nextLog = logQueue.take()
			fun writeLine(contents: String) {
				val then = nextLog.at.format(formatter)
				val label = "($serverName [${nextLog.managingPort}]) ${nextLog.address} @ $then : $contents\n"
				write.write(label.toByteArray(Charsets.UTF_8))
				write.flush()
			}

			when (nextLog) {
				is LogMessage.Mouse -> {
					if (mouseMouseOK) writeLine(
						"mouse: ${MouseState.entries.from(nextLog.mask)} @ ${nextLog.x}, ${nextLog.y}"
					) else if (mouseButtonOK && (lastMouseMask != nextLog.mask)) writeLine(
						"mouse buttons: ${MouseState.entries.from(nextLog.mask)} @ ${nextLog.x}, ${nextLog.y}"
					)
					lastMouseMask = nextLog.mask
				}

				is LogMessage.Key if keyOK -> {
					val keyLabel = when (nextLog.key) {
						0x00 -> "NUL"
						0x01 -> "SOH"
						0x02 -> "STX"
						0x03 -> "ETX"
						0x04 -> "EOT"
						0x05 -> "ENQ"
						0x06 -> "ACK"
						0x07 -> "BEL"
						0x08 -> "BS"
						0x09 -> "HT"
						0x0A -> "LF"
						0x0B -> "VT"
						0x0C -> "FF"
						0x0D -> "CR"
						0x0E -> "SO"
						0x0F -> "SI"
						0x10 -> "DLE"
						0x11 -> "DC1"
						0x12 -> "DC2"
						0x13 -> "DC3"
						0x14 -> "DC4"
						0x15 -> "NAK"
						0x16 -> "SYN"
						0x17 -> "ETB"
						0x18 -> "CAN"
						0x19 -> "EM"
						0x1A -> "SUB"
						0x1B -> "ESC"
						0x1C -> "FS"
						0x1D -> "GS"
						0x1E -> "RS"
						0x1F -> "US"
						0x7F -> "DEL"
						0xFF08 -> "Backspace"
						0xFF09 -> "Tab"
						0xFF0D -> "Enter"
						0xFF1B -> "Escape"
						0xFF63 -> "Insert"
						0xFFFF -> "Delete"
						0xFF50 -> "Home"
						0xFF57 -> "End"
						0xFF55 -> "Page Up"
						0xFF56 -> "Page Down"
						0xFF51 -> "Left"
						0xFF52 -> "Up"
						0xFF53 -> "Right"
						0xFF54 -> "Down"
						0xFFBE -> "Function 1"
						0xFFBF -> "Function 2"
						0xFFC0 -> "Function 3"
						0xFFC1 -> "Function 4"
						0xFFC2 -> "Function 5"
						0xFFC3 -> "Function 6"
						0xFFC4 -> "Function 7"
						0xFFC5 -> "Function 8"
						0xFFC6 -> "Function 9"
						0xFFC7 -> "Function 10"
						0xFFC8 -> "Function 11"
						0xFFC9 -> "Function 12"
						0xFFE1 -> "Left Shift"
						0xFFE2 -> "Right Shift"
						0xFFE3 -> "Left Control"
						0xFFE4 -> "Right Control"
						0xFFE7 -> "Left Meta"
						0xFFE8 -> "Right Meta"
						0xFFE9 -> "Left Alt"
						0xFFEA -> "Right Alt"
						else -> if (nextLog.key < 256) Char(nextLog.key).toString()
						else "???"
					}
					writeLine("key: $keyLabel [${nextLog.key}] ${if (nextLog.down) "down" else "up"}")
				}

				is LogMessage.Info if infoOK -> writeLine("general info: \"${nextLog.label}\"")
				is LogMessage.Connect if connectOK -> writeLine("connect info: \"${nextLog.label}\"")
				is LogMessage.Disconnect if connectOK -> writeLine(
					"disconnect info ${if (nextLog.serverInitiated) "(server" else "(client"} initiated): " +
							"\"${nextLog.reason}\""
				)

				is LogMessage.Clipboard if clipboardOK -> writeLine("clipboard: \"${nextLog.data}\"")
				else -> {}
			}
		}
	}
}