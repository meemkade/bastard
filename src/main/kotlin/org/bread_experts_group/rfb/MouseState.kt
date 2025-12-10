package org.bread_experts_group.project_incubator.rfb

import org.bread_experts_group.Flaggable

enum class MouseState : Flaggable {
	LEFT,
	MIDDLE,
	RIGHT,
	WHEEL_UP,
	WHEEL_DOWN,
	BUTTON_6,
	BUTTON_7,
	BUTTON_8;

	override val position: Long = 1L shl ordinal
}