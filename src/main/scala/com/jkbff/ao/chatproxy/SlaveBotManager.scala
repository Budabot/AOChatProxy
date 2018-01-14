package com.jkbff.ao.chatproxy

import com.jkbff.ao.tyrlib.chat.Helper
import com.jkbff.ao.tyrlib.chat.socket.AOClientSocket
import com.jkbff.ao.tyrlib.packets._
import com.jkbff.ao.tyrlib.packets.client.LoginSelect

class SlaveBotManager(id: String, username: String, password: String, characterName: String, aoClient: AOClientSocket, clientHandler: ClientHandler) extends BotManager(id, aoClient, clientHandler) {
  var lastSentPing = 0L

  override def run(): Unit = {
    try {
      aoClient.start()
      lastSentPing = System.currentTimeMillis()
      while (!shouldStop) {
        if (System.currentTimeMillis() - lastSentPing > 60000) {
          aoClient.sendPacket(new client.Ping("ping"))
        }
        val packet = aoClient.readPacket()
        if (packet != null) {
          process(packet)
        }
      }
    } catch {
      case e: Throwable => e.printStackTrace()
    } finally {
      close()
    }
  }

  def process(packet: server.BaseServerPacket): Unit = {
    packet match {
      case p: server.LoginSeed =>
        val loginKey = Helper.generateLoginKey(username, password, p.getSeed)
        val loginRequest = new client.LoginRequest(0, username, loginKey)
        aoClient.sendPacket(loginRequest)
      case p: server.CharacterList =>
        val character = p.getLoginUsers.find(x => characterName.equalsIgnoreCase(x.getName)).getOrElse(throw new Exception(s"Could not find character $characterName on account $username"))
        val loginSelect = new LoginSelect(character.getUserId)
        aoClient.sendPacket(loginSelect)
      case p: server.LoginError =>
        throw new Exception(s"Could not login with character $characterName on account $username: ${p.getMessage}")
      case _ =>
        clientHandler.processPacket(packet, this)
    }
  }
}
