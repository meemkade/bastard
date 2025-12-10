package org.bread_experts_group.project_incubator.rfb

enum class BanMode(val description: String) {
	CLOSE("Immediately closes the connection after acceptance, attempting a graceful closure."),
	RESET("Immediately resets the connection after acceptance.")
}