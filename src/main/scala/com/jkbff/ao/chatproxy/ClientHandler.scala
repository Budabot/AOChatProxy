package com.jkbff.ao.chatproxy

import java.net.Socket

import com.jkbff.ao.tyrlib.chat.socket._
import com.jkbff.ao.tyrlib.packets.client.{BaseClientPacket, FriendRemove, FriendUpdate, LoginSelect}
import com.jkbff.ao.tyrlib.packets.server
import com.jkbff.ao.tyrlib.packets.server.BaseServerPacket
import org.apache.log4j.Logger._

import scala.collection.mutable

class ClientHandler(botInfo: Map[String, BotLoginInfo], serverAddress: String, serverPort: Int, socket: Socket) extends Thread with Closeable {
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

	// masterBot - connection to running Budabot (all requests originate from here)
	// mainBot - connection to AO Servers on behalf of masterBot/Budabot (most requests that aren't buddy-related go to this connection)

	override def run(): Unit = {
		try {
			bots("main").start()
			masterBot.start()

			while (!shouldStop) {
				val packet = masterBot.readPacket()
				if (packet != null) {
					logger.debug("FROM MASTER " + packet)
					packet match {
						case p: LoginSelect =>
							sendPacketToServer(p, "main")
							startSlaveBots()
						case p: FriendUpdate =>
							addBuddy(p)
						case p: FriendRemove =>
							remBuddy(p)
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

	private def addBuddy(packet: FriendUpdate): Unit = {
		var currentBot: BotManager = null
		var currentCount = 1000
		buddyList.synchronized {
			for ((bot, buddies) <- buddyList) {
				if (buddies.contains(packet.getCharId)) {
					logger.debug("buddy re-added to " + bot.id)
					bot.sendPacket(packet)
					return
				//} else if (buddies.size < currentCount && bot.getCharacterId != packet.getCharId) {
				} else if (buddies.size < currentCount) {
					currentBot = bot
					currentCount = buddies.size
				}
			}

			if (currentBot != null) {
				logger.debug("buddy added to " + currentBot.id)
				buddyList(currentBot) += packet.getCharId
				currentBot.sendPacket(packet)
			} else {
        logger.warn("Could not add buddy for char_id " + packet.getCharId)
      }
		}
	}

	private def remBuddy(packet: FriendRemove): Unit = {
		buddyList.synchronized {
			for ((bot, slaveBotList) <- buddyList) {
				if (slaveBotList.contains(packet.getCharId)) {
					logger.debug("buddy removed from " + bot.id)
					bot.sendPacket(packet)
				}
			}
		}
	}

	def sendPacketToMasterBot(packet: BaseServerPacket): Unit = {
		logger.debug("TO MASTER " + packet)
		masterBot.sendPacket(packet)
	}

	def startSlaveBots(): Unit = {
		buddyList.synchronized {
			bots.foreach{ case (id, bot) =>
				buddyList(bot) = mutable.Set[Long]()
				if (id != "main") {
					logger.info("starting proxy bot " + id)
					bot.start()
				}
			}
		}
	}

	def sendPacketToServer(packet: BaseClientPacket, botId: String): Unit = {
		bots(botId).sendPacket(packet)
	}

	def addBuddy(packet: server.FriendUpdate, bot: BotManager): Unit = {
		buddyList.synchronized {
			buddyList.find(x => x._1 != bot && x._2.contains(packet.getCharId)) match {
				case Some(_) =>
					// if buddy is already register on another bot, remove it from this one
					logger.info("duplicate buddy detected and removed on " + bot.id + ": " + packet)
					buddyList(bot).remove(packet.getCharId)
					bot.sendPacket(new FriendRemove(packet.getCharId))
				case None =>
					// otherwise forward packet to master bot
					buddyList(bot) += packet.getCharId
					sendPacketToMasterBot(packet)
			}
		}
	}

	def removeBuddy(packet: server.FriendRemove, bot: BotManager): Unit = {
		buddyList.synchronized {
			if (buddyList(bot).contains(packet.getCharId)) {
				buddyList(bot).remove(packet.getCharId)
				sendPacketToMasterBot(packet)
			}
		}
	}

	def processPacket(packet: BaseServerPacket, bot: BotManager): Unit = {
		packet match {
			case p: server.LoginOk =>
        // send login ok
        sendPacketToMasterBot(p)
			case p: server.FriendUpdate =>
				addBuddy(p, bot)
			case p: server.FriendRemove =>
				removeBuddy(p, bot)
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
			bots.foreach(_._2.close())
			masterBot.close()
		}
	}

	def isRunning(): Boolean = {
		this.isAlive && bots.values.forall(_.isAlive)
	}
}