package com.jkbff.ao.chatproxy

import java.io.FileInputStream
import java.util.Properties
import java.net.ServerSocket
import java.net.Socket

import org.apache.log4j.Logger
import org.apache.log4j.Logger._
import org.apache.log4j.PropertyConfigurator

import scala.collection.mutable.HashMap

import com.jkbff.ao.tyrlib.chat.{ AOSocket, AOSocketInfo }
import com.jkbff.ao.tyrlib.packets.BaseClientPacket
import com.jkbff.ao.tyrlib.chat.MMDBParser

object Program {
	private var bots: HashMap[String, AOSocket] = null

	private val log = getLogger("com.jkbff.ao.chatproxy.Program")

	def main(args: Array[String]) {
		Program.run()
	}

	def run(): Unit = {
		// tell the lib where to find the text.mdb file
		MMDBParser.fileLocation = "text.mdb";

		// initialize the log4j component
		PropertyConfigurator.configure("log4j.xml");

		// load the properties file for the bot info
		val properties = new Properties();
		properties.load(new FileInputStream("chatbot.properties"));

		val serverSocket = new ServerSocket(properties.getProperty("proxyPortNumber").toInt)

		var socket: Socket = null
		while (true) {
			try {
				log.info("Ready to accept a master bot on port " + properties.getProperty("proxyPortNumber"))

				socket = serverSocket.accept

				val clientHandler = new ClientHandler
				clientHandler.mainBotCharacter = properties.getProperty("bot1_characterName")

				bots = createBots(properties, clientHandler);

				clientHandler.socket = socket
				clientHandler.bots = bots;
				clientHandler.setName("clientHandler")
				clientHandler.start

				// check to make sure everything is running
				do {
					Thread.sleep(5000)
				} while (clientHandler.isRunning)
			} catch {
				case e: Exception => {
					socket.close
					log.error("master bot connection ended", e)
				}
			}

			shutdownBots
			socket.close
		}
	}

	def createBots(properties: Properties, clientHandler: ClientHandler): HashMap[String, AOSocket] = {
		var count = 1
		val bots = new HashMap[String, AOSocket];

		while (properties.getProperty("bot" + count + "_characterName") != null) {
			val botName = "bot" + count + "_";

			// create the connection
			val conn = new AOSocket(new AOSocketInfo(
				properties.getProperty(botName + "username"),
				properties.getProperty(botName + "password"),
				properties.getProperty(botName + "characterName"),
				properties.getProperty("serverAddress"),
				Integer.parseInt(properties.getProperty("serverPortNumber"))), clientHandler);

			// set the login info
			val id = if (count != 1) "slave" + (count - 1) else "main"
			conn.setName(id);
			bots.put(id, conn);

			log.debug("created proxy bot " + properties.getProperty(botName + "characterName"))

			count += 1
		}
		bots
	}

	def shutdownBots(): Unit = {
		// shutdown any currently running bots
		if (bots != null) {
			bots.values.par.foreach(bot => bot.shutdown)
		}
	}
}