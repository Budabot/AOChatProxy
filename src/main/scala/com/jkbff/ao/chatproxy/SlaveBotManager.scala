package com.jkbff.ao.chatproxy

import com.jkbff.ao.tyrlib.chat.Helper
import com.jkbff.ao.tyrlib.chat.socket.PacketFactory
import com.jkbff.ao.tyrlib.packets._
import com.jkbff.ao.tyrlib.packets.client.LoginSelect
import com.jkbff.ao.tyrlib.packets.server.BaseServerPacket
import org.apache.log4j.Logger.getLogger

class SlaveBotManager(id: String, username: String, password: String, characterName: String, serverAddress: String, serverPort: Int, serverPacketFactory: PacketFactory[BaseServerPacket], clientHandler: ClientHandler)
    extends BotManager(id, serverAddress, serverPort, serverPacketFactory, clientHandler) {

  var lastSentPing: Long = _
  private val logger = getLogger("com.jkbff.ao.chatproxy.ClientHandler")

  override def run(): Unit = {
    try {
      aoClientSocket.start()
      lastSentPing = System.currentTimeMillis()
      while (!shouldStop) {
        if (System.currentTimeMillis() - lastSentPing > 60000) {
          aoClientSocket.sendPacket(new client.Ping("ping"))
        }
        val packet = aoClientSocket.readPacket()
        if (packet != null) {
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
        val loginKey = Helper.generateLoginKey(username, password, p.getSeed)
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
