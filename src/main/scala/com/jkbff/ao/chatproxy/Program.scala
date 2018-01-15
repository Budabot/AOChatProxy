package com.jkbff.ao.chatproxy

import java.net.{ServerSocket, Socket}
import java.util.Properties

import org.apache.log4j.Logger._

object Program {
	private val log = getLogger("com.jkbff.ao.chatproxy.Program")
	var clientHandler: ClientHandler = _

	def main(args: Array[String]) {
		Program.run()
	}

	def run(): Unit = {
		// load the properties file for the bot info
		val properties = new Properties()
		properties.load(this.getClass.getResourceAsStream("/config.properties"))

		val serverSocket = new ServerSocket(properties.getProperty("proxyPortNumber").toInt)

		var socket: Socket = null
		while (true) {
			try {
				log.info("Ready to accept a master bot on port " + properties.getProperty("proxyPortNumber"))

				socket = serverSocket.accept

				log.info("Master bot accepted")

				val slaves = getSlaveInfo(properties)

				log.info(s"Read ${slaves.size} slaves from config")

				clientHandler = new ClientHandler(
					slaves,
					properties.getProperty("serverAddress"),
					Integer.parseInt(properties.getProperty("serverPortNumber")),
					socket)

				clientHandler.setName("clientHandler")
				clientHandler.start()

				// check to make sure everything is running
				do {
					Thread.sleep(5000)
				} while (clientHandler.isRunning())
			} catch {
				case e: Exception =>
					log.error("master bot connection ended", e)
			}

			clientHandler.close()
		}
	}

	def getSlaveInfo(properties: Properties): Map[String, BotLoginInfo] = {
		Iterator.from(1).takeWhile(id => properties.getProperty("slave" + id + "_characterName") != null).map{ idx =>
			"slave" + idx ->
				BotLoginInfo(
					properties.getProperty("slave" + idx + "_username"),
					properties.getProperty("slave" + idx + "_password"),
					properties.getProperty("slave" + idx + "_characterName"))
		}.toMap
	}
}