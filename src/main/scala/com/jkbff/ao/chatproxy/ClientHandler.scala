package com.jkbff.ao.chatproxy

import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

import com.jkbff.ao.tyrlib.chat.socket._
import com.jkbff.ao.tyrlib.packets.{client, server}
import org.apache.log4j.Logger._

import scala.collection.mutable

class ClientHandler(botInfo: Map[String, BotLoginInfo], serverAddress: String, serverPort: Int, socket: Socket, spamBotSupport: Boolean) extends Thread with Closeable {
	private val logger = getLogger("com.jkbff.ao.chatproxy.ClientHandler")

	val clientPacketFactory = new BasicClientPacketFactory
	val serverPacketFactory = new BasicServerPacketFactory

	val bots: Map[String, BotManager] = botInfo.map { case (id, info) =>
		(id,
			new SlaveBotManager(
				id,
				info.username,
				info.password,
				info.characterName,
				serverAddress,
				serverPort,
				serverPacketFactory,
				this))
	} + (("main", new BotManager("main", serverAddress, serverPort, serverPacketFactory, this)))

	val masterBot = new AOServerSocket("master", socket, clientPacketFactory, this)
	val buddyList = new mutable.HashMap[BotManager, mutable.Set[Long]]
	var shouldStop = false

	private val buddyListTaskQueue = new LinkedBlockingQueue[() => Unit]()
	private val buddyListTaskListener = new BuddyListTaskListener(buddyListTaskQueue, this)

	private lazy val privateMessageQueue = new LinkedBlockingQueue[client.PrivateMessageSend]()
	private lazy val privateMessageListeners =
		bots.filter(_._1 != "main").map{case (_, bot) => new PrivateMessageListener(bot, privateMessageQueue)}

	// masterBot - connection to Budabot (the client)
	// mainBot - connection to AO (the server)

	override def run(): Unit = {
		try {
			bots("main").start()
			masterBot.start()
			if (spamBotSupport) {
				privateMessageListeners.foreach(_.start())
			}
			buddyListTaskListener.start()

			while (!shouldStop) {
				val packet = masterBot.readPacket()
				if (packet != null) {
					logger.debug("FROM MASTER " + packet)
					packet match {
						case p: client.LoginSelect =>
							sendPacketToServer(p, "main")
							startSlaveBots()
						case p: client.BuddyAdd =>
							addBuddy(p)
						case p: client.BuddyRemove =>
							remBuddy(p)
						case p: client.PrivateMessageSend if spamBotSupport && p.getRaw == "spam" =>
							sendSpamTell(p)
						case _ =>
							sendPacketToServer(packet, "main")
					}
				}
			}
		} catch {
			case e: Exception =>
				logger.error("", e)
		} finally {
			close()
		}
	}

	def addBuddyListTask(f: () => Unit): Unit = {
		buddyListTaskQueue.add(f)
	}

	private def addBuddy(packet: client.BuddyAdd): Unit = {
		addBuddyListTask { () =>
			logger.info("adding buddy: " + packet.getCharId)
			val (bot, buddies) = buddyList.find { x =>
				x._2.contains(packet.getCharId)
			}.getOrElse {
				buddyList.minBy(_._2.size)
			}

			if (buddies.size < 1000) {
				logger.debug("buddy added to " + bot.id)
				buddyList(bot) += packet.getCharId
				bot.sendPacket(packet)
			} else {
				logger.warn("Not bots available with room to add buddy")
			}
		}
	}

	private def remBuddy(packet: client.BuddyRemove): Unit = {
		addBuddyListTask { () =>
			logger.info("removing buddy: " + packet.getCharId)
			buddyList.filter(_._2.contains(packet.getCharId)).foreach { case (bot, _) =>
				logger.debug("buddy removed from " + bot.id)
				bot.sendPacket(packet)
			}
		}
	}

	def sendSpamTell(packet: client.PrivateMessageSend): Unit = {
		privateMessageQueue.add(packet)
	}

	def sendPacketToMasterBot(packet: server.BaseServerPacket): Unit = {
		logger.debug("TO MASTER " + packet)
		masterBot.sendPacket(packet)
	}

	def startSlaveBots(): Unit = {
		addBuddyListTask { () =>
			bots.foreach{ case (id, bot) =>
				buddyList(bot) = mutable.Set[Long]()
				if (id != "main") {
					logger.info("starting proxy bot " + id)
					bot.start()
				}
			}
		}
	}

	def sendPacketToServer(packet: client.BaseClientPacket, botId: String): Unit = {
		bots(botId).sendPacket(packet)
	}

	def buddyAdded(packet: server.BuddyAdded, bot: BotManager): Unit = {
		addBuddyListTask { () =>
			logger.info("buddy added: " + packet.getCharId)
			// remove buddy from other bots if it exists on them
			buddyList.filter(x => x._1 != bot && x._2.contains(packet.getCharId)).foreach { x =>
				logger.info("duplicate buddy detected and removed on " + x._1.id + ": " + packet)
				buddyList(x._1).remove(packet.getCharId)
				x._1.sendPacket(new client.BuddyRemove(packet.getCharId))
			}

			// forward packet to master bot
			buddyList(bot) += packet.getCharId
			sendPacketToMasterBot(packet)
		}
	}

	def buddyRemoved(packet: server.BuddyRemoved, bot: BotManager): Unit = {
		addBuddyListTask { () =>
			logger.info("buddy removed: " + packet.getCharId)
			if (buddyList(bot).contains(packet.getCharId)) {
				buddyList(bot).remove(packet.getCharId)
				sendPacketToMasterBot(packet)
			}
		}
	}

	def processPacket(packet: server.BaseServerPacket, bot: BotManager): Unit = {
		packet match {
			case p: server.LoginOk =>
        // send login ok
        sendPacketToMasterBot(p)
			case p: server.BuddyAdded =>
				buddyAdded(p, bot)
			case p: server.BuddyRemoved =>
				buddyRemoved(p, bot)
			case p: server.CharacterUpdate =>
				sendPacketToMasterBot(p)
			case _ if bot.id == "main" =>
				// if packet came from main bot connection and
				// if packet isn't a packet that requires special handling (cases above)
				// then forward to master bot
				sendPacketToMasterBot(packet)
			case _ =>
				// ignore packets from bots that aren't main and aren't already handled
		}
	}

	def close(): Unit = {
		if (!shouldStop) {
			shouldStop = true
			logger.warn("closing client handler")
			if (spamBotSupport) {
				privateMessageListeners.foreach(_.close())
			}
			bots.foreach(_._2.close())
			masterBot.close()
			buddyListTaskListener.close()
		}
	}

	def isRunning(): Boolean = {
		this.isAlive && bots.values.forall(_.isAlive)
	}
}