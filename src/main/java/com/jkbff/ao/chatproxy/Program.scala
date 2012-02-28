package com.jkbff.ao.chatproxy

import java.io.FileInputStream
import java.util.Properties
import java.net.ServerSocket
import java.net.Socket

import org.apache.log4j.Logger
import org.apache.log4j.Logger._
import org.apache.log4j.PropertyConfigurator

import scala.collection.mutable.HashMap

import com.jkbff.ao.tyrlib.chat.AOConnection
import com.jkbff.ao.tyrlib.packets.BaseClientPacket
import com.jkbff.ao.tyrlib.chat.MMDBParser

object Program {
	private var bots = new HashMap[String, AOConnection]
	
	private var log = getLogger("com.jkbff.ao.chatproxy.Program")
	
	def main(args:Array[String]) {
		Program.run()
	}

	def run() : Unit = {
		// tell the lib where to find the text.mdb file
		MMDBParser.fileLocation = "text.mdb";
		
		// initialize the log4j component
		PropertyConfigurator.configure("log4j.properties");
		
		// load the properties file for the bot info
		var properties = new Properties();
		properties.load(new FileInputStream("chatbot.properties"));
		
		var serverSocket = new ServerSocket(properties.getProperty("proxyPortNumber").toInt)

		var socket:Socket = null
		while (true) {
			try {
				log.info("Ready to accept a master bot on port " + properties.getProperty("proxyPortNumber"))

				socket = serverSocket.accept
				
				var clientHandler = new ClientHandler
				clientHandler.mainBotCharacter = properties.getProperty("bot1_characterName")
	
				createBots(properties);
				for (bot <- bots.values) {
					bot.setChatPacketHandler(clientHandler)
				}
	
				clientHandler.socket = socket
				clientHandler.bots = bots;
				clientHandler.setName("clientHandler")
				clientHandler.start
				
				Thread.sleep(5000)
				
				// check to make sure everything is running
				var running = true
				while (running) {
					if (!clientHandler.isAlive()) {
						running = false;
					}
					for (aoBot <- bots.values) {
						if (!aoBot.isAlive()) {
							running = false;
						}
					}
					Thread.sleep(5000)
				}
			} catch {
				case e:Exception => {
					socket.close
					log.error("master bot connection ended", e)
				}
			}
			
			shutdownBots
			socket.close
		}
	}
	
	def createBots(properties:Properties): Unit = {
		var count = 1
		
		while (properties.getProperty("bot" + count + "_characterName") != null) {
			var botName = "bot" + count + "_";
			
			// create the connection
			var conn = new AOConnection();
			
			// set the login info
			var id = "main"
			if (count != 1) {
				id = "slave" + (count - 1)
			}
			conn.setCharacter(properties.getProperty(botName + "characterName"));
			conn.setPassword(properties.getProperty(botName + "password"));
			conn.setPortNumber(Integer.parseInt(properties.getProperty("serverPortNumber")));
			conn.setServerName(properties.getProperty("serverAddress"));
			conn.setUsername(properties.getProperty(botName + "username"));
			conn.setName(id);
			bots.put(id, conn);
			
			log.debug("created proxy bot " + properties.getProperty(botName + "characterName"))
			
			count += 1
		}
	}
	
	def shutdownBots() : Unit = {
		// shutdown any currently running bots
		if (bots != null) {
			for (aoBot <- bots.values) {
				aoBot.shutdown();
			}
		}

		bots = new HashMap[String, AOConnection];
	}
}