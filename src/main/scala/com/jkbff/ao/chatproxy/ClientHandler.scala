package com.jkbff.ao.chatproxy

import java.io._
import java.net.Socket

import com.jkbff.ao.tyrlib.chat.{AOSocket, ChatPacketHandler}
import com.jkbff.ao.tyrlib.packets.BaseClientPacket._
import com.jkbff.ao.tyrlib.packets.client.{FriendRemove, FriendUpdate, LoginSelect}
import com.jkbff.ao.tyrlib.packets.{BaseClientPacket, BaseServerPacket, server}
import org.apache.log4j.Logger._

import scala.collection.mutable

class ClientHandler extends Thread with ChatPacketHandler {
	object ConnectionState extends Enumeration {
		type ConnectionState = Value
		val DISCONNECTED, SENT_SEED, SENT_CHAR_LIST, CONNECTED = Value
	}

	private val log_master = getLogger("com.jkbff.ao.chatproxy.ClientHandler.master")
	private val log_server = getLogger("com.jkbff.ao.chatproxy.ClientHandler.server")

	var socket: Socket = _
	var in: DataInputStream = _
	var out: OutputStream = _
	var bots: Map[String, AOSocket] = _

	val friendlist = new mutable.HashMap[AOSocket, mutable.HashMap[Long, String]]

	override def run() {
		try {
			in = new DataInputStream(socket.getInputStream)
			out = socket.getOutputStream

			bots("main").start()

			while (true) {
				val packet = readPacketFromMaster()
				packet match {
					case p: LoginSelect =>
						// start slave bots
						sendPacketToServer(p)
						startBots()
					case p: FriendUpdate =>
						addBuddy(p)
					case p: FriendRemove =>
						remBuddy(p)
					case _ =>
						sendPacketToServer(packet)
				}
			}
		} catch {
			case e: Exception => log_master.error("", e)
		}
	}

	private def addBuddy(packet: FriendUpdate): Unit = {
		var currentBot: AOSocket = null
		var currentCount = 1000
		friendlist.synchronized {
			for ((bot, value) <- friendlist) {
				if (friendlist(bot).contains(packet.getCharId)) {
					log_master.debug("buddy re-added")
					bot.sendPacket(packet)
					return ;
				} else if (value.size < currentCount && bot.getCharacterId != packet.getCharId) {
					currentBot = bot
					currentCount = value.size
				}
			}

			if (currentBot != null) {
				log_master.debug("buddy added")
				friendlist(currentBot)(packet.getCharId) = ""
				currentBot.sendPacket(packet)
			}
		}
	}

	private def remBuddy(packet: FriendRemove): Unit = {
		friendlist.synchronized {
			for ((bot, slaveBotList) <- friendlist) {
				if (slaveBotList.contains(packet.getCharId)) {
					log_master.debug("buddy removed")
					bot.sendPacket(packet)
				}
			}
		}
	}

	def sendPacketToMasterBot(packet: BaseServerPacket): Unit = {
		log_master.debug("TO MASTER " + packet)
		out.write(packet.getBytes)
	}

	def startBots(): Unit = {
		if (bots != null) {
			friendlist.synchronized {
				for ((name, bot) <- bots) {
					friendlist(bot) = new mutable.HashMap[Long, String]
					if (!bot.isAlive) {
						log_master.info("starting proxy bot " + name)
						bot.start()
					}
				}
			}
		}
	}

	def readPacketFromMaster(): BaseClientPacket = {
		val packetId = in.readUnsignedShort()
		val packetLength = in.readUnsignedShort()
		val payload = new Array[Byte](packetLength)
		in.readFully(payload)

		val packet: BaseClientPacket = createInstance(packetId, payload)
		log_master.debug("FROM MASTER " + packet.toString)
		packet
	}

	def sendPacketToServer(packet: BaseClientPacket, botId: String = "main"): Unit = {
		bots(botId).sendPacket(packet)
	}

	def processPacket(packet: server.LoginOk, bot: AOSocket): Unit = {
		// send login ok
		sendPacketToMasterBot(packet)
	}

	def processPacket(packet: server.FriendUpdate, bot: AOSocket): Unit = {
		friendlist.synchronized {
			friendlist.find(x => x._1 != bot && x._2.contains(packet.getCharId)) match {
				case Some(_) =>
					// if buddy is already register on another bot, remove it from this one
					log_master.info("duplicate buddy detected and removed: " + packet)
					friendlist(bot).remove(packet.getCharId)
					bot.sendPacket(new FriendRemove(packet.getCharId))
				case None =>
					// otherwise forward packet to master bot
					friendlist(bot)(packet.getCharId) = ""
					sendPacketToMasterBot(packet)
			}
		}
	}

	def processPacket(packet: server.FriendRemove, bot: AOSocket): Unit = {
		friendlist.synchronized {
			if (friendlist(bot).contains(packet.getCharId)) {
				friendlist(bot).remove(packet.getCharId)
				sendPacketToMasterBot(packet)
			}
		}
	}

	def processPacket(packet: server.CharacterUpdate, bot: AOSocket): Unit = {
		sendPacketToMasterBot(packet)
	}

	def processPacket(packet: BaseServerPacket, bot: AOSocket): Unit = {
		packet match {
			case p: server.LoginOk =>
				processPacket(p, bot)
			case p: server.FriendUpdate =>
				processPacket(p, bot)
			case p: server.FriendRemove =>
				processPacket(p, bot)
			case p: server.CharacterUpdate =>
				processPacket(p, bot)
			case _ if bot == bots("main") =>
				// if packet came from main bot connection and
				// if packet isn't a packet that requires special handling (cases above)
				// then forward to master bot
				sendPacketToMasterBot(packet)
			case _ =>
				// ignore packets from bots that aren't main and aren't already handled
		}
	}

	def shutdownEvent() {

	}

	def isRunning: Boolean = {
		this.isAlive && bots.values.forall(_.isAlive)
	}
}