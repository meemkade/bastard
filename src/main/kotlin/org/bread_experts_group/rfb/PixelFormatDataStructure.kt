package org.bread_experts_group.project_incubator.rfb

import org.bread_experts_group.io.reader.BSLReader
import org.bread_experts_group.io.reader.BSLWriter

data class PixelFormatDataStructure(
	val bitsPerPixel: Int,
	val depth: Int,
	val bigEndian: Boolean,
	val trueColor: Boolean,
	val redMax: Int,
	val greenMax: Int,
	val blueMax: Int,
	val redShift: Int,
	val greenShift: Int,
	val blueShift: Int
) {
	companion object {
		fun writePixelFormatStructure(write: BSLWriter<*, *>, n: PixelFormatDataStructure) {
			write.write8i(n.bitsPerPixel)
			write.write8i(n.depth)
			write.write8i(if (n.bigEndian) 1 else 0)
			write.write8i(if (n.trueColor) 1 else 0)
			write.write16i(n.redMax)
			write.write16i(n.greenMax)
			write.write16i(n.blueMax)
			write.write8i(n.redShift)
			write.write8i(n.greenShift)
			write.write8i(n.blueShift)
			write.fill(3)
		}

		fun nextPixelFormatStructure(read: BSLReader<*, *>): PixelFormatDataStructure {
			val structure = PixelFormatDataStructure(
				read.readU8i(),
				read.readU8i(),
				read.readU8i() != 0,
				read.readU8i() != 0,
				read.readU16i(),
				read.readU16i(),
				read.readU16i(),
				read.readU8i(),
				read.readU8i(),
				read.readU8i()
			)
			read.skip(3)
			return structure
		}
	}
}