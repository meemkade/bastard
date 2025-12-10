package org.bread_experts_group.rfb

import org.bread_experts_group.api.system.socket.ipv6.InternetProtocolV6AddressPortData
import java.time.ZonedDateTime

sealed class LogMessage(
	val address: InternetProtocolV6AddressPortData,
	val managingPort: UShort
) {
	val at: ZonedDateTime = ZonedDateTime.now()

	class Connect(
		val label: String,
		address: InternetProtocolV6AddressPortData,
		managingPort: UShort
	) : LogMessage(address, managingPort)

	class Disconnect(
		val serverInitiated: Boolean,
		val reason: String,
		address: InternetProtocolV6AddressPortData,
		managingPort: UShort
	) : LogMessage(address, managingPort)

	class Info(
		val label: String,
		address: InternetProtocolV6AddressPortData,
		managingPort: UShort
	) : LogMessage(address, managingPort)

	class Key(
		val down: Boolean,
		val key: Int,
		address: InternetProtocolV6AddressPortData,
		managingPort: UShort
	) : LogMessage(address, managingPort)

	class Mouse(
		val mask: Int,
		val x: Int,
		val y: Int,
		address: InternetProtocolV6AddressPortData,
		managingPort: UShort
	) : LogMessage(address, managingPort)

	class Clipboard(
		val data: String,
		address: InternetProtocolV6AddressPortData,
		managingPort: UShort
	) : LogMessage(address, managingPort)
}