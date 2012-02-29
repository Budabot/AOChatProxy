package com.jkbff.ao.chatproxy
import com.jkbff.ao.tyrlib.chat.{ChatPacketHandler, AOSocket}
import com.jkbff.ao.tyrlib.packets.{BaseServerPacket, BaseClientPacket}
import com.jkbff.ao.tyrlib.packets.BaseClientPacket._
import com.jkbff.ao.tyrlib.packets.client.FriendRemove
import com.jkbff.ao.tyrlib.packets.client.FriendUpdate
import com.jkbff.ao.tyrlib.packets.server._
import java.io._
import java.lang.reflect.{Method, InvocationTargetException}
import java.net.Socket
import org.apache.log4j.Logger
import org.apache.log4j.Logger._
import scala.collection.mutable.HashMap

class ClientHandler extends Thread with ChatPacketHandler {
	object ConnectionState extends Enumeration {
		type ConnectionState = Value
		val DISCONNECTED, SENT_SEED, SENT_CHAR_LIST, CONNECTED = Value
    }
	import ConnectionState._
	
	private val log_master = getLogger("com.jkbff.ao.chatproxy.ClientHandler.master")
	private val log_server = getLogger("com.jkbff.ao.chatproxy.ClientHandler.server")

	var socket:Socket = null
	var state:ConnectionState = DISCONNECTED
	var in:DataInputStream = null
	var out:OutputStream = null
	var bots:HashMap[String, AOSocket] = null
	var mainBotCharacter:String = null;
	
	val friendlist = new HashMap[AOSocket, HashMap[Long, String]]
	
	override def run() {
		try {
			in = new DataInputStream(socket.getInputStream)
			out = socket.getOutputStream
	
			// send seed packet
			sendPacketToMasterBot(new LoginSeed("11111111111111111111111111111111"))
			state = SENT_SEED
			
			// wait for login request
			readPacketFromMaster
			
			// send character list
			sendPacketToMasterBot(new CharacterList(Array(1), Array(mainBotCharacter), Array(1), Array(0)))
			state = SENT_CHAR_LIST
			
			// wait for selected character
			readPacketFromMaster
			
			startBots
			
			while (true) {
				val packet = readPacketFromMaster
				packet match {
					case p:FriendUpdate => addBuddy(p) 
					case p:FriendRemove => remBuddy(p)
					case _ => sendPacketToServer(packet)
				}
			}
		} catch {
			case e:Exception => log_master.error("", e)
		}
	}
	
	private def addBuddy(packet:FriendUpdate) : Unit = {
		var currentBot:AOSocket = null
		var currentCount = 1000
		friendlist.synchronized {
			for ((bot, value) <- friendlist) {
				if (friendlist(bot).contains(packet.getCharId)) {
					log_master.debug("buddy re-added")
					bot.sendPacket(packet)
					return;
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
	
	private def remBuddy(packet:FriendRemove) : Unit = {
		friendlist.synchronized {
			for ((bot, value) <- friendlist) {
				if (friendlist(bot).contains(packet.getCharId)) {
					log_master.debug("buddy removed")
					bot.sendPacket(packet)
				}
			}
		}
	}
	
	def sendPacketToMasterBot(packet:BaseServerPacket) : Unit = {
		log_master.debug("TO MASTER " + packet)
		out.write(packet.getBytes)
	}
	
	def startBots() : Unit = {
		if (bots != null) {
			friendlist.synchronized {
				for ((name, bot) <- bots) {
					friendlist(bot) = new HashMap[Long, String]
					if (!bot.isAlive()) {
						log_master.info("starting proxy bot " + name)
						bot.start
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
        
        val packet:BaseClientPacket = createInstance(packetId, payload)
        log_master.debug("FROM MASTER " + packet.toString)
        return packet
	}
	
	def sendPacketToServer(packet:BaseClientPacket, botId:String = "main") : Unit = {
		bots(botId).sendPacket(packet)
	}

	def processPacket(packet:LoginOk, bot:AOSocket) : Unit = {
		if (state != CONNECTED) {
			// send login ok
			sendPacketToMasterBot(packet)
			state = CONNECTED
		}
	}
	
	def processPacket(packet:com.jkbff.ao.tyrlib.packets.server.FriendUpdate, bot:AOSocket) : Unit = {
		friendlist.synchronized {
			for ((currentBot, buddies) <- friendlist) {
				if (currentBot != bot && buddies.contains(packet.getCharId)) {
					log_master.info("duplicate buddy detected and removed: " + packet)
					friendlist(bot).remove(packet.getCharId)
					bot.sendPacket(new FriendRemove(packet.getCharId))
					return;
				}
			}
		
			friendlist(bot)(packet.getCharId) = ""
		}
		sendPacketToMasterBot(packet)
	}
	
	def processPacket(packet:com.jkbff.ao.tyrlib.packets.server.FriendRemove, bot:AOSocket) : Unit = {
		friendlist.synchronized {
			if (friendlist(bot).contains(packet.getCharId)) {
				friendlist(bot).remove(packet.getCharId)
				sendPacketToMasterBot(packet)
			}
		}
	}
	
	def processPacket(packet:com.jkbff.ao.tyrlib.packets.server.CharacterUpdate, bot:AOSocket) : Unit = {
		sendPacketToMasterBot(packet)
	}

	def processPacket(packet:BaseServerPacket, bot:AOSocket) : Unit = {
		try {
    		val method = this.getClass().getDeclaredMethod("processPacket", packet.getClass(), classOf[AOSocket]);
    		method.invoke(this, packet, bot);
		} catch {
			// if no specific method exists, forward the packet to the proxy bot, but only if it came from the main bot
			case e:NoSuchMethodException => if (bot == bots("main")) sendPacketToMasterBot(packet)
			case e:InvocationTargetException => log_server.error("", e.getCause); e.printStackTrace()
			case e:Exception => log_server.error("", e)
		}
    }
	
	def shutdownEvent() {
		
	}

  def isRunning: Boolean = {
    if (!this.isAlive()) {
      return false;
    }
    for (aoBot <- bots.values) {
      if (!aoBot.isAlive()) {
        return false;
      }
    }
    return true;
  }
}