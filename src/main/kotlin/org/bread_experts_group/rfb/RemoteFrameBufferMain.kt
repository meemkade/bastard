package org.bread_experts_group.rfb

import org.bread_experts_group.Flaggable.Companion.from
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
import org.bread_experts_group.rfb.PixelFormatDataStructure.Companion.nextPixelFormatStructure
import org.bread_experts_group.rfb.PixelFormatDataStructure.Companion.writePixelFormatStructure
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private val logQueue = LinkedBlockingQueue<LogMessage>()

fun client(
	name: String, addressData: InternetProtocolV6AddressPortData,
	read: BSLReader<*, *>, write: BSLWriter<*, *>, serverPort: UShort,
	frames: List<ImageFrame>
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
	bans: Map<BanMode, List<InternetProtocolV6AddressData>>, frames: List<ImageFrame>
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
				client(serverName, acceptedAddress, read, write, port, frames)
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
		"A port to use for listening on the RFB server." +
		"\nFormat: PORT" +
		"\nFormat: PORT,IMAGE" +
		"\n\tThe image used by the above format is defined by the default_image flag.",
		true,
		default = 5900u.toUShort() to emptyList(),
		conv = {
			val imageSplit = it.split(',', limit = 2)
			portEffector(imageSplit[0]) to (if (imageSplit.size == 2) decodePNG(imageSplit[1]) else emptyList())
		}
	)
	val portRange = Flag(
		"port_range",
		"A range of ports to use for listening on the RFB server." +
		"\nFormat: LOWERPORT-UPPERPORT" +
		"\nFormat: LOWERPORT-UPPERPORT,IMAGE" +
		"\n\tThe image used by the above format is defined by the default_image flag.",
		true,
		conv = {
			val imageSplit = it.split(',', limit = 1)
			val portSplit = imageSplit[0].split('-')
			if (portSplit.size != 2) throw IllegalArgumentException(
				"\"$it\": Port range must consist of a lower and upper bound, like so: \"5900-5910\""
			)
			portEffector(portSplit[0])..portEffector(portSplit[1]) to
					(if (imageSplit.size == 2) decodePNG(imageSplit[1]) else emptyList())
		}
	)
	val name = Flag(
		"server_name",
		"The server name transmitted with each connection.",
		default = "BSLSTD_${bslVersion()}",
		required = 1
	)
	val defaultImagePath = Flag(
		"default_image",
		"The image to transmit over the RFB connection." +
		"\nThis flag is used when the port / port_range flags do not have a set image.",
		conv = ::decodePNG
	)
	val logTemplate = Flag(
		"log_template",
		"The name template to use for logging user actions." +
		"\nExample Templates:" +
		"\n\texample.log" +
		"\n\tVNC_{datetime:yyyy-MM-dd}.log" +
		"\nSupported Templates:" +
		"\n\tdatetime: See https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/format/DateTimeFormatter.html" +
		"\nTemplates are used in names with the following format:" +
		"\n\t{TEMPLATE[:TEMPLATE PARAMETER]}" +
		"\n\tWhere the file system allows, you can use \\ to escape the {}, or \\ itself.",
		conv = { template ->
			{
				var string = ""
				var templateName = ""
				var templateParameter = ""
				var escaping = false
				var inTemplate = false
				var inTemplateName = false
				for (char in template) if (!inTemplate) {
					if (escaping) {
						escaping = false
						string += char
						continue
					}
					when (char) {
						'\\' -> escaping = true
						'{' -> {
							inTemplate = true
							inTemplateName = true
						}
						else -> string += char
					}
				} else when (char) {
					'}' -> {
						inTemplate = false
						inTemplateName = false
						when (templateName.lowercase()) {
							"datetime" -> {
								val formatter = DateTimeFormatter.ofPattern(templateParameter)
								string += ZonedDateTime.now().format(formatter)
							}

							else -> throw IllegalArgumentException("\"$template\": Unsupported template: $templateName")
						}
						templateName = ""
						templateParameter = ""
					}

					':' -> inTemplateName = false
					else -> if (inTemplateName) templateName += char else templateParameter += char
				}
				string
			}
		}
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
		port, portRange, name, defaultImagePath,
		logTemplate, logConnectionEvents, logKeyEvents, logMouseButtonEvents,
		logMouseMovementEvents, logClipboardEvents, logInfoEvents,
		ban, banFile, maxSessionTime, maxInactivityTime
	)
	val serverName = args.getRequired(name)
	val ports = mutableMapOf<UShort, MutableList<ImageFrame>>()
	args.getsRequired(port).forEach { (port, frames) ->
		ports.getOrPut(port) { mutableListOf() }.addAll(frames)
	}
	args.gets(portRange)?.forEach { (portRange, frames) ->
		portRange.forEach { port ->
			ports.getOrPut(port.toUShort()) { mutableListOf() }.addAll(frames)
		}
	}
	val default = args.get(defaultImagePath)
	val bans = mutableMapOf<BanMode, MutableList<InternetProtocolV6AddressData>>()
	val bansUnmerged = mutableSetOf<Pair<BanMode, InternetProtocolV6AddressData>>()
	bansUnmerged.addAll(args.gets(ban) ?: emptyList())
	bansUnmerged.addAll(args.gets(banFile)?.flatMap { it } ?: emptyList())
	bansUnmerged.forEach { (mode, address) ->
		bans.getOrPut(mode) { mutableListOf() }.add(address)
	}
	ports.forEach { (port, frames) ->
		Thread.ofPlatform().name("Thread Loop [$port; ${frames.size} frame(s)]").start {
			threadLoop(
				tcpV6Resolution, tcpV6,
				port, serverName,
				bans, frames.ifEmpty {
					default ?: throw IllegalArgumentException(
						"You must specify the default_image flag; port $port is missing an image."
					)
				}
			)
		}
	}
	val logTemplater = args.get(logTemplate) ?: return
	val connectOK = args.getRequired(logConnectionEvents)
	val mouseButtonOK = args.getRequired(logMouseButtonEvents)
	val mouseMouseOK = args.getRequired(logMouseMovementEvents)
	val keyOK = args.getRequired(logKeyEvents)
	val clipboardOK = args.getRequired(logClipboardEvents)
	val infoOK = args.getRequired(logInfoEvents)
	Thread.ofPlatform().name("Log Queue Manager").start {
		var openTemplate: String? = null
		var openWriter: BSLWriter<*, *>? = null
		var openDevice: IODevice? = null

		@Suppress("AssignedValueIsNeverRead")
		fun writer(): BSLWriter<*, *> {
			val template = logTemplater()
			if (template == openTemplate) return openWriter!!
			openTemplate = template
			openDevice?.get(IODeviceFeatures.RELEASE)?.close()
			val openData = SystemProvider.get(SystemFeatures.GET_CURRENT_WORKING_PATH_DEVICE).device
				.get(SystemDeviceFeatures.PATH_APPEND).append(template)
				.get(SystemDeviceFeatures.IO_DEVICE).open(
					FileIOReOpenFeatures.WRITE,
					FileIOReOpenFeatures.SHARE_READ,
					StandardIOOpenFeatures.CREATE
				)
			val logDevice = openData.firstNotNullOfOrNull { it as? IODevice }
			if (logDevice == null) throw IllegalStateException(
				"Failed to open log for writing. Status: $openData"
			)
			openDevice = logDevice
			val writer = BSLWriter(logDevice.get(IODeviceFeatures.WRITE), { _, _ -> })
			openWriter = writer
			return writer
		}
		val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSSxxxx")
		var lastMouseMask = 0
		while (true) {
			val nextLog = logQueue.take()
			fun writeLine(contents: String) {
				val then = nextLog.at.format(formatter)
				val label = "($serverName [${nextLog.managingPort}]) ${nextLog.address} @ $then : $contents\n"
				writer().write(label.toByteArray(Charsets.UTF_8))
				writer().flush()
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