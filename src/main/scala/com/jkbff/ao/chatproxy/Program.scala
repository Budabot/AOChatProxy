package com.jkbff.ao.chatproxy

import java.net.{ServerSocket, Socket}
import java.util.Properties

import com.jkbff.ao.tyrlib.chat.{AOSocket, AOSocketExtended, AOSocketInfo, MMDBParser}
import org.apache.log4j.Logger._

object Program {
	private var bots: Map[String, AOSocket] = _

	private val log = getLogger("com.jkbff.ao.chatproxy.Program")

	def main(args: Array[String]) {
		Program.run()
	}

	def run(): Unit = {
		// tell the lib where to find the text.mdb file
		MMDBParser.fileLocation = "text.mdb"

		// load the properties file for the bot info
		val properties = new Properties()
		properties.load(this.getClass.getResourceAsStream("/config.properties"))

		val serverSocket = new ServerSocket(properties.getProperty("proxyPortNumber").toInt)

		var socket: Socket = null
		while (true) {
			try {
				log.info("Ready to accept a master bot on port " + properties.getProperty("proxyPortNumber"))

				socket = serverSocket.accept

				val clientHandler = new ClientHandler

				bots = createBots(properties, clientHandler)

				clientHandler.socket = socket
				clientHandler.bots = bots
				clientHandler.setName("clientHandler")
				clientHandler.start()

				// check to make sure everything is running
				do {
					Thread.sleep(5000)
				} while (clientHandler.isRunning)
			} catch {
				case e: Exception =>
					socket.close()
					log.error("master bot connection ended", e)
			}

			shutdownBots()
			socket.close()
		}
	}

	def createBots(properties: Properties, clientHandler: ClientHandler): Map[String, AOSocket] = {
		val serverAddress = properties.getProperty("serverAddress")
		val serverPort = Integer.parseInt(properties.getProperty("serverPortNumber"))

		val mainBot = new AOSocketExtended(new AOSocketInfo("", "", "", serverAddress, serverPort), clientHandler)

		Iterator.from(1).takeWhile(id => properties.getProperty("slave" + id + "_characterName") != null).map{ id =>
			val username = properties.getProperty("slave" + id + "_username")
			val password = properties.getProperty("slave" + id + "_password")
			val characterName = properties.getProperty("slave" + id + "_characterName")

			// create the connection
			val conn = new AOSocket(
				new AOSocketInfo(
					username,
					password,
					characterName,
					serverAddress,
					serverPort),
				clientHandler)

			// set the login info
			val name = "slave" + id

			log.debug("initialized character '" + characterName + "' as " + name)

			(name, conn)
		}.toMap + ("main" -> mainBot)
	}

	def shutdownBots(): Unit = {
		// shutdown any currently running bots
		if (bots != null) {
			bots.values.par.foreach(bot => bot.shutdown())
		}
	}
}