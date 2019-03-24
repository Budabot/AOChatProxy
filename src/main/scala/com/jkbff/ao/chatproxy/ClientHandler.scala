package com.jkbff.ao.chatproxy

import java.net.Socket
import java.util.concurrent.{Callable, LinkedBlockingQueue}

import aoChatLib.Crypto
import com.jkbff.ao.tyrlib.chat.socket._
import com.jkbff.ao.tyrlib.packets.{client, server}
import org.apache.log4j.Logger._

import scala.collection.mutable

class ClientHandler(botInfo: Seq[(String, BotLoginInfo)], serverAddress: String, serverPort: Int, socket: Socket, spamBotSupport: Boolean, masterBotAuthPassthrough: Boolean) extends Thread with Closeable {
	private val logger = getLogger("com.jkbff.ao.chatproxy.ClientHandler")

	val clientPacketFactory = new BasicClientPacketFactory
	val serverPacketFactory = new BasicServerPacketFactory

	val bots: Map[String, BotManager] = getBotManagers(if (masterBotAuthPassthrough) botInfo else botInfo.tail)

	val masterBot = new AOServerSocket("master", socket, clientPacketFactory, this)
	val buddyList = new mutable.HashMap[BotManager, mutable.Set[Long]]
	var shouldStop = false

	private val buddyListTaskQueue = new LinkedBlockingQueue[Callable[Unit]]()
	private val buddyListTaskListener = new BuddyListTaskListener(buddyListTaskQueue, this)

	private lazy val privateMessageQueue = new LinkedBlockingQueue[client.PrivateMessageSend]()
	private lazy val privateMessageListeners =
		bots.filter(_._1 != "main").map{case (_, bot) => new PrivateMessageListener(bot, privateMessageQueue)}

	// masterBot - connection with bot (the client)
	// mainBot - connection with AO (the server)

	override def run(): Unit = {
		try {
			masterBot.start()
			bots("main").start()
			if (spamBotSupport) {
				if (privateMessageListeners.isEmpty) {
					throw new Exception("at least one slave bot besides the main bot is required for spam bot support")
				}
				privateMessageListeners.foreach(_.start())
			}
			buddyListTaskListener.start()

			while (!shouldStop) {
				val packet = masterBot.readPacket()
				if (packet != null) {
					logger.debug("FROM MASTER " + packet)
					packet match {
						case p: client.LoginSelect =>
							sendPacketToServer(p, botId = "main")
							startSlaveBots()
						case p: client.BuddyAdd =>
							addBuddy(p)
						case p: client.BuddyRemove =>
							remBuddy(p)
						case p: client.PrivateMessageSend if spamBotSupport && p.getRaw == "spam" =>
							sendSpamTell(new client.PrivateMessageSend(p.getCharId, p.getMessage, "\0"))
						case _: client.LoginRequest if !masterBotAuthPassthrough =>
							// ignore LoginRequest since the correct version has already been sent
						case _ =>
							sendPacketToServer(packet, botId = "main")
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
		buddyListTaskQueue.add(() => f())
	}

	private def addBuddy(packet: client.BuddyAdd): Unit = {
		addBuddyListTask { () =>
			val (bot, buddies) = buddyList.find { x: (BotManager, mutable.Set[Long]) =>
				x._2.contains(packet.getCharId)
			}.getOrElse {
				buddyList.minBy(_._2.size)
			}

			if (buddies.size < 1000) {
				logger.debug("buddy added to " + bot.id + ": " + packet)
				buddyList(bot) += packet.getCharId
				bot.sendPacket(packet)
			} else {
				logger.warn("No bots available with room to add buddy")
			}
		}
	}

	private def remBuddy(packet: client.BuddyRemove): Unit = {
		addBuddyListTask { () =>
			buddyList.filter(_._2.contains(packet.getCharId)).foreach { case (bot, _) =>
				logger.debug("buddy removed from " + bot.id + ": " + packet)
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
			case p: server.LoginSeed if !masterBotAuthPassthrough && bot.id == "main" =>
				// send correct LoginRequest, but forward packet to masterBot so login handshake is continued
				sendPacketToMasterBot(p)
				val loginInfo = botInfo.head._2
				val loginKey = Crypto.generateKey(loginInfo.username, loginInfo.password, p.getSeed)
				val loginRequest = new client.LoginRequest(0, loginInfo.username, loginKey)
				sendPacketToServer(loginRequest, botId = "main")
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

	def getBotManagers(botInfo: Seq[(String, BotLoginInfo)]): Map[String, BotManager] = {
		botInfo.map { case (id, info) =>
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
		}.toMap + (("main", new BotManager("main", serverAddress, serverPort, serverPacketFactory, this)))
	}
}