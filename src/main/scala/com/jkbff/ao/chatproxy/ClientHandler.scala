package com.jkbff.ao.chatproxy

import java.io._
import java.net.Socket

import com.jkbff.ao.tyrlib.chat.socket.{AOClientSocket, ClientPacketFactory, ServerPacketFactory}
import com.jkbff.ao.tyrlib.packets.client.{BaseClientPacket, FriendRemove, FriendUpdate, LoginSelect}
import com.jkbff.ao.tyrlib.packets.server
import com.jkbff.ao.tyrlib.packets.server.BaseServerPacket
import org.apache.log4j.Logger._

import scala.collection.mutable

class ClientHandler(botInfo: Map[String, BotLoginInfo], serverAddress: String, serverPort: Int, socket: Socket) extends Thread {
	object ConnectionState extends Enumeration {
		type ConnectionState = Value
		val DISCONNECTED, SENT_SEED, SENT_CHAR_LIST, CONNECTED = Value
	}

	private val log_master = getLogger("com.jkbff.ao.chatproxy.ClientHandler.master")
	val serverPacketFactory = new ServerPacketFactory

	var in: DataInputStream = _
	var out: OutputStream = _
	var shouldStop = false
	val bots: Map[String, BotManager] = botInfo.map { case (id, info) =>
		(id,
			new SlaveBotManager(
				id,
				info.username,
				info.password,
				info.characterName,
				new AOClientSocket(id, new Socket(serverAddress, serverPort), serverPacketFactory), this))
	} + (("main", new BotManager("main", new AOClientSocket("main", new Socket(serverAddress, serverPort), serverPacketFactory), this)))

	val friendlist = new mutable.HashMap[BotManager, mutable.Set[Long]]
	val clientPacketFactory = new ClientPacketFactory

	override def run() {
		try {
			in = new DataInputStream(socket.getInputStream)
			out = socket.getOutputStream

			bots("main").start()

			while (!shouldStop) {
				val packet = readPacketFromMaster()
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
		} catch {
			case e: Exception => log_master.error("", e)
		} finally {
			shutdown()
		}
	}

	private def addBuddy(packet: FriendUpdate): Unit = {
		var currentBot: BotManager = null
		var currentCount = 1000
		friendlist.synchronized {
			for ((bot, buddies) <- friendlist) {
				if (buddies.contains(packet.getCharId)) {
					log_master.debug("buddy re-added to " + bot.getId)
					bot.sendPacket(packet)
					return
				//} else if (buddies.size < currentCount && bot.getCharacterId != packet.getCharId) {
				} else if (buddies.size < currentCount) {
					currentBot = bot
					currentCount = buddies.size
				}
			}

			if (currentBot != null) {
				log_master.debug("buddy added to " + currentBot.getId)
				friendlist(currentBot) += packet.getCharId
				currentBot.sendPacket(packet)
			} else {
        log_master.warn("Could not add buddy for char_id " + packet.getCharId)
      }
		}
	}

	private def remBuddy(packet: FriendRemove): Unit = {
		friendlist.synchronized {
			for ((bot, slaveBotList) <- friendlist) {
				if (slaveBotList.contains(packet.getCharId)) {
					log_master.debug("buddy removed from " + bot.getId)
					bot.sendPacket(packet)
				}
			}
		}
	}

	def sendPacketToMasterBot(packet: BaseServerPacket): Unit = {
		log_master.debug("TO MASTER " + packet)
		out.write(packet.getBytes)
	}

	def startSlaveBots(): Unit = {
		friendlist.synchronized {
			bots.foreach{ case (id, bot) =>
				friendlist(bot) = mutable.Set[Long]()
				if (id != "main") {
					log_master.info("starting proxy bot " + id)
					bot.start()
				}
			}
		}
	}

	def readPacketFromMaster(): BaseClientPacket = {
		val packetId = in.readUnsignedShort()
		val packetLength = in.readUnsignedShort()
		val payload = new Array[Byte](packetLength)
		in.readFully(payload)

		val packet: BaseClientPacket = clientPacketFactory.createInstance(packetId, payload)
		log_master.debug("FROM MASTER " + packet.toString)
		packet
	}

	def sendPacketToServer(packet: BaseClientPacket, botId: String): Unit = {
		bots(botId).sendPacket(packet)
	}

	def processPacket(packet: server.FriendUpdate, bot: BotManager): Unit = {
		friendlist.synchronized {
			friendlist.find(x => x._1 != bot && x._2.contains(packet.getCharId)) match {
				case Some(_) =>
					// if buddy is already register on another bot, remove it from this one
					log_master.info("duplicate buddy detected and removed on " + bot.getId + ": " + packet)
					friendlist(bot).remove(packet.getCharId)
					bot.sendPacket(new FriendRemove(packet.getCharId))
				case None =>
					// otherwise forward packet to master bot
					friendlist(bot) += packet.getCharId
					sendPacketToMasterBot(packet)
			}
		}
	}

	def processPacket(packet: server.FriendRemove, bot: BotManager): Unit = {
		friendlist.synchronized {
			if (friendlist(bot).contains(packet.getCharId)) {
				friendlist(bot).remove(packet.getCharId)
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
				processPacket(p, bot)
			case p: server.FriendRemove =>
				processPacket(p, bot)
			case p: server.CharacterUpdate =>
				sendPacketToMasterBot(packet)
			case _ if bot.id == "main" =>
				// if packet came from main bot connection and
				// if packet isn't a packet that requires special handling (cases above)
				// then forward to master bot
				sendPacketToMasterBot(packet)
			case _ =>
				// ignore packets from bots that aren't main and aren't already handled
		}
	}

	def shutdown() {
		log_master.info("Shutting down client handler")
		shouldStop = true
		bots.foreach(_._2.close())
		in.close()
		out.close()
		socket.close()
	}

	def isRunning: Boolean = {
		this.isAlive && bots.values.forall(_.isAlive)
	}
}