package com.jkbff.ao.chatproxy

import aoChatLib.Crypto
import com.jkbff.ao.tyrlib.packets._
import com.jkbff.ao.tyrlib.packets.client.LoginSelect
import com.jkbff.ao.tyrlib.packets.server.BaseServerPacket
import org.apache.log4j.Logger.getLogger

class SlaveBotManager(id: String, username: String, password: String, characterName: String, serverAddress: String, serverPort: Int, serverPacketFactory: PacketFactory[BaseServerPacket], clientHandler: ClientHandler)
    extends BotManager(id, serverAddress, serverPort, serverPacketFactory, clientHandler) {

  var lastSentPing: Long = _
  private val logger = getLogger("com.jkbff.ao.chatproxy.ClientHandler")

  override def run(): Unit = {
    logger.debug("Starting slave bot manager for " + id)
    try {
      aoClientSocket.start()
      lastSentPing = System.currentTimeMillis()
      while (!shouldStop) {
        if (System.currentTimeMillis() - lastSentPing > 60000) {
          aoClientSocket.sendPacket(new client.Ping("ping"))
          lastSentPing = System.currentTimeMillis()
        }
        val packet = aoClientSocket.readPacket()
        if (packet != null) {
          logger.debug("Received packet from " + id + ": " + packet)
          process(packet)
        }
      }
    } catch {
      case e: Throwable =>
        logger.error("", e)
    } finally {
      clientHandler.close()
    }
  }

  def process(packet: server.BaseServerPacket): Unit = {
    packet match {
      case p: server.LoginSeed =>
        val loginKey = Crypto.generateKey(username, password, p.getSeed)
        val loginRequest = new client.LoginRequest(0, username, loginKey)
        aoClientSocket.sendPacket(loginRequest)
      case p: server.CharacterList =>
        val character = p.getLoginUsers.find(x => characterName.equalsIgnoreCase(x.getName)).getOrElse(throw new Exception(s"Could not find character $characterName on account $username"))
        val loginSelect = new LoginSelect(character.getUserId)
        aoClientSocket.sendPacket(loginSelect)
      case p: server.LoginError =>
        throw new Exception(s"Could not login with character $characterName on account $username: ${p.getMessage}")
      case _ =>
        clientHandler.processPacket(packet, this)
    }
  }
}
