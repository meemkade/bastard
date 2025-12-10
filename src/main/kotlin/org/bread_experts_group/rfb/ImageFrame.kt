package org.bread_experts_group.rfb

import kotlin.time.Duration

data class ImageFrame(
	val x: Int,
	val y: Int,
	val w: Int,
	val h: Int,
	val data: IntArray,
	val delay: Duration
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as ImageFrame

		if (x != other.x) return false
		if (y != other.y) return false
		if (w != other.w) return false
		if (h != other.h) return false
		if (!data.contentEquals(other.data)) return false
		if (delay != other.delay) return false

		return true
	}

	override fun hashCode(): Int {
		var result = x
		result = 31 * result + y
		result = 31 * result + w
		result = 31 * result + h
		result = 31 * result + data.contentHashCode()
		result = 31 * result + delay.hashCode()
		return result
	}
}
