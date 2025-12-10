plugins {
	kotlin("jvm") version "2.2.21"
	application // internal testing only
}

application {
	mainClass = "org.bread_experts_group.rfb.RemoteFrameBufferMainKt"
	applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

group = "org.bread_experts_group"
version = "1.0.0"

repositories {
	mavenCentral()
	mavenLocal()
}

dependencies {
	testImplementation(kotlin("test"))
	implementation("org.bread_experts_group:bread_server_lib-code:D0F2N2P5")
}

tasks.test {
	useJUnitPlatform()
}
kotlin {
	jvmToolchain(24)
}